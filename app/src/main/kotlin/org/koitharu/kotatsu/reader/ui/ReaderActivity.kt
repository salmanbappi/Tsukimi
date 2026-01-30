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
    lateinit var aiFeatureManager: AiFeatureManager

    private val viewModel: ReaderViewModel by viewModels()
    private val readerManager by lazy { ReaderManager(this, supportFragmentManager, R.id.container) }
    private val controlDelegate by lazy { ReaderControlDelegate(this, this, tapGridSettings) }
    private val idlingDetector by lazy { IdlingDetector(this) }
    private val scrollTimer by lazy { ScrollTimer(lifecycleScope, settings) }
    private val screenOrientationHelper by lazy { ScreenOrientationHelper(this) }
    private val pageSaveHelper by lazy { pageSaveHelperFactory.create(this) }

    private val hideUiRunnable = Runnable { setUiIsVisible(false) }
    private var isFoldUnfolded = false
    private var autoTranslateJob: Job? = null

    companion object {
        private const val TOAST_DURATION = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityReaderBinding.inflate(layoutInflater))
        setSupportActionBar(viewBinding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewBinding.appbarTop.isVisible = false
        viewBinding.actionsView.isVisible = false

        viewModel.isBookmarkAdded.observe(this) { viewBinding.actionsView.isBookmarkAdded = it }
        viewModel.onLoadingError.observeEvent(
            this,
            SnackbarErrorObserver(viewBinding.container) {
                viewModel.retryLoading()
            },
        )
        viewModel.onError.observeEvent(
            this,
            SnackbarErrorObserver(viewBinding.container, null),
        )
        viewModel.readerMode.observe(this, Lifecycle.State.STARTED, this::onInitReader)
        viewModel.onPageSaved.observeEvent(this, PagesSavedObserver(viewBinding.container))
        viewModel.uiState.zipWithPrevious().observe(this, this::onUiStateChanged)
        viewModel.isKeepScreenOnEnabled.observe(this, this::setKeepScreenOn)
        viewModel.isInfoBarTransparent.observe(this) { viewBinding.infoBar.drawBackground = !it }
        viewModel.isInfoBarEnabled.observe(this, ::onReaderBarChanged)
        viewModel.isBookmarkAdded.observe(this, MenuInvalidator(this))
        viewModel.onAskNsfwIncognito.observeEvent(this) { askForIncognitoMode() }
        viewModel.onShowToast.observeEvent(this) { msgId ->
            viewBinding.toastView.show(msgId)
        }

        viewModel.readerSettingsProducer.observe(this) {
            readerManager.onConfigChanged(it)
        }
        viewModel.isZoomControlsEnabled.observe(this) {
            viewBinding.zoomControl.isVisible = it
        }

        viewBinding.actionsView.callback = object : ReaderActionsView.Callback {
            override fun onSliderChanged(value: Int) {
                viewModel.onSliderChanged(value)
                readerManager.currentReader?.switchPageTo(value, false)
            }

            override fun onSliderTrackingStop(value: Int) {
                viewModel.onSliderTrackingStop(value)
                readerManager.currentReader?.switchPageTo(value, true)
            }

            override fun onPrevChapterClick() {
                viewModel.switchChapterBy(-1)
            }

            override fun onNextChapterClick() {
                viewModel.switchChapterBy(1)
            }

            override fun onBookmarkClick() {
                this@ReaderActivity.onBookmarkClick()
            }

            override fun onAiTranslateClick() {
                this@ReaderActivity.onAiTranslateClick()
            }
        }

        viewBinding.zoomControl.listener = this
        viewBinding.buttonTimer?.setOnClickListener(this)
        viewBinding.timerControl.listener = scrollTimer

        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            viewBinding.appbarTop.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
            )
            viewBinding.actionsView.updatePadding(
                left = systemBars.left,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            viewBinding.zoomControl.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = systemBars.bottom + getThemeDimensionPixelOffset(appcompatR.attr.listPreferredItemPaddingRight)
            }
            insets
        }

        WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
            .map { info -> info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() }
            .onEach { feature ->
                isFoldUnfolded = feature == null || feature.state == FoldingFeature.State.FLAT
                applyDoubleModeAuto()
            }
            .flowOn(Dispatchers.Main)
            .launchIn(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        idlingDetector.register(this)
    }

    override fun onPause() {
        super.onPause()
        idlingDetector.unregister(this)
    }

    override fun onProvideAssistContent(outContent: AssistContent?) {
        super.onProvideAssistContent(outContent)
        viewModel.content.value.manga?.let {
            outContent?.webUri = it.url.toUriOrNull()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        idlingDetector.onUserInteraction()
    }

    override fun onIdlingStarted() {
        if (settings.isReaderAutoscrollFabVisible && !viewBinding.appbarTop.isVisible) {
            viewBinding.buttonTimer?.show()
        }
    }

    override fun onIdlingStopped() {
        viewBinding.buttonTimer?.hide()
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
        viewBinding.actionsView.setSliderReversed(mode == ReaderMode.REVERSED)
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
        return controlDelegate.onGridDoubleTap(area)
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

    private fun applyDoubleModeAuto(manualEnabled: Boolean? = null) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
        viewBinding.appbarTop.isVisible = isUiVisible
        viewBinding.actionsView.isVisible = isUiVisible
        if (isUiVisible) {
            lifecycle.removeCallbacks(hideUiRunnable)
        }
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
            viewBinding.actionsView.setSliderValue(0, 1)
            viewBinding.actionsView.isSliderEnabled = false
            return
        }

        // Trigger auto-translate if page changed
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
            viewBinding.actionsView.setSliderValue(
                value = uiState.currentPage,
                max = uiState.totalPages - 1,
            )
        } else {
            viewBinding.actionsView.setSliderValue(0, 1)
        }
        viewBinding.actionsView.isSliderEnabled = uiState.isSliderAvailable()
        viewBinding.actionsView.isNextEnabled = uiState.hasNextChapter()
        viewBinding.actionsView.isPrevEnabled = uiState.hasPreviousChapter()
    }

    private fun autoTranslateCurrentPages() {
        autoTranslateJob?.cancel()
        autoTranslateJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(1000) // Optimal debounce to prevent rate limits during fast scrolling
            performAiTranslation()
        }
    }

    override fun onAiTranslateClick() {
        if (!settings.isAiTranslationEnabled) {
            Snackbar.make(viewBinding.container, "Enable AI Translation in Reader Settings first", Snackbar.LENGTH_SHORT)
                .setAnchorView(viewBinding.toolbarDocked)
                .show()
            return
        }
        performAiTranslation()
    }

    private fun performAiTranslation() {
        lifecycleScope.launch(Dispatchers.Default) {
            val holders = withContext(Dispatchers.Main) {
                readerManager.currentReader?.getCurrentHolders()
            } ?: return@launch
            
            for (holder in holders) {
                val ssiv = holder.itemView.findViewById<SubsamplingScaleImageView>(R.id.ssiv) ?: continue
                val overlay = holder.itemView.findViewById<AiTranslationOverlayView>(R.id.translationOverlay) ?: continue
                val page = holder.boundData ?: continue
                val pageKey = "${page.chapterId}_${page.index}"

                // Check cache first to avoid flickering/toasts
                if (aiFeatureManager.isCached(pageKey)) {
                    val scale = withContext(Dispatchers.Main) { ssiv.scale }
                    val vTranslate = withContext(Dispatchers.Main) { ssiv.vTranslate ?: PointF(0f, 0f) }
                    val blocks = aiFeatureManager.translatePage(pageKey, Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8), scale, vTranslate.x, vTranslate.y)
                    withContext(Dispatchers.Main) {
                        overlay.isVisible = true
                        overlay.setTranslatedBlocks(blocks)
                    }
                    continue
                }

                // Proceed with full translation pipeline
                withContext(Dispatchers.Main) {
                    viewBinding.toastView.show(R.string.processing_)
                }
                
                val scale = withContext(Dispatchers.Main) { ssiv.scale }
                val vTranslate = withContext(Dispatchers.Main) { ssiv.vTranslate ?: PointF(0f, 0f) }
                
                val bitmap = withContext(Dispatchers.Main) {
                    ssiv.drawToBitmap()
                }
                
                val blocks = aiFeatureManager.translatePage(pageKey, bitmap, scale, vTranslate.x, vTranslate.y)
                
                withContext(Dispatchers.Main) {
                    overlay.isVisible = true
                    overlay.setTranslatedBlocks(blocks)
                    viewBinding.toastView.hide()
                }
            }
        }
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

    private fun askForIncognitoMode() {
        buildAlertDialog(this) {
            setTitle(R.string.incognito_mode)
            setMessage(R.string.incognito_mode_hint_nsfw)
            setPositiveButton(R.string.enable) { _, _ ->
                viewModel.setIncognitoMode(true)
            }
            setNegativeButton(R.string.no_thanks, null)
            setCheckbox(R.string.dont_ask_again) { _, isChecked ->
                if (isChecked) {
                    viewModel.disableIncognitoAsk()
                }
            }
        }.show()
    }

    override fun onVisibilityChanged(isVisible: Boolean) {
        updateScrollTimerButton()
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
}