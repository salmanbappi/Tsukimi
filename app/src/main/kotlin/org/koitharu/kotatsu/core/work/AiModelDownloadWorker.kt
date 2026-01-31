package org.koitharu.kotatsu.core.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.core.ai.model.ModelDownloadProgress
import org.koitharu.kotatsu.core.ai.model.ModelDownloadStatusProvider
import org.koitharu.kotatsu.core.network.MangaHttpClient
import java.io.File
import kotlinx.coroutines.runBlocking

@HiltWorker
class AiModelDownloadWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val statusProvider: ModelDownloadStatusProvider,
) : CoroutineWorker(context, params) {

	private val client = OkHttpClient.Builder()
		.connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
		.readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
		.build()

	private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		Log.i(TAG, "Starting AI Model Download Worker")
		createNotificationChannel()
		
		try {
			setForeground(createForegroundInfo(0))
		} catch (e: Exception) {
			Log.e(TAG, "Failed to set foreground", e)
		}

		val modelDir = File(applicationContext.filesDir, "models")
		if (!modelDir.exists()) {
			modelDir.mkdirs()
		}

		val models = mapOf(
			"realesrgan_x4plus_anime_6b.tflite" to listOf(
				"https://github.com/salmanbappi/AI-Models/releases/download/v1.0/realesrgan_x4plus_anime_6b.tflite",
				"https://huggingface.co/qualcomm/Real-ESRGAN-x4plus/resolve/main/Real-ESRGAN-x4plus-w8a8.tflite",
				"https://huggingface.co/repsup/Real-ESRGAN-TFLite/resolve/main/realesrgan-x4plus-anime.tflite"
			)
		)

		var downloaded = 0
		for ((name, urls) in models) {
			val file = File(modelDir, name)
			if (file.exists() && file.length() > 15000000) {
				Log.i(TAG, "Model $name already exists and looks valid, skipping")
				downloaded++
				continue
			}

			var success = false
			var lastError: String? = null
			
			for (url in urls) {
				Log.i(TAG, "Downloading model $name from $url")
				try {
					runBlocking { statusProvider.updateProgress(ModelDownloadProgress(0, 0, 0, ModelDownloadProgress.Status.CONNECTING)) }
					val request = Request.Builder()
						.url(url)
						.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
						.build()
					
					success = client.newCall(request).execute().use { response ->
						if (!response.isSuccessful) {
							Log.e(TAG, "Failed to download from $url: ${response.code}")
							lastError = "Server returned ${response.code}"
							return@use false
						}
						val body = response.body ?: return@use false
						val totalSize = body.contentLength()
						var bytesRead = 0L
						
						body.source().use { source ->
							file.sink().buffer().use { sink ->
								val buffer = okio.Buffer()
								var read: Long
								while (source.read(buffer, 8192).also { read = it } != -1L) {
									sink.write(buffer, read)
									bytesRead += read
									if (totalSize > 0) {
										val progress = ((bytesRead.toFloat() / totalSize) * 100).toInt()
										setForeground(createForegroundInfo(progress))
										runBlocking {
											statusProvider.updateProgress(ModelDownloadProgress(progress, totalSize, bytesRead, ModelDownloadProgress.Status.DOWNLOADING))
										}
									}
								}
							}
						}
						true
					}
					if (success) {
						Log.i(TAG, "Successfully saved $name, size: ${file.length()}")
						runBlocking { statusProvider.updateProgress(ModelDownloadProgress(100, file.length(), file.length(), ModelDownloadProgress.Status.COMPLETED)) }
						downloaded++
						break
					}
				} catch (e: Exception) {
					Log.e(TAG, "Error downloading model from $url", e)
					lastError = e.message
				}
			}
			
			if (!success) {
				runBlocking { statusProvider.updateProgress(ModelDownloadProgress(0, 0, 0, ModelDownloadProgress.Status.FAILED, lastError)) }
			}
		}

		Log.i(TAG, "AI Model Download Worker finished. Downloaded: $downloaded/${models.size}")
		if (downloaded == models.size) Result.success() else Result.failure()
	}

	private fun createForegroundInfo(progress: Int): ForegroundInfo {
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle("Downloading AI Models")
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setProgress(100, progress, false)
			.build()
		return ForegroundInfo(NOTIFICATION_ID, notification)
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(CHANNEL_ID, "AI Model Downloads", NotificationManager.IMPORTANCE_LOW)
			notificationManager.createNotificationChannel(channel)
		}
	}

	companion object {
		private const val TAG = "AiModelDownloadWorker"
		private const val CHANNEL_ID = "ai_downloads"
		private const val NOTIFICATION_ID = 1001

		fun enqueue(context: Context) {
			Log.d(TAG, "Enqueuing AiModelDownloadWorker")
			val request = OneTimeWorkRequestBuilder<AiModelDownloadWorker>()
				.addTag(TAG)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
		}
	}
}