package org.koitharu.kotatsu.details.ui.pager.chapters

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.core.ai.model.UpscaleStatusProvider
import org.koitharu.kotatsu.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class UpscaleProgressViewModel @Inject constructor(
    statusProvider: UpscaleStatusProvider
) : BaseViewModel() {
    val progress = statusProvider.progress.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )
}
