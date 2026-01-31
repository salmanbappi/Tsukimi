package org.koitharu.kotatsu.core.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.core.work.AiModelDownloadWorker
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiResourceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelFile = File(context.filesDir, "models/realesrgan_x4plus_anime_6b.tflite")
    
    private val _isResourceReady = MutableStateFlow(modelFile.exists() && modelFile.length() > 10000000)
    val isResourceReady = _isResourceReady.asStateFlow()

    fun checkResources() {
        _isResourceReady.value = modelFile.exists() && modelFile.length() > 10000000
    }

    fun isDownloading(): Boolean {
        val workManager = androidx.work.WorkManager.getInstance(context)
        val infos = workManager.getWorkInfosByTag("AiModelDownloadWorker").get()
        return infos.any { it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED }
    }

    fun downloadResources() {
        AiModelDownloadWorker.enqueue(context)
    }
}
