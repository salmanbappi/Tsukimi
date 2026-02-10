package org.koitharu.kotatsu.reader.ui.pager.vm

import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.core.net.toFile
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.throttle
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.ai.AiFeatureManager

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay

class PageViewModel(
	private val loader: PageLoader,
	val settingsProducer: ReaderSettings.Producer,
	private val networkState: NetworkState,
	private val exceptionResolver: ExceptionResolver,
	private val isWebtoon: Boolean,
	private val aiFeatureManager: AiFeatureManager,
) : DefaultOnImageEventListener {

	companion object {
		// Global limit: only upscale 1 image at a time across the entire app
		private val upscaleSemaphore = Semaphore(1)
	}

	private val scope = loader.loaderScope + Dispatchers.Main.immediate
	private var job: Job? = null
	private var upscaleJob: Job? = null
	private var cachedBounds: Rect? = null
	private var boundPage: MangaPage? = null

	val state = MutableStateFlow<PageState>(PageState.Empty)
	val hapticEvent = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.BUFFERED)

	init {
		settingsProducer
			.onEach { settings ->
				if (settings.isAiUpscaleEnabled && loader.isUpscaleReady()) {
					val currentState = state.value
					val uri = when (currentState) {
						is PageState.Shown -> if (!currentState.isUpscaled) (currentState.source as? ImageSource.Uri)?.uri else null
						is PageState.Loaded -> if (!currentState.isUpscaled) (currentState.source as? ImageSource.Uri)?.uri else null
						else -> null
					}
					if (uri != null) {
						startUpscaling(uri)
					}
				}
			}
			.launchIn(scope)
	}

	fun isLoading() = job?.isActive == true

	fun onBind(page: MangaPage) {
		boundPage = page
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			doLoad(page, force = false, forceSharpen = false)
		}
	}

	fun retry(page: MangaPage, isFromUser: Boolean, forceSharpen: Boolean = false) {
		val prevJob = job
		job = scope.launch {
			prevJob?.cancelAndJoin()
			val e = (state.value as? PageState.Error)?.error
			if (e != null && ExceptionResolver.canResolve(e)) {
				if (isFromUser) {
					exceptionResolver.resolve(e)
				}
			}
			withContext(Dispatchers.Default) {
				doLoad(page, force = isFromUser, forceSharpen = forceSharpen)
			}
		}
	}

	fun showErrorDetails(url: String?) {
		val e = (state.value as? PageState.Error)?.error ?: return
		exceptionResolver.showErrorDetails(e, url)
	}

	fun onRecycle() {
		state.value = PageState.Empty
		cachedBounds = null
		boundPage = null
		job?.cancel()
		upscaleJob?.cancel()
	}

	override fun onImageLoaded() {
		state.update { currentState ->
			if (currentState is PageState.Loaded) {
				PageState.Shown(currentState.source, currentState.isConverted, currentState.isUpscaled)
			} else {
				currentState
			}
		}
		// If just shown and upscaling is enabled, trigger it
		val uri = (state.value as? PageState.Shown)?.let { 
			if (!it.isUpscaled) (it.source as? ImageSource.Uri)?.uri else null 
		}
		if (uri != null && settingsProducer.value.isAiUpscaleEnabled) {
			startUpscaling(uri)
		}
	}

	override fun onImageLoadError(e: Throwable) {
		e.printStackTraceDebug()

		state.update { currentState ->
			if (currentState is PageState.Loaded) {
				val uri = (currentState.source as? ImageSource.Uri)?.uri
				if (!currentState.isConverted && uri != null && e is IOException) {
					tryConvert(uri, e)
					PageState.Converting()
				} else {
					PageState.Error(e)
				}
			} else {
				currentState
			}
		}
	}

	private fun tryConvert(uri: Uri, e: Exception) {
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.join()
			state.value = PageState.Converting()
			try {
				val newUri = loader.convertBimap(uri)
				cachedBounds = if (settingsProducer.value.isPagesCropEnabled(isWebtoon)) {
					loader.getTrimmedBounds(newUri)
				} else {
					null
				}
				state.value = PageState.Loaded(newUri.toImageSource(cachedBounds), isConverted = true)
			} catch (ce: CancellationException) {
				throw ce
			} catch (e2: Throwable) {
				e2.printStackTrace()
				e.addSuppressed(e2)
				state.value = PageState.Error(e)
			}
		}
	}

	@WorkerThread
	private suspend fun doLoad(data: MangaPage, force: Boolean, forceSharpen: Boolean) = coroutineScope {
		state.value = PageState.Loading(null, -1)
		val previewJob = launch {
			val preview = loader.loadPreview(data) ?: return@launch
			state.update {
				if (it is PageState.Loading) it.copy(preview = preview) else it
			}
		}
		try {
			val task = loader.loadPageAsync(data, force)
			val progressObserver = observeProgress(this, task.progressAsFlow())
			var uri = task.await()
			progressObserver.cancelAndJoin()
			previewJob.cancel()
			
			val sharpening = settingsProducer.value.sharpening
			val denoising = settingsProducer.value.denoising
			if (sharpening > 0f || denoising > 0f) {
				state.value = PageState.Converting()
				uri = loader.applyImageFilters(uri, sharpening, denoising)
			}

			cachedBounds = if (settingsProducer.value.isPagesCropEnabled(isWebtoon)) {
				loader.getTrimmedBounds(uri)
			} else {
				null
			}
			state.value = PageState.Loaded(uri.toImageSource(cachedBounds), isConverted = false)
			
			// Trigger Haptic Analysis
			launch(Dispatchers.Default) {
				try {
					if (uri.scheme == "file") {
						val bitmap = org.koitharu.kotatsu.core.image.BitmapDecoderCompat.decode(uri.toFile())
						if (bitmap != null) {
							val intensity = aiFeatureManager.analyzeForHaptics(bitmap)
							if (intensity > 0) {
								hapticEvent.send(intensity)
							}
							bitmap.recycle()
						}
					}
				} catch (e: Exception) {
					// Ignore analysis errors
				}
			}

			// startUpscaling(uri) // Don't start automatically here to save memory
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			state.value = PageState.Error(e)
			if (e is IOException && !networkState.value) {
				networkState.awaitForConnection()
				retry(data, isFromUser = false)
			}
		}
	}

	private fun startUpscaling(originalUri: Uri) {
		if (upscaleJob?.isActive == true) return
		upscaleJob = scope.launch(Dispatchers.Default) {
			upscaleSemaphore.withPermit {
				try {
					android.util.Log.d("PageViewModel", "Starting AI Upscale for: $originalUri")
					// Wait a bit to ensure UI thread is free and user isn't scrolling rapidly
					delay(500) 
					val upscaledUri = loader.upscalePage(originalUri)
					if (upscaledUri != originalUri) {
						android.util.Log.d("PageViewModel", "AI Upscale success: $upscaledUri")
						withContext(Dispatchers.Main) {
							state.value = PageState.Loaded(upscaledUri.toImageSource(cachedBounds), isConverted = false, isUpscaled = true)
						}
					} else {
						android.util.Log.d("PageViewModel", "AI Upscale skipped or failed (same URI returned)")
					}
				} catch (e: Throwable) {
					android.util.Log.e("PageViewModel", "AI Upscale failed", e)
					e.printStackTraceDebug()
				}
			}
		}
	}

	private fun observeProgress(scope: CoroutineScope, progress: Flow<Float>) = progress
		.throttle(250)
		.onEach {
			val progressValue = (100 * it).toInt()
			state.update { currentState ->
				if (currentState is PageState.Loading) {
					currentState.copy(progress = progressValue)
				} else {
					currentState
				}
			}
		}.launchIn(scope)

	private fun Uri.toImageSource(bounds: Rect?): ImageSource {
		val source = ImageSource.uri(this)
		return if (bounds != null) {
			source.region(bounds)
		} else {
			source
		}
	}
}
