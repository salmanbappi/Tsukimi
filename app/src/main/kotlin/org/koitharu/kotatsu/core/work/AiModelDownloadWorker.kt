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
import org.koitharu.kotatsu.core.network.MangaHttpClient
import java.io.File

@HiltWorker
class AiModelDownloadWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	@MangaHttpClient private val client: OkHttpClient,
) : CoroutineWorker(context, params) {

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
			val created = modelDir.mkdirs()
			Log.d(TAG, "Created models directory: $created")
		}

		val models = mapOf(
			"waifu2x_fast.tflite" to "https://github.com/salmanbappi/AI-Models/releases/download/v1.0/waifu2x_fast.tflite",
			"esrgan_elite.tflite" to "https://github.com/salmanbappi/AI-Models/releases/download/v1.0/esrgan_elite.tflite"
		)

		var downloaded = 0
		for ((name, url) in models) {
			val file = File(modelDir, name)
			if (file.exists() && file.length() > 500000) {
				Log.i(TAG, "Model $name already exists, skipping")
				downloaded++
				continue
			}

			Log.i(TAG, "Downloading model $name from $url")
			try {
				val request = Request.Builder().url(url).build()
				client.newCall(request).execute().use { response ->
					if (!response.isSuccessful) {
						Log.e(TAG, "Failed to download $name: ${response.code}")
						return@use
					}
					response.body?.source()?.let { source ->
						file.sink().buffer().use { sink ->
							sink.writeAll(source)
						}
						Log.i(TAG, "Successfully saved $name, size: ${file.length()}")
					}
				}
				downloaded++
				try {
					setForeground(createForegroundInfo((downloaded * 100) / models.size))
				} catch (e: Exception) {
					// Ignore
				}
			} catch (e: Exception) {
				Log.e(TAG, "Error downloading model $name", e)
			}
		}

		Log.i(TAG, "AI Model Download Worker finished. Downloaded: $downloaded/${models.size}")
		Result.success()
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
			WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
		}
	}
}