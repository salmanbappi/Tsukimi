package org.koitharu.kotatsu.details.ui.pager.chapters

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.view.WindowInsetsCompat
import org.koitharu.kotatsu.core.ai.model.UpscaleProgress
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetUpscaleProgressBinding

@AndroidEntryPoint
class UpscaleProgressSheet : BaseAdaptiveSheet<SheetUpscaleProgressBinding>() {

    private val viewModel by viewModels<UpscaleProgressViewModel>()

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ) = SheetUpscaleProgressBinding.inflate(inflater, container, false)

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        return insets
    }

    override fun onViewBindingCreated(binding: SheetUpscaleProgressBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.buttonClose.setOnClickListener { dismiss() }

        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            if (progress == null) return@observe
            
            binding.textViewStatus.text = when (progress.status) {
                UpscaleProgress.Status.INITIALIZING -> "Initializing high-performance engine..."
                UpscaleProgress.Status.PROCESSING -> "Processing Page ${progress.currentPageIndex + 1} of ${progress.totalPages}"
                UpscaleProgress.Status.SAVING -> "Saving high-quality chapter..."
                UpscaleProgress.Status.COMPLETED -> "Upscaling successfully complete!"
                UpscaleProgress.Status.FAILED -> "Upscaling failed. Please check logs."
            }

            binding.progressOverall.progress = progress.overallPercentage
            binding.progressPage.progress = progress.pagePercentage
            binding.textViewPageProgress.text = "Current Page: ${progress.pagePercentage}% (${progress.pagePartsUpscaled}/${progress.totalPageParts} parts)"
            
            val minutes = progress.timeLeftSeconds / 60
            val seconds = progress.timeLeftSeconds % 60
            binding.textViewTimer.text = if (progress.timeLeftSeconds > 0) {
                "${String.format("%02d:%02d", minutes, seconds)} remaining"
            } else if (progress.status == UpscaleProgress.Status.COMPLETED) {
                "Processed ${progress.totalPages} pages"
            } else {
                "Calculating speed..."
            }

            if (progress.status == UpscaleProgress.Status.COMPLETED) {
                binding.buttonClose.text = "Finish"
                binding.textViewTimer.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
            }
        }
    }
}
