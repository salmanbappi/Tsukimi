package org.koitharu.kotatsu.reader.ui

import android.app.assist.AssistContent
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.drawToBitmap
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.DialogErrorObserver
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.BaseFullscreenActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.dialog.setCheckbox
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.core.util.IdlingDetector
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.core.util.ext.hasGlobalPoint
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.postDelayed
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.core.util.ext.zipWithPrevious
import org.koitharu.kotatsu.databinding.ActivityReaderBinding
import org.koitharu.kotatsu.details.ui.pager.pages.PagesSavedObserver
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.reader.domain.TapGridArea
import org.koitharu.kotatsu.reader.ui.ai.AiFeatureManager
import org.koitharu.kotatsu.reader.ui.ai.AiTranslationOverlayView
import org.koitharu.kotatsu.reader.ui.config.ReaderConfigSheet
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.reader.ui.tapgrid.TapGridDispatcher
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class ReaderActivity :
    BaseFullscreenActivity<ActivityReaderBinding>(),
    TapGridDispatcher.OnGridTouchListener,
    ReaderConfigSheet.Callback,
    ReaderControlDelegate.OnInteractionListener,
    ReaderNavigationCallback,
    IdlingDetector.Callback,
    ZoomControl.ZoomControlListener,
    View.OnClickListener,
    ScrollTimerControlView.OnVisibilityChangeListener {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var tapGridSettings: TapGridSettings

    @Inject
    lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

    @Inject
    lateinit var scrollTimerFactory: ScrollTimer.Factory

    @Inject
    lateinit var aiFeatureManager: AiFeatureManager

    @Inject
    lateinit var screenOrientationHelper: ScreenOrientationHelper

    private val idlingDetector = IdlingDetector(TimeUnit.SECONDS.toMillis(10), this)
    private val ocrMutex = Mutex()

    private val viewModel: ReaderViewModel by viewModels()

    override val readerMode: ReaderMode?
        get() = readerManager.currentMode

    private lateinit var scrollTimer: ScrollTimer
    private lateinit var pageSaveHelper: PageSaveHelper
    private lateinit var touchHelper: TapGridDispatcher
    private lateinit var controlDelegate: ReaderControlDelegate
    private var gestureInsets: Insets = Insets.NONE
    private lateinit var readerManager: ReaderManager
    private val hideUiRunnable = Runnable { setUiIsVisible(false) }

    private var isFoldUnfolded: Boolean = false
    private var autoTranslateJob: Job? = null
    private var ocrBuffer: Bitmap? = null

    companion object {
        private const val TOAST_DURATION = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityReaderBinding.inflate(layoutInflater))
        readerManager = ReaderManager(supportFragmentManager, viewBinding.container, settings)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        touchHelper = TapGridDispatcher(viewBinding.root, this)
        scrollTimer = scrollTimerFactory.create(resources, this, this)
        pageSaveHelper = pageSaveHelperFactory.create(this)
        controlDelegate = ReaderControlDelegate(resources, settings, tapGridSettings, this)
        		viewBinding.zoomControl.listener = this
        		viewBinding.actionsView.listener = this
        		viewBinding.buttonTimer?.setOnClickListener(this)
        		viewBinding.buttonAiTranslateFab?.setOnClickListener { performAiTranslation() }

        		viewBinding.sliderVertical?.setLabelFormatter(PageLabelFormatter())
        		viewBinding.sliderVertical?.addOnChangeListener { slider, value, fromUser ->
        			if (fromUser) {
        				switchPageTo(value.toInt())
        			}
        		}
        		
        		viewBinding.buttonPrevVertical?.setOnClickListener { switchChapterBy(-1) }
        		viewBinding.buttonNextVertical?.setOnClickListener { switchChapterBy(1) }

        		updateAiTranslateFabVisibility()
        		idlingDetector.bindToLifecycle(this)
		screenOrientationHelper.applySettings()
		viewModel.isBookmarkAdded.observe(this) { viewBinding.actionsView.isBookmarkAdded = it }
        scrollTimer.isActive.observe(this) {
            updateScrollTimerButton()
            viewBinding.actionsView.setTimerActive(it)
        }
        viewBinding.timerControl.onVisibilityChangeListener = this
        viewBinding.timerControl.attach(scrollTimer, this)
        if (resources.getBoolean(R.bool.is_tablet)) {
            viewBinding.timerControl.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                topMargin = marginEnd + getThemeDimensionPixelOffset(appcompatR.attr.actionBarSize)
            }
        }

        viewModel.onLoadingError.observeEvent(
            this,
            DialogErrorObserver(
                host = viewBinding.container,
                fragment = null,
                resolver = exceptionResolver,
                onResolved = Consumer { isResolved ->
                    if (isResolved) {
                        viewModel.reload()
                    } else if (viewModel.content.value.pages.isEmpty()) {
                        dispatchNavigateUp()
                    }
                },
            ),
        )
        viewModel.onError.observeEvent(
            this,
            SnackbarErrorObserver(
                host = viewBinding.container,
                fragment = null,
                resolver = exceptionResolver,
                onResolved = null,
            ),
        )
        viewModel.readerMode.observe(this, Lifecycle.State.STARTED, this::onInitReader)
        viewModel.onPageSaved.observeEvent(this, PagesSavedObserver(viewBinding.container))
        viewModel.uiState.zipWithPrevious().observe(this, this::onUiStateChanged)
        combine(
            viewModel.isLoading,
            viewModel.content.map { it.pages.isNotEmpty() }.distinctUntilChanged(),
            ::Pair,
        ).flowOn(Dispatchers.Default)
            .observe(this, this::onLoadingStateChanged)
        viewModel.isKeepScreenOnEnabled.observe(this, this::setKeepScreenOn)
        viewModel.isInfoBarTransparent.observe(this) { viewBinding.infoBar.drawBackground = !it }
        viewModel.isInfoBarEnabled.observe(this, ::onReaderBarChanged)
        viewModel.isBookmarkAdded.observe(this, MenuInvalidator(this))
        viewModel.onAskNsfwIncognito.observeEvent(this) { askForIncognitoMode() }
        viewModel.onShowToast.observeEvent(this) { msgId ->
            Snackbar.make(viewBinding.container, msgId, Snackbar.LENGTH_SHORT)
                .setAnchorView(viewBinding.toolbarDocked)
                .show()
        }
        viewModel.readerSettingsProducer.observe(this) {
            viewBinding.infoBar.applyColorScheme(isBlackOnWhite = it.background.isLight(this))
        }
        viewModel.isZoomControlsEnabled.observe(this) {
            viewBinding.zoomControl.isVisible = it
        }
        addMenuProvider(ReaderMenuProvider(viewModel, this))

        observeWindowLayout()
        applyDoubleModeAuto()
    }

    override fun getParentActivityIntent(): Intent? {
        val manga = viewModel.getMangaOrNull() ?: return null
        return AppRouter.detailsIntent(this, manga)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!viewBinding.timerControl.isVisible) {
            scrollTimer.onUserInteraction()
        }
        idlingDetector.onUserInteraction()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
    }

    override fun isNsfwContent(): Flow<Boolean> = viewModel.isMangaNsfw

    override fun onIdle() {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        viewModel.onIdle()
    }

    override fun onVisibilityChanged(v: View, visibility: Int) {
        updateScrollTimerButton()
    }

    override fun onZoomIn() {
        readerManager.currentReader?.onZoomIn()
    }

    override fun onZoomOut() {
        readerManager.currentReader?.onZoomOut()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.button_timer -> onScrollTimerClick(isLongClick = false)
        }
    }

    private fun onInitReader(mode: ReaderMode?) {
        if (mode == null) {
            return
        }
        if (readerManager.currentMode != mode) {
            readerManager.replace(mode)
        }
        if (viewBinding.appbarTop.isVisible) {
            lifecycle.postDelayed(TimeUnit.SECONDS.toMillis(1), hideUiRunnable)
        }
        viewBinding.timerControl.onReaderModeChanged(mode)
    }

    private fun onLoadingStateChanged(value: Pair<Boolean, Boolean>) {
        val (isLoading, hasPages) = value
        val showLoadingLayout = isLoading && !hasPages
        if (viewBinding.layoutLoading.isVisible != showLoadingLayout) {
            val transition = Fade().addTarget(viewBinding.layoutLoading)
            TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            viewBinding.layoutLoading.isVisible = showLoadingLayout
        }
        if (isLoading && hasPages) {
            viewBinding.toastView.show(R.string.loading_)
        } else {
            viewBinding.toastView.hide()
        }
        invalidateOptionsMenu()
    }

    override fun onGridTouch(area: TapGridArea): Boolean {
        return isReaderResumed() && controlDelegate.onGridTouch(area)
    }

    override fun onGridDoubleTap(area: TapGridArea): Boolean {
        if (!isReaderResumed()) return false
        viewBinding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        return when (area) {
            TapGridArea.TOP_LEFT, TapGridArea.CENTER_LEFT, TapGridArea.BOTTOM_LEFT -> true
            TapGridArea.TOP_RIGHT, TapGridArea.CENTER_RIGHT, TapGridArea.BOTTOM_RIGHT -> true
            else -> false
        }
    }

    override fun onGridLongTouch(area: TapGridArea) {
        if (isReaderResumed()) {
            controlDelegate.onGridLongTouch(area)
        }
    }

    override fun onProcessTouch(rawX: Int, rawY: Int): Boolean {
        return if (
            rawX <= gestureInsets.left ||
            rawY <= gestureInsets.top ||
            rawX >= viewBinding.root.width - gestureInsets.right ||
            rawY >= viewBinding.root.height - gestureInsets.bottom ||
            viewBinding.appbarTop.hasGlobalPoint(rawX, rawY) ||
            viewBinding.toolbarDocked?.hasGlobalPoint(rawX, rawY) == true
        ) {
            false
        } else {
            val touchables = window.peekDecorView()?.touchables
            touchables?.none { it.hasGlobalPoint(rawX, rawY) } != false
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchHelper.dispatchTouchEvent(ev)
        if (!viewBinding.timerControl.hasGlobalPoint(ev.rawX.toInt(), ev.rawY.toInt())) {
            scrollTimer.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun onChapterSelected(chapter: MangaChapter): Boolean {
        viewModel.switchChapter(chapter.id, 0)
        return true
    }

    override fun onPageSelected(page: ReaderPage): Boolean {
        lifecycleScope.launch(Dispatchers.Default) {
            val pages = viewModel.content.value.pages
            val index = pages.indexOfFirst { it.chapterId == page.chapterId && it.id == page.id }
            if (index != -1) {
                withContext(Dispatchers.Main) {
                    readerManager.currentReader?.switchPageTo(index, true)
                }
            } else {
                viewModel.switchChapter(page.chapterId, page.index)
            }
        }
        return true
    }

    override fun onReaderModeChanged(mode: ReaderMode) {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        viewModel.switchMode(mode)
        viewBinding.timerControl.onReaderModeChanged(mode)
    }

    	override fun onDoubleModeChanged(isEnabled: Boolean) {
    		applyDoubleModeAuto(isEnabled)
    	}
    
    	override fun onAiTranslationChanged(isEnabled: Boolean) {
    		updateAiTranslateFabVisibility()
    	}
    
    			private fun updateAiTranslateFabVisibility() {
    
    				val fab = viewBinding.buttonAiTranslateFab ?: return
    
    				val isVisible = settings.isAiTranslationEnabled && !settings.isAiAutoTranslationEnabled
    
    				if (fab.isVisible != isVisible) {
    
    					if (isAnimationsEnabled) {
    
    						TransitionManager.beginDelayedTransition(viewBinding.root, Fade().addTarget(fab))
    
    					}
    
    					fab.isVisible = isVisible
    
    				}
    
    			}
    
    		
    
    			private fun applyDoubleModeAuto(manualEnabled: Boolean? = null) {        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val autoFoldable = settings.isReaderDoubleOnFoldable && isFoldUnfolded
        val manualLandscape = (manualEnabled ?: settings.isReaderDoubleOnLandscape) && isLandscape
        val autoEnabled = autoFoldable || manualLandscape
        readerManager.setDoubleReaderMode(autoEnabled)
    }

    private fun setKeepScreenOn(isKeep: Boolean) {
        if (isKeep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setUiIsVisible(isUiVisible: Boolean) {
        if (viewBinding.appbarTop.isVisible != isUiVisible) {
            if (isAnimationsEnabled) {
                val transition = TransitionSet()
                    .setOrdering(TransitionSet.ORDERING_TOGETHER)
                    .addTransition(Slide(Gravity.TOP).addTarget(viewBinding.appbarTop))
                    .addTransition(Fade().addTarget(viewBinding.infoBar))
                viewBinding.toolbarDocked?.let {
                    transition.addTransition(Slide(Gravity.BOTTOM).addTarget(it))
                }
                TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            }
            val isFullscreen = settings.isReaderFullscreenEnabled
            viewBinding.appbarTop.isVisible = isUiVisible
            viewBinding.toolbarDocked?.isVisible = isUiVisible
            viewBinding.containerSliderVertical?.isVisible = isUiVisible && (viewModel.uiState.value?.isSliderAvailable() == true)
            viewBinding.infoBar.isGone = isUiVisible || (!viewModel.isInfoBarEnabled.value) || (settings.isReaderZenModeEnabled && !isUiVisible)
            viewBinding.infoBar.isTimeVisible = isFullscreen
            updateScrollTimerButton()
            systemUiController.setSystemUiVisible(isUiVisible || !isFullscreen)
            viewBinding.root.requestApplyInsets()
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        viewBinding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = systemBars.top
            rightMargin = systemBars.right
            leftMargin = systemBars.left
        }
        if (viewBinding.toolbarDocked != null) {
            viewBinding.actionsView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom
                rightMargin = systemBars.right
                leftMargin = systemBars.left
            }
        }
        viewBinding.infoBar.updatePadding(
            top = systemBars.top,
        )
        val innerInsets = Insets.of(
            systemBars.left,
            systemBars.top,
            systemBars.right,
            systemBars.bottom,
        )
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), innerInsets)
            .build()
    }

    override fun switchPageBy(delta: Int) {
        if (settings.isReaderHapticsEnabled) {
            viewBinding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        readerManager.currentReader?.switchPageBy(delta)
    }

    override fun switchChapterBy(delta: Int) {
        viewModel.switchChapterBy(delta)
    }

    override fun openMenu() {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        val currentMode = readerManager.currentMode ?: return
        router.showReaderConfigSheet(currentMode)
    }

    override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
        return readerManager.currentReader?.scrollBy(delta, smooth) == true
    }

    override fun toggleUiVisibility() {
        setUiIsVisible(!viewBinding.appbarTop.isVisible)
    }

    override fun isReaderResumed(): Boolean {
        val reader = readerManager.currentReader ?: return false
        return reader.isResumed && supportFragmentManager.fragments.lastOrNull() === reader
    }

    override fun onBookmarkClick() {
        viewModel.toggleBookmark()
    }

	override fun onAiTranslateClick() {
		performAiTranslation()
	}



    private fun autoTranslateCurrentPages() {
        autoTranslateJob?.cancel()
        autoTranslateJob = lifecycleScope.launch(Dispatchers.Main) {
            val holders = readerManager.currentReader?.getCurrentHolders() ?: emptyList()
            val allCached = holders.isNotEmpty() && holders.all { holder ->
                val page = holder.boundData ?: return@all true
                aiFeatureManager.isCached("${page.chapterId}_${page.index}")
            }
            
            if (!allCached) {
                delay(600)
            }
            performAiTranslation()
        }
    }

    private fun performAiTranslation() {
        lifecycleScope.launch(Dispatchers.Default) {
            val holders = withContext(Dispatchers.Main) {
                readerManager.currentReader?.getCurrentHolders()
            } ?: return@launch
            
            for (holder in holders) {
                val overlay = holder.itemView.findViewById<AiTranslationOverlayView>(R.id.translationOverlay) ?: continue
                withContext(Dispatchers.Main) {
                    overlay.clear()
                }
                val ssiv = holder.itemView.findViewById<SubsamplingScaleImageView>(R.id.ssiv) ?: continue
                val page = holder.boundData ?: continue
                val pageKey = "${page.chapterId}_${page.index}"

                // Capture mapping state accurately on Main thread
                val (isReady, scale, vTranslateX, vTranslateY) = withContext(Dispatchers.Main) {
                    val origin = ssiv.viewToSourceCoord(0f, 0f)
                    val tx = if (origin != null) -origin.x * ssiv.scale else 0f
                    val ty = if (origin != null) -origin.y * ssiv.scale else 0f
                    Quadruple(ssiv.isReady, ssiv.scale, tx, ty)
                }

                if (aiFeatureManager.isCached(pageKey)) {
                    val blocks = aiFeatureManager.getFromCache(pageKey) ?: emptyList()
                    withContext(Dispatchers.Main) {
                        overlay.setupWithSSIV(ssiv)
                        overlay.setSettings(settings)
                        overlay.isVisible = true
                        overlay.setTranslatedBlocks(blocks)
                    }
                    continue
                }

                if (!isReady) continue

                withContext(Dispatchers.Main) {
                    viewBinding.toastView.show(R.string.processing_)
                }
                
                ocrMutex.withLock {
                    // Optimized bitmap capture: only capture if necessary and use a smaller bitmap if possible
                    var captureScale = 1f
                    val bitmap = try {
                        withContext(Dispatchers.Main) {
                            val width = ssiv.width
                            val height = ssiv.height
                            if (width <= 0 || height <= 0) return@withContext null
                            
                            // MAINTAIN QUALITY: Use 3600px threshold and ARGB_8888 to ensure good translation results
                            // Higher threshold is critical for Webtoon mode where views can be very tall.
                            captureScale = if (Math.max(width, height) > 3600) 3600f / Math.max(width, height) else 1f
                            val targetW = (width * captureScale).toInt()
                            val targetH = (height * captureScale).toInt()
                            val requiredBytes = targetW * targetH * 4 // ARGB_8888

                            val b = ocrBuffer?.takeIf { 
                                !it.isRecycled && it.allocationByteCount >= requiredBytes 
                            }?.also {
                                it.reconfigure(targetW, targetH, Bitmap.Config.ARGB_8888)
                            } ?: Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also { 
                                ocrBuffer?.recycle()
                                ocrBuffer = it 
                            }
                            
                            val canvas = android.graphics.Canvas(b)
                            canvas.scale(captureScale, captureScale)
                            ssiv.draw(canvas)
                            b
                        }
                    } catch (e: Exception) {
                        null
                    }
                    
                    if (bitmap == null) {
                        withContext(Dispatchers.Main) {
                            viewBinding.toastView.hide()
                        }
                        return@withLock
                    }
                    
                    val blocks = aiFeatureManager.translatePage(
                        pageKey = pageKey,
                        bitmap = bitmap,
                        viewScale = scale,
                        vTranslateX = vTranslateX,
                        vTranslateY = vTranslateY,
                        captureScale = captureScale
                    )
                    
                    withContext(Dispatchers.Main) {
                        overlay.setupWithSSIV(ssiv)
                        overlay.setSettings(settings)
                        overlay.isVisible = true
                        overlay.setTranslatedBlocks(blocks)
                        viewBinding.toastView.hide()
                    }
                }
            }
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    override fun onSavePageClick() {
        viewModel.saveCurrentPage(pageSaveHelper)
    }

    override fun onScrollTimerClick(isLongClick: Boolean) {
        if (isLongClick) {
            scrollTimer.setActive(!scrollTimer.isActive.value)
        } else {
            viewBinding.timerControl.showOrHide()
        }
    }

    override fun toggleScreenOrientation() {
        if (screenOrientationHelper.toggleScreenOrientation()) {
            Snackbar.make(
                viewBinding.container,
                if (screenOrientationHelper.isLocked) {
                    R.string.screen_rotation_locked
                } else {
                    R.string.screen_rotation_unlocked
                },
                Snackbar.LENGTH_SHORT,
            ).setAnchorView(viewBinding.toolbarDocked)
                .show()
        }
    }

    override fun switchPageTo(index: Int) {
        val pages = viewModel.getCurrentChapterPages()
        val page = pages?.getOrNull(index) ?: return
        val chapterId = viewModel.getCurrentState()?.chapterId ?: return
        onPageSelected(ReaderPage(page, index, chapterId))
    }

    private fun onReaderBarChanged(isBarEnabled: Boolean) {
        viewBinding.infoBar.isVisible = isBarEnabled && viewBinding.appbarTop.isGone
    }

    private fun onUiStateChanged(pair: Pair<ReaderUiState?, ReaderUiState?>) {
        val (previous: ReaderUiState?, uiState: ReaderUiState?) = pair
        title = uiState?.mangaName ?: getString(R.string.loading_)
        viewBinding.infoBar.update(uiState)
        if (uiState == null) {
            supportActionBar?.subtitle = null
            viewBinding.sliderVertical?.valueTo = 1f
            viewBinding.sliderVertical?.value = 0f
            viewBinding.sliderVertical?.isEnabled = false
            return
        }

        if (settings.isAiAutoTranslationEnabled && uiState.currentPage != previous?.currentPage) {
            autoTranslateCurrentPages()
        }

        val chapterTitle = uiState.getChapterTitle(resources)
        supportActionBar?.subtitle = when {
            uiState.incognito -> getString(R.string.incognito_mode)
            else -> chapterTitle
        }
        if (
            settings.isReaderChapterToastEnabled &&
            chapterTitle != previous?.getChapterTitle(resources) &&
            chapterTitle.isNotEmpty()
        ) {
            viewBinding.toastView.showTemporary(chapterTitle, TOAST_DURATION)
        }
        if (uiState.isSliderAvailable()) {
            viewBinding.sliderVertical?.valueTo = (uiState.totalPages - 1).toFloat()
            viewBinding.sliderVertical?.value = uiState.currentPage.toFloat()
            viewBinding.textViewCurrentPageVertical?.text = (uiState.currentPage + 1).toString()
            viewBinding.textViewTotalPagesVertical?.text = uiState.totalPages.toString()
        } else {
            viewBinding.sliderVertical?.valueTo = 1f
            viewBinding.sliderVertical?.value = 0f
            viewBinding.textViewCurrentPageVertical?.text = "1"
            viewBinding.textViewTotalPagesVertical?.text = "1"
        }
        viewBinding.sliderVertical?.isEnabled = uiState.isSliderAvailable()
        viewBinding.buttonPrevVertical?.isEnabled = uiState.hasPreviousChapter()
        viewBinding.buttonNextVertical?.isEnabled = uiState.hasNextChapter()
    }

    private fun updateScrollTimerButton() {
        val button = viewBinding.buttonTimer ?: return
        val isButtonVisible = scrollTimer.isActive.value
            && settings.isReaderAutoscrollFabVisible
            && !viewBinding.appbarTop.isVisible
            && !viewBinding.timerControl.isVisible
        if (button.isVisible != isButtonVisible) {
            val transition = Fade().addTarget(button)
            TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            button.isVisible = isButtonVisible
        }
    }

    private fun observeWindowLayout() {
        WindowInfoTracker.getOrCreate(this)
            .windowLayoutInfo(this)
            .onEach { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                val unfolded = when (fold?.state) {
                    FoldingFeature.State.HALF_OPENED, FoldingFeature.State.FLAT -> true
                    else -> false
                }
                if (unfolded != isFoldUnfolded) {
                    isFoldUnfolded = unfolded
                    applyDoubleModeAuto()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun askForIncognitoMode() {
        buildAlertDialog(this, isCentered = true) {
            var dontAskAgain = false
            val listener = DialogInterface.OnClickListener { _, which ->
                if (which == DialogInterface.BUTTON_NEUTRAL) {
                    finishAfterTransition()
                } else {
                    viewModel.setIncognitoMode(which == DialogInterface.BUTTON_POSITIVE, dontAskAgain)
                }
            }
            setCheckbox(R.string.dont_ask_again, dontAskAgain) { _, isChecked ->
                dontAskAgain = isChecked
            }
            setIcon(R.drawable.ic_incognito)
            setTitle(R.string.incognito_mode)
            setMessage(R.string.incognito_mode_hint_nsfw)
            setPositiveButton(R.string.incognito, listener)
            setNegativeButton(R.string.disable, listener)
            setNeutralButton(android.R.string.cancel, listener)
            setOnCancelListener { finishAfterTransition() }
            setCancelable(true)
        }.show()
    }
}
