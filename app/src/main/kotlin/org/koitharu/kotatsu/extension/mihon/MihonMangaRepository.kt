package org.koitharu.kotatsu.extension.mihon

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.extension.mihon.model.MihonDataMapper
import org.koitharu.kotatsu.extension.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder

class MihonMangaRepository(
    override val source: MihonMangaSource,
    private val cache: MemoryContentCache,
) : MangaRepository {

    private val catalogueSource = source.catalogueSource

    override val sortOrders: Set<SortOrder> = buildSet {
        add(SortOrder.POPULARITY)
        if (catalogueSource.supportsLatest) {
            add(SortOrder.UPDATED)
        }
    }

    override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

    override val filterCapabilities: MangaListFilterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isSearchWithFiltersSupported = true
    )

    override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = withContext(Dispatchers.IO) {
        val page = (offset / 20) + 1 // Rough estimation for page
        val query = filter?.query ?: ""
        
        val mangasPage = if (query.isNotBlank()) {
            catalogueSource.getSearchManga(page, query, eu.kanade.tachiyomi.source.model.FilterList())
        } else if (order == SortOrder.UPDATED && catalogueSource.supportsLatest) {
            catalogueSource.getLatestUpdates(page)
        } else {
            catalogueSource.getPopularManga(page)
        }

        mangasPage.mangas.map { 
            MihonDataMapper.toKotoManga(it, source)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = withContext(Dispatchers.IO) {
        val sManga = MihonDataMapper.toMihonManga(manga)
        val details = catalogueSource.getMangaDetails(sManga)
        val chapters = catalogueSource.getChapterList(sManga)
        
        MihonDataMapper.toKotoManga(details, source).copy(
            chaptersCount = chapters.size,
            chapters = chapters.map { MihonDataMapper.toKotoChapter(it, source) }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
        val sChapter = MihonDataMapper.toMihonChapter(chapter)
        val pages = catalogueSource.getPageList(sChapter)
        pages.map { MihonDataMapper.toKotoPage(it) }
    }

    override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.IO) {
        if (catalogueSource is HttpSource) {
            val sPage = eu.kanade.tachiyomi.source.model.Page(page.id.toInt(), "", page.url)
            catalogueSource.getImageUrl(sPage)
        } else {
            page.url
        }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        // Simple implementation for now
        return MangaListFilterOptions()
    }

    override suspend fun getRelated(seed: Manga): List<Manga> = emptyList()
}
