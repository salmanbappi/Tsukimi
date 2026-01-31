package org.koitharu.kotatsu.details.ui.pager.chapters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ai.model.ModelDownloadProgress
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetModelDownloadProgressBinding

@AndroidEntryPoint
class ModelDownloadProgressSheet : BaseAdaptiveSheet<SheetModelDownloadProgressBinding>() {

    private val viewModel by viewModels<ModelDownloadProgressViewModel>()

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = SheetModelDownloadProgressBinding.inflate(inflater, container, false)

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        return insets
    }

    override fun onViewBindingCreated(binding: SheetModelDownloadProgressBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.buttonClose.setOnClickListener { dismiss() }

        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            if (progress == null) return@observe
            
            val totalMb = String.format("%.1f", progress.totalSize.toFloat() / (1024 * 1024))
            val readMb = String.format("%.1f", progress.bytesRead.toFloat() / (1024 * 1024))

            binding.textViewStatus.text = when (progress.status) {
                ModelDownloadProgress.Status.CONNECTING -> "Connecting to server..."
                ModelDownloadProgress.Status.DOWNLOADING -> "Downloading: ${progress.progress}% ($readMb MB / $totalMb MB)"
                ModelDownloadProgress.Status.COMPLETED -> "Download complete!"
                ModelDownloadProgress.Status.FAILED -> "Download failed."
            }

            binding.progressBar.progress = progress.progress
            binding.progressBar.isIndeterminate = progress.status == ModelDownloadProgress.Status.CONNECTING
            
            binding.textViewError.text = progress.errorMessage?.let { "Error: $it" }
            binding.textViewError.isVisible = progress.status == ModelDownloadProgress.Status.FAILED

            if (progress.status == ModelDownloadProgress.Status.COMPLETED) {
                binding.buttonClose.text = "Finish"
            }
        }
    }
}
