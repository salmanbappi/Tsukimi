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
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.io.File

@HiltWorker
class AiModelDownloadWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	@BaseHttpClient private val client: OkHttpClient,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val modelDir = File(applicationContext.filesDir, "models")
		if (!modelDir.exists()) modelDir.mkdirs()

		val models = mapOf(
			"waifu2x_fast.tflite" to "https://github.com/salmanbappi/AI-Models/releases/download/v1.0/waifu2x_fast.tflite",
			"esrgan_elite.tflite" to "https://github.com/salmanbappi/AI-Models/releases/download/v1.0/esrgan_elite.tflite"
		)

		for ((name, url) in models) {
			val file = File(modelDir, name)
			if (file.exists() && file.length() > 1000) continue

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
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}

		return Result.success()
	}

	companion object {
		private const val TAG = "AiModelDownloadWorker"

		fun enqueue(context: Context) {
			val request = OneTimeWorkRequestBuilder<AiModelDownloadWorker>()
				.addTag(TAG)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
		}
	}
}
