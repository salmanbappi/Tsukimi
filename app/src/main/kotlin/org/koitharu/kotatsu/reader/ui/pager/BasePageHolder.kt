package org.koitharu.kotatsu.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.LayoutPageInfoBinding
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.ai.AiEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.koitharu.kotatsu.reader.ui.ai.AiFeatureManager
import org.koitharu.kotatsu.reader.ui.ai.AiTranslationOverlayView
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.vm.PageState
import org.koitharu.kotatsu.reader.ui.pager.vm.PageViewModel

abstract class BasePageHolder<B : ViewBinding>(
	protected val binding: B,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
	lifecycleOwner: LifecycleOwner,
	isWebtoon: Boolean,
) : LifecycleAwareViewHolder(binding.root, lifecycleOwner), DefaultOnImageEventListener, ComponentCallbacks2 {

	private val aiFeatureManager: AiFeatureManager by lazy {
		EntryPointAccessors.fromApplication(context, AiEntryPoint::class.java).aiFeatureManager()
	}

	protected val viewModel = PageViewModel(
		loader = loader,
		settingsProducer = readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
		isWebtoon = isWebtoon,
	)
	protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
	protected abstract val ssiv: SubsamplingScaleImageView
	protected open val translationOverlay: AiTranslationOverlayView? = null
	protected open val upscaleBadge: View? = null

	protected val settings: ReaderSettings
		get() = viewModel.settingsProducer.value

	private var lastSharpening = -1f

	val context: Context
		get() = itemView.context

	var boundData: ReaderPage? = null
		private set

	init {
		lifecycleScope.launch(Dispatchers.Main) {
			ssiv.bindToLifecycle(this@BasePageHolder)
			ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
			ssiv.addOnImageEventListener(viewModel)
			ssiv.addOnImageEventListener(this@BasePageHolder)
		}
		val clickListener = View.OnClickListener { v ->
			when (v.id) {
				R.id.button_retry -> viewModel.retry(
					page = boundData?.toMangaPage() ?: return@OnClickListener,
					isFromUser = true,
				)

				R.id.button_error_details -> viewModel.showErrorDetails(boundData?.url)
			}
		}
		bindingInfo.buttonRetry.setOnClickListener(clickListener)
		bindingInfo.buttonErrorDetails.setOnClickListener(clickListener)
		translationOverlay?.setupWithSSIV(ssiv)
	}

	@CallSuper
	protected open fun onConfigChanged(settings: ReaderSettings) {
		settings.applyBackground(itemView)
		val sharpeningChanged = lastSharpening != -1f && lastSharpening != settings.sharpening
		lastSharpening = settings.sharpening
		
		if (settings.applyBitmapConfig(ssiv) || sharpeningChanged) {
			if (sharpeningChanged) {
				boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false, forceSharpen = true) }
			} else {
				reloadImage()
			}
		} else if (viewModel.state.value is PageState.Shown) {
			onReady()
			restoreTranslationIfPossible()
		}
		ssiv.applyDownSampling(isResumed())
	}

	private fun restoreTranslationIfPossible() {
		val page = boundData ?: return
		val pageKey = "${page.chapterId}_${page.index}"
		val cached = aiFeatureManager.getFromCache(pageKey)
		if (settings.isAiTranslationEnabled && cached != null) {
			translationOverlay?.setupWithSSIV(ssiv)
			translationOverlay?.isVisible = true
			translationOverlay?.setTranslatedBlocks(cached)
		} else {
			translationOverlay?.isVisible = false
			translationOverlay?.setTranslatedBlocks(emptyList())
		}
	}

	fun reloadImage() {
		val source = (viewModel.state.value as? PageState.Shown)?.source ?: return
		ssiv.setImage(source)
	}

	fun bind(data: ReaderPage) {
		boundData = data
		restoreTranslationIfPossible()
		viewModel.onBind(data.toMangaPage())
		onBind(data)
	}

	@CallSuper
	protected open fun onBind(data: ReaderPage) = Unit

	override fun onCreate() {
		super.onCreate()
		context.registerComponentCallbacks(this)
		viewModel.state.observe(this, ::onStateChanged)
		viewModel.settingsProducer.observe(this, ::onConfigChanged)
	}

	override fun onResume() {
		super.onResume()
		ssiv.applyDownSampling(isForeground = true)
		if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
			boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
		}
	}

	override fun onPause() {
		super.onPause()
		ssiv.applyDownSampling(isForeground = false)
	}

	override fun onDestroy() {
		context.unregisterComponentCallbacks(this)
		super.onDestroy()
	}

	open fun onAttachedToWindow() = Unit

	open fun onDetachedFromWindow() = Unit

	@CallSuper
	open fun onRecycled() {
		viewModel.onRecycle()
		ssiv.recycle()
		translationOverlay?.isVisible = false
		translationOverlay?.setTranslatedBlocks(emptyList())
	}

	override fun onTrimMemory(level: Int) {
		// TODO
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Deprecated("Deprecated in Java")
	final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_COMPLETE)

	protected open fun onStateChanged(state: PageState) {
		bindingInfo.layoutError.isVisible = state is PageState.Error
		bindingInfo.layoutProgress.isGone = state.isFinalState()
		val progress = (state as? PageState.Loading)?.progress ?: -1
		if (progress in 0..100) {
			bindingInfo.progressBar.isIndeterminate = false
			bindingInfo.progressBar.setProgressCompat(progress, true)
			bindingInfo.textViewStatus.text = context.getString(R.string.percent_string_pattern, progress.toString())
		} else {
			bindingInfo.progressBar.isIndeterminate = true
			bindingInfo.textViewStatus.setText(R.string.loading_)
		}
		when (state) {
			is PageState.Converting -> {
				bindingInfo.textViewStatus.setText(R.string.processing_)
			}

			is PageState.Empty -> Unit

			is PageState.Error -> {
				val e = state.error
				bindingInfo.textViewError.text = e.getDisplayMessage(context.resources)
				bindingInfo.buttonRetry.setText(
					ExceptionResolver.getResolveStringId(e).ifZero { R.string.try_again },
				)
				bindingInfo.buttonErrorDetails.isVisible = e.isSerializable()
				bindingInfo.layoutError.isVisible = true
				bindingInfo.progressBar.hide()
			}

			is PageState.Loaded -> {
				bindingInfo.textViewStatus.setText(R.string.preparing_)
				ssiv.setImage(state.source)
			}

			is PageState.Loading -> {
				if (state.preview != null && ssiv.getState() == null) {
					ssiv.setImage(state.preview)
				}
			}

			is PageState.Shown -> {
				if (state.isUpscaled) {
					val currentScale = ssiv.scale
					val currentCenter = ssiv.getCenter()
					ssiv.setImage(state.source)
					if (currentCenter != null) {
						// UpscaleFactor is 2x, so we need to adjust center coordinates
						// and scale to maintain the same view
						ssiv.setScaleAndCenter(currentScale / 2f, android.graphics.PointF(currentCenter.x * 2f, currentCenter.y * 2f))
					}
				}
				upscaleBadge?.isVisible = state.isUpscaled
				restoreTranslationIfPossible()
			}
		}
	}

	protected fun SubsamplingScaleImageView.applyDownSampling(isForeground: Boolean) {
		downSampling = when {
			isForeground || !settings.isReaderOptimizationEnabled -> 1
			BuildConfig.DEBUG -> 32
			context.isLowRamDevice() -> 8
			else -> 4
		}
	}
}
