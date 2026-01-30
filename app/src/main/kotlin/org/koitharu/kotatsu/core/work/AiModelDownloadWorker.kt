package org.koitharu.kotatsu.core.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.hilt.work.HiltWorker
import okhttp3.OkHttpClient
import okhttp3.Request
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.io.File

@HiltWorker
class AiModelDownloadWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	@BaseHttpClient private val client: OkHttpClient,
) : CoroutineWorker(context, params) {

	private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

	override suspend fun doWork(): Result {
		createNotificationChannel()
		setForeground(createForegroundInfo(0))

		val modelDir = File(applicationContext.filesDir, "models")
		if (!modelDir.exists()) modelDir.mkdirs()

		val models = mapOf(
			"waifu2x_fast.tflite" to "https://github.com/freedomtan/tensorflow-lite-super-resolution/raw/master/models/waifu2x.tflite",
			"esrgan_elite.tflite" to "https://github.com/freedomtan/tensorflow-lite-super-resolution/raw/master/models/esrgan.tflite"
		)

		var downloaded = 0
		for ((name, url) in models) {
			val file = File(modelDir, name)
			if (file.exists() && file.length() > 1000000) {
				downloaded++
				continue
			}

			try {
				val request = Request.Builder().url(url).build()
				client.newCall(request).execute().use { response ->
					if (!response.isSuccessful) return@use
					response.body?.source()?.let { source ->
						file.sink().buffer().use { sink ->
							sink.writeAll(source)
						}
					}
				}
				downloaded++
				setForeground(createForegroundInfo((downloaded * 100) / models.size))
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}

		return Result.success()
	}

	private fun createForegroundInfo(progress: Int): ForegroundInfo {
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle("Downloading AI Models")
			.setSmallIcon(R.drawable.ic_updated)
			.setOngoing(true)
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
			val request = OneTimeWorkRequestBuilder<AiModelDownloadWorker>()
				.addTag(TAG)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
		}
	}
}
