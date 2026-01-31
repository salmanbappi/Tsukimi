package org.koitharu.kotatsu.details.ui.pager

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import okio.FileNotFoundException
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.LocaleStringComparator
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.combine
import org.koitharu.kotatsu.core.util.ext.isEmpty
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.ui.DetailsActivity
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.local.ui.LocalChaptersRemoveService
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

import androidx.work.WorkManager

abstract class ChaptersPagesViewModel(
	@JvmField protected val settings: AppSettings,
	@JvmField protected open val interactor: DetailsInteractor,
	private val bookmarksRepository: BookmarksRepository,
	private val historyRepository: HistoryRepository,
	private val downloadScheduler: DownloadWorker.Scheduler,
	private val deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
	private val localStorageChanges: SharedFlow<LocalManga?>,
	private val workManager: WorkManager,
) : BaseViewModel() {

	val mangaDetails = MutableStateFlow<MangaDetails?>(null)
	val readingState = MutableStateFlow<ReaderState?>(null)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onDownloadStarted = MutableEventFlow<Unit>()
	val onMangaRemoved = MutableEventFlow<Manga>()

	private val chaptersQuery = MutableStateFlow("")
	val selectedBranch = MutableStateFlow<String?>(null)

	val manga = mangaDetails.map { x -> x?.toManga() }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val coverUrl = mangaDetails.map { x -> x?.coverUrl }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val isChaptersReversed = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_REVERSE_CHAPTERS,
		valueProducer = { isChaptersReverse },
	)

	val isChaptersInGridView = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_VIEW_CHAPTERS,
		valueProducer = { isChaptersGridView },
	)

	val isDownloadedOnly = MutableStateFlow(false)
	private val deletionConfirmation = MutableStateFlow(emptySet<Long>())
	private val deletingChapters = MutableStateFlow(emptySet<Long>())
	private val upscaledChapters = MutableStateFlow(emptySet<Long>())

	private val downloadingChapters = combine(
		downloadScheduler.observeWorks(),
		manga,
	) { works, manga ->
		if (manga == null) return@combine emptyMap<Long, Float>()
		val mangaWorks = works.filter {
			it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED
		}.filter {
			val data = it.progress.takeUnless { p -> p.isEmpty } ?: it.outputData
			org.koitharu.kotatsu.download.domain.DownloadState.getMangaId(data) == manga.id
		}
		if (mangaWorks.isEmpty()) return@combine emptyMap<Long, Float>()

		val progressMap = mutableMapOf<Long, Float>()
		for (work in mangaWorks) {
			val data = work.progress.takeUnless { p -> p.isEmpty } ?: work.outputData
			val task = downloadScheduler.getTask(work.id) ?: continue
			val chapterIds = task.chaptersIds ?: manga.chapters?.map { it.id }?.toLongArray() ?: continue
			
			val totalProgress = org.koitharu.kotatsu.download.domain.DownloadState.getProgress(data).toFloat()
			val maxProgress = org.koitharu.kotatsu.download.domain.DownloadState.getMax(data).toFloat()
			val currentPercent = if (maxProgress > 0) totalProgress / maxProgress else 0f
			
			for (id in chapterIds) {
				progressMap[id] = currentPercent
			}
		}
		progressMap
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyMap())

	val newChaptersCount = mangaDetails.flatMapLatest { d ->
		if (d?.isLocal == false) {
			interactor.observeNewChapters(d.id)
		} else {
			flowOf(0)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

	val emptyReason: StateFlow<EmptyMangaReason?> = combine(
		mangaDetails,
		isLoading,
		onError.onStart { emit(null) },
	) { details, loading, error ->
		when {
			details == null || loading -> null
			details.chapters.isNotEmpty() -> null
			details.toManga().state == MangaState.RESTRICTED -> EmptyMangaReason.RESTRICTED
			error != null -> EmptyMangaReason.LOADING_ERROR
			else -> EmptyMangaReason.NO_CHAPTERS
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(), null)

	val bookmarks = mangaDetails.flatMapLatest {
		if (it != null) {
			bookmarksRepository.observeBookmarks(it.toManga()).withErrorHandling()
		} else {
			flowOf(emptyList())
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	val mangaHistory = manga.flatMapLatest {
		if (it != null) {
			historyRepository.observeOne(it.id).withErrorHandling()
		} else {
			flowOf(null)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, null)

	val readChapterEntities = manga.flatMapLatest {
		if (it != null) {
			interactor.observeReadChaptersEntities(it.id).withErrorHandling()
		} else {
			flowOf(emptyList())
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	private val mappedChapters = combine(
		mangaDetails,
		readingState.map { it?.chapterId ?: 0L }.distinctUntilChanged(),
		mangaHistory.map { it?.maxPercent ?: 0f }.distinctUntilChanged(),
		readChapterEntities,
		selectedBranch,
		newChaptersCount,
		bookmarks,
		isChaptersInGridView,
		isDownloadedOnly,
		deletionConfirmation,
		downloadingChapters,
		deletingChapters,
		upscaledChapters,
	) { args: Array<Any?> ->
		val details = args[0] as? MangaDetails
		val currentChapterId = args[1] as Long
		val maxPercent = args[2] as Float
		@Suppress("UNCHECKED_CAST")
		val readEntities = args[3] as List<org.koitharu.kotatsu.core.db.entity.ReadChapterEntity>
		val branch = args[4] as? String
		val news = args[5] as Int
		@Suppress("UNCHECKED_CAST")
		val bookmarked = args[6] as List<Bookmark>
		val grid = args[7] as Boolean
		val downloadedOnly = args[8] as Boolean
		@Suppress("UNCHECKED_CAST")
		val deletionConfirm = args[9] as Set<Long>
		@Suppress("UNCHECKED_CAST")
		val downloading = args[10] as Map<Long, Float>
		@Suppress("UNCHECKED_CAST")
		val deleting = args[11] as Set<Long>
		@Suppress("UNCHECKED_CAST")
		val upscaled = args[12] as Set<Long>

		details?.mapChapters(
			currentChapterId = currentChapterId,
			maxPercent = maxPercent,
			readChapters = readEntities,
			newCount = news,
			branch = branch,
			bookmarks = bookmarked,
			isGrid = grid,
			isDownloadedOnly = downloadedOnly,
			deletionConfirmation = deletionConfirm,
			downloadingChapters = downloading,
			deletingChapters = deleting,
			upscaledChapters = upscaled,
		).orEmpty()
	}

	val chapters = combine(
		mappedChapters,
		isChaptersReversed,
		chaptersQuery,
	) { list, reversed, query ->
		(if (reversed) list.asReversed() else list).filterSearch(query)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val quickFilter = combine(
		mangaDetails,
		selectedBranch,
	) { details, branch ->
		val branches = details?.chapters?.toList()?.sortedWithSafe(
			compareBy(LocaleStringComparator()) { it.first },
		).orEmpty()
		if (branches.size > 1) {
			branches.map {
				val option = ListFilterOption.Branch(titleText = it.first, chaptersCount = it.second.size)
				option.toChipModel(isChecked = it.first == branch)
			}
		} else {
			emptyList()
		}
	}

	init {
		launchJob(Dispatchers.Default) {
			localStorageChanges
				.collect { 
					onDownloadComplete(it)
					refreshUpscaledChapters(it)
				}
		}
		launchJob(Dispatchers.Default) {
			manga.collect { 
				// Need to find LocalManga for current manga
				val local = mangaDetails.value?.local
				refreshUpscaledChapters(local)
			}
		}
	}

	private fun refreshUpscaledChapters(localManga: LocalManga?) {
		if (localManga == null) {
			upscaledChapters.value = emptySet()
			return
		}
		val upscaled = mutableSetOf<Long>()
		localManga.manga.chapters?.forEach { chapter ->
			val uri = android.net.Uri.parse(chapter.url)
			if (uri.scheme == "file" || uri.scheme == "zip") {
				val path = if (uri.scheme == "zip") uri.schemeSpecificPart.substringBefore("!") else uri.path
				if (path != null) {
					val file = java.io.File(path)
					val marker = if (file.isDirectory) {
						java.io.File(file, ".upscaled")
					} else {
						java.io.File(file.parentFile, "${file.name}.upscaled")
					}
					if (marker.exists()) {
						upscaled.add(chapter.id)
					}
				}
			}
		}
		upscaledChapters.value = upscaled
	}

	fun setChaptersReversed(newValue: Boolean) {
		settings.isChaptersReverse = newValue
	}

	fun setChaptersInGridView(newValue: Boolean) {
		settings.isChaptersGridView = newValue
	}

	fun setSelectedBranch(branch: String?) {
		selectedBranch.value = branch
	}

	fun performChapterSearch(query: String?) {
		chaptersQuery.value = query?.trim().orEmpty()
	}

	fun getMangaOrNull(): Manga? = mangaDetails.value?.toManga()

	fun requireManga() = mangaDetails.requireValue().toManga()

	fun markChapterAsCurrent(chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.requireValue()
			val chapters = checkNotNull(manga.chapters[selectedBranch.value])
			val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
			check(chapterIndex in chapters.indices) { "Chapter not found" }
			val percent = chapterIndex / chapters.size.toFloat()
			historyRepository.addOrUpdate(
				manga = manga.toManga(),
				chapterId = chapterId,
				page = 0,
				scroll = 0,
				percent = percent,
				force = true,
			)
		}
	}

	fun markAsReadUpTo(chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val details = mangaDetails.value ?: return@launchJob
			val branch = selectedBranch.value
			val allChapters = details.chapters[branch] ?: return@launchJob
			val index = allChapters.indexOfFirst { it.id == chapterId }
			if (index == -1) return@launchJob

			val chaptersToMark = allChapters.subList(0, index + 1)
			historyRepository.markChaptersAsRead(details.id, chaptersToMark.map { it.id })
		}
	}

	fun markChaptersAsRead(chaptersIds: List<Long>) {
		launchJob(Dispatchers.Default) {
			val details = mangaDetails.value ?: return@launchJob
			historyRepository.markChaptersAsRead(details.id, chaptersIds)
		}
	}

	fun toggleDeletionConfirmation(chapterId: Long) {
		deletionConfirmation.update {
			if (it.contains(chapterId)) it - chapterId else setOf(chapterId)
		}
	}

	fun cancelDownload(chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val works = downloadScheduler.observeWorks().first()
			for (work in works) {
				if (work.state == androidx.work.WorkInfo.State.RUNNING || work.state == androidx.work.WorkInfo.State.ENQUEUED) {
					val task = downloadScheduler.getTask(work.id) ?: continue
					if (task.chaptersIds?.contains(chapterId) == true) {
						downloadScheduler.cancel(work.id)
					}
				}
			}
		}
	}

	fun download(chaptersIds: Set<Long>?, allowMeteredNetwork: Boolean) {
		launchJob(Dispatchers.Default) {
			val manga = requireManga()
			val task = DownloadTask(
				mangaId = manga.id,
				isPaused = false,
				isSilent = false,
				chaptersIds = chaptersIds?.toLongArray(),
				destination = null,
				format = null,
				allowMeteredNetwork = allowMeteredNetwork,
			)
			downloadScheduler.schedule(setOf(manga to task))
		}
	}

	fun deleteChapter(context: android.content.Context, chapterId: Long) {
		deletionConfirmation.update { it - chapterId }
		deletingChapters.update { it + chapterId }
		deleteChapters(context, setOf(chapterId))
	}

	fun deleteChapters(context: android.content.Context, chaptersIds: Set<Long>) {
		launchJob(Dispatchers.Default) {
			LocalChaptersRemoveService.start(context, requireManga(), chaptersIds)
		}
	}

	fun deleteLocal() {
		val m = mangaDetails.value?.local?.manga
		if (m == null) {
			errorEvent.call(FileNotFoundException())
			return
		}
		launchLoadingJob(Dispatchers.Default) {
			deleteLocalMangaUseCase(m)
			onMangaRemoved.call(m)
		}
	}

	private fun List<ChapterListItem>.filterSearch(query: String): List<ChapterListItem> {
		if (query.isEmpty() || this.isEmpty()) {
			return this
		}
		return filter { it.contains(query) }
	}

	private suspend fun onDownloadComplete(downloadedManga: LocalManga?) {
		mangaDetails.update { details ->
			if (downloadedManga != null) {
				if (details?.id == downloadedManga.manga.id) {
					interactor.updateLocal(details, downloadedManga)
				} else {
					details
				}
			} else {
				// We don't know which one was deleted, so we must refresh our state
				// from the database/interactor to see if we are still 'downloaded'
				details
			}
		}
		// Clear deleting state for chapters that are no longer local or have been updated
		
		if (downloadedManga == null) {
			// If null, we might need to re-verify our local state
			manga.value?.let { m ->
				launchJob(Dispatchers.Default) {
					val saved = interactor.findSavedManga(m, withDetails = false)
					mangaDetails.update { details ->
						details?.copy(localManga = saved)
					}
				}
			}
			deletingChapters.value = emptySet()
			return
		}
		
		if (downloadedManga.manga.id != manga.value?.id) return
		
		val currentDeleting = deletingChapters.value
		if (currentDeleting.isNotEmpty()) {
			val remainingChapters = downloadedManga.manga.chapters?.map { it.id }?.toSet().orEmpty()
			val deleted = currentDeleting.filter { it !in remainingChapters }
			if (deleted.isNotEmpty()) {
				deletingChapters.update { it - deleted.toSet() }
			}
		}
	}

	class ActivityVMLazy(
		private val fragment: Fragment,
	) : Lazy<ChaptersPagesViewModel> {
		private var cached: ChaptersPagesViewModel? = null

		override val value: ChaptersPagesViewModel
			get() {
				val viewModel = cached
				return if (viewModel == null) {
					val activity = fragment.requireActivity()
					val vmClass = getViewModelClass(activity)
					ViewModelProvider.create(
						store = activity.viewModelStore,
						factory = activity.defaultViewModelProviderFactory,
						extras = activity.defaultViewModelCreationExtras,
					)[vmClass].also { cached = it }
				} else {
					viewModel
				}
			}

		override fun isInitialized(): Boolean = cached != null

		private fun getViewModelClass(activity: Activity) = when (activity) {
			is ReaderActivity -> ReaderViewModel::class.java
			is DetailsActivity -> DetailsViewModel::class.java
			else -> error("Wrong activity ${activity.javaClass.simpleName} for ${ChaptersPagesViewModel::class.java.simpleName}")
		}
	}
}
