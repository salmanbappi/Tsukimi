package org.koitharu.kotatsu.core.ai.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class UpscaleProgress(
    val mangaId: Long,
    val chapterId: Long,
    val totalPages: Int,
    val currentPageIndex: Int,
    val pagePartsUpscaled: Int,
    val totalPageParts: Int,
    val factor: Int,
    val timeLeftSeconds: Long,
    val status: Status,
    val errorMessage: String? = null
) {
    enum class Status {
        INITIALIZING,
        PROCESSING,
        SAVING,
        COMPLETED,
        FAILED
    }

    val overallPercentage: Int
        get() = if (totalPages > 0) ((currentPageIndex.toFloat() / totalPages) * 100).toInt() else 0

    val pagePercentage: Int
        get() = if (totalPageParts > 0) ((pagePartsUpscaled.toFloat() / totalPageParts) * 100).toInt() else 0
}

@Singleton
class UpscaleStatusProvider @Inject constructor() {
    private val _progress = MutableSharedFlow<UpscaleProgress>(replay = 1)
    val progress = _progress.asSharedFlow()

    suspend fun updateProgress(update: UpscaleProgress) {
        _progress.emit(update)
    }
}
