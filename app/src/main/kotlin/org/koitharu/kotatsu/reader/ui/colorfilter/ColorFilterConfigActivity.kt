package org.koitharu.kotatsu.reader.ui.colorfilter

import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.transformations
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setChecked
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.core.util.progress.ImageRequestIndicatorListener
import org.koitharu.kotatsu.databinding.ActivityColorFilterBinding
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.format
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import javax.inject.Inject

@AndroidEntryPoint
class ColorFilterConfigActivity :
	BaseActivity<ActivityColorFilterBinding>(),
	Slider.OnChangeListener,
	View.OnClickListener, CompoundButton.OnCheckedChangeListener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel: ColorFilterConfigViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityColorFilterBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.sliderBrightness.addOnChangeListener(this)
		viewBinding.sliderContrast.addOnChangeListener(this)
		viewBinding.sliderSharpening?.addOnChangeListener(this)
		viewBinding.sliderDenoising?.addOnChangeListener(this)
		val formatter = PercentLabelFormatter(resources)
		viewBinding.sliderContrast.setLabelFormatter(formatter)
		viewBinding.sliderBrightness.setLabelFormatter(formatter)
		viewBinding.sliderSharpening?.setLabelFormatter(formatter)
		viewBinding.sliderDenoising?.setLabelFormatter(formatter)
		viewBinding.switchInvert.setOnCheckedChangeListener(this)
		viewBinding.switchGrayscale.setOnCheckedChangeListener(this)
		viewBinding.switchBook.setOnCheckedChangeListener(this)
		viewBinding.buttonDone.setOnClickListener(this)
		viewBinding.buttonReset.setOnClickListener(this)

		onBackPressedDispatcher.addCallback(ColorFilterConfigBackPressedDispatcher(this, viewModel))

		viewModel.colorFilter.observe(this, this::onColorFilterChanged)
		viewModel.isLoading.observe(this, this::onLoadingChanged)
		viewModel.onDismiss.observeEvent(this) {
			finishAfterTransition()
		}
		loadPreview(viewModel.preview)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			when (slider.id) {
				R.id.slider_brightness -> viewModel.setBrightness(value)
				R.id.slider_contrast -> viewModel.setContrast(value)
				R.id.slider_sharpening -> viewModel.setSharpening(value)
				R.id.slider_denoising -> viewModel.setDenoising(value)
			}
		}
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		when (buttonView.id) {
			R.id.switch_invert -> viewModel.setInversion(isChecked)
			R.id.switch_grayscale -> viewModel.setGrayscale(isChecked)
			R.id.switch_book -> viewModel.setBookEffect(isChecked)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_done -> showSaveConfirmation()
			R.id.button_reset -> viewModel.reset()
		}
	}

	fun showSaveConfirmation() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.apply)
			.setMessage(R.string.color_correction_apply_text)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.this_manga) { _, _ ->
				viewModel.save()
			}.setNeutralButton(R.string.globally) { _, _ ->
				viewModel.saveGlobally()
			}.show()
	}

	private fun onColorFilterChanged(readerColorFilter: ReaderColorFilter?) {
		viewBinding.sliderBrightness.setValueRounded(readerColorFilter?.brightness ?: 0f)
		viewBinding.sliderContrast.setValueRounded(readerColorFilter?.contrast ?: 0f)
		viewBinding.sliderSharpening?.setValueRounded(readerColorFilter?.sharpening ?: 0f)
		viewBinding.sliderDenoising?.setValueRounded(readerColorFilter?.denoising ?: 0f)
		viewBinding.switchInvert.setChecked(readerColorFilter?.isInverted == true, false)
		viewBinding.switchGrayscale.setChecked(readerColorFilter?.isGrayscale == true, false)
		viewBinding.switchBook.setChecked(readerColorFilter?.isBookBackground == true, false)
		viewBinding.imageViewAfter.colorFilter = readerColorFilter?.toColorFilter()
		updateAfterImagePreview(readerColorFilter?.sharpening ?: 0f, readerColorFilter?.denoising ?: 0f)
	}

	private fun updateAfterImagePreview(sharpening: Float, denoising: Float) {
		val request = ImageRequest.Builder(this@ColorFilterConfigActivity)
			.data(viewModel.preview)
			.target(
				onStart = { viewBinding.imageViewAfter.setImageDrawable(it?.asDrawable(resources)) },
				onSuccess = { viewBinding.imageViewAfter.setImageDrawable(it.asDrawable(resources)) },
				onError = { viewBinding.imageViewAfter.setImageDrawable(it?.asDrawable(resources)) }
			)
			.memoryCacheKey("preview_f_s${sharpening}_d${denoising}") // Unique cache key for preview
			.apply {
				if (sharpening > 0f || denoising > 0f) {
					transformations(org.koitharu.kotatsu.core.ui.image.ImageFiltersTransformation(sharpening, denoising))
				}
			}
			.build()
		coil.enqueue(request)
	}

	private fun loadPreview(page: MangaPage) = with(viewBinding.imageViewBefore) {
		addImageRequestListener(
			ImageRequestIndicatorListener(
				listOf(
					viewBinding.progressBefore,
					viewBinding.progressAfter,
				),
			),
		)
		addImageRequestListener(ShadowImageListener(viewBinding.imageViewAfter))
		setImageAsync(page)
	}

	private fun onLoadingChanged(isLoading: Boolean) {
		viewBinding.sliderContrast.isEnabled = !isLoading
		viewBinding.sliderBrightness.isEnabled = !isLoading
		viewBinding.sliderSharpening?.isEnabled = !isLoading
		viewBinding.sliderDenoising?.isEnabled = !isLoading
		viewBinding.switchInvert.isEnabled = !isLoading
		viewBinding.switchGrayscale.isEnabled = !isLoading
		viewBinding.buttonDone.isEnabled = !isLoading
	}

	private class PercentLabelFormatter(resources: Resources) : LabelFormatter {

		private val pattern = resources.getString(R.string.percent_string_pattern)

		override fun getFormattedValue(value: Float): String {
			val percent = ((value + 1f) * 100).format(0)
			return pattern.format(percent)
		}
	}

	private class ShadowImageListener(
		private val imageView: ImageView
	) : ImageRequest.Listener {

		override fun onError(request: ImageRequest, result: ErrorResult) {
			super.onError(request, result)
			imageView.setImageDrawable(result.image?.asDrawable(imageView.resources))
		}

		override fun onStart(request: ImageRequest) {
			super.onStart(request)
			imageView.setImageDrawable(request.placeholder()?.asDrawable(imageView.resources))
		}

		override fun onSuccess(request: ImageRequest, result: SuccessResult) {
			super.onSuccess(request, result)
			imageView.setImageDrawable(result.image.asDrawable(imageView.resources))
		}
	}
}
