package org.koitharu.kotatsu.core.ai.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ModelDownloadProgress(
    val progress: Int,
    val totalSize: Long,
    val bytesRead: Long,
    val status: Status,
    val errorMessage: String? = null
) {
    enum class Status {
        CONNECTING,
        DOWNLOADING,
        COMPLETED,
        FAILED
    }
}

@Singleton
class ModelDownloadStatusProvider @Inject constructor() {
    private val _progress = MutableSharedFlow<ModelDownloadProgress>(replay = 1)
    val progress = _progress.asSharedFlow()

    suspend fun updateProgress(update: ModelDownloadProgress) {
        _progress.emit(update)
    }
}
