package org.koitharu.kotatsu.backups.domain.mihon

import org.koitharu.kotatsu.backups.data.model.CategoryBackup
import org.koitharu.kotatsu.backups.data.model.FavouriteBackup
import org.koitharu.kotatsu.backups.data.model.HistoryBackup
import org.koitharu.kotatsu.backups.data.model.MangaBackup
import org.koitharu.kotatsu.backups.data.model.ReadChapterBackup
import org.koitharu.kotatsu.backups.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backups.data.model.SourceBackup
import org.koitharu.kotatsu.backups.data.model.mihon.MihonBackup
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.HistoryWithManga
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.util.longHashCode

class MihonBackupMapper(private val backup: MihonBackup) {

    // Common Mihon/Tachiyomi source IDs mapping to Kotatsu names.
    // In Mihon, IDs are long hashes. 
    private val sourceIdMap = mapOf(
        7L to "MANGADEX",
        6903828345115167097L to "MANGADEX", // Mangadex EN
        4714104230103756887L to "MANGAHASU",
        7514120392345167097L to "MANGAPARK",
        -4966601429443657573L to "MANGAKAKALOT",
        -7333621419443657573L to "MANGASEE",
        -1966601429443657573L to "MANGALIFE",
        138345115167097L to "NENTAI",
        -5534211419443657573L to "READMANGA",
        -5433621419443657573L to "MANGALIB",
    )

    private fun mapSource(mihonSourceId: Long): String {
        sourceIdMap[mihonSourceId]?.let { return it }
        val source = backup.backupSources.find { it.sourceId == mihonSourceId }
        val name = source?.name ?: mihonSourceId.toString()
        return name.uppercase().replace(" ", "_")
            .replace("(", "").replace(")", "").replace("-", "_")
    }

    private fun generateMangaId(source: String, url: String): Long {
        return "${source}_${url}".longHashCode()
    }

    fun mapCategories(): List<CategoryBackup> {
        return backup.backupCategories.map {
            CategoryBackup(
                categoryId = it.id?.toInt() ?: it.order?.toInt() ?: 0,
                createdAt = System.currentTimeMillis(),
                sortKey = it.order?.toInt() ?: 0,
                title = it.name
            )
        }
    }

    fun mapMangaAndFavourites(): Pair<List<MangaBackup>, List<FavouriteBackup>> {
        val mangas = mutableListOf<MangaBackup>()
        val favourites = mutableListOf<FavouriteBackup>()

        backup.backupManga.forEach { mihonManga ->
            val sourceName = mapSource(mihonManga.source)
            val mangaId = generateMangaId(sourceName, mihonManga.url)

            val mangaBackup = MangaBackup(
                id = mangaId,
                title = mihonManga.title ?: "Unknown",
                url = mihonManga.url,
                publicUrl = mihonManga.url,
                coverUrl = mihonManga.thumbnailUrl ?: "",
                largeCoverUrl = mihonManga.thumbnailUrl,
                source = sourceName,
                authors = mihonManga.author,
                state = null,
                contentRating = if (mihonManga.genre.any { it.contains("Adult") || it.contains("Hentai") }) ContentRating.ADULT.name else null,
                isNsfw = mihonManga.genre.any { it.contains("Adult") || it.contains("Hentai") },
                rating = RATING_UNKNOWN,
            )
            mangas.add(mangaBackup)

            if (mihonManga.favorite == true) {
                if (mihonManga.categories.isNotEmpty()) {
                    mihonManga.categories.forEach { categoryId ->
                        favourites.add(
                            FavouriteBackup(
                                mangaId = mangaId,
                                categoryId = categoryId,
                                sortKey = 0,
                                isPinned = false,
                                createdAt = mihonManga.dateAdded ?: System.currentTimeMillis(),
                                manga = mangaBackup
                            )
                        )
                    }
                } else {
                    favourites.add(
                        FavouriteBackup(
                            mangaId = mangaId,
                            categoryId = 0L,
                            sortKey = 0,
                            isPinned = false,
                            createdAt = mihonManga.dateAdded ?: System.currentTimeMillis(),
                            manga = mangaBackup
                        )
                    )
                }
            }
        }
        return Pair(mangas, favourites)
    }

    fun mapHistory(): List<HistoryBackup> {
        val historyList = mutableListOf<HistoryBackup>()

        backup.backupManga.forEach { mihonManga ->
            val sourceName = mapSource(mihonManga.source)
            val mangaId = generateMangaId(sourceName, mihonManga.url)

            mihonManga.history.forEach { history ->
                val chapterId = history.url.longHashCode()

                historyList.add(
                    HistoryBackup(
                        mangaId = mangaId,
                        createdAt = history.lastRead,
                        updatedAt = history.lastRead,
                        chapterId = chapterId,
                        page = 0,
                        scroll = 0f,
                        percent = ReadingProgress.PROGRESS_NONE,
                        maxPercent = ReadingProgress.PROGRESS_NONE,
                        chaptersCount = mihonManga.chapters.size,
                        manga = MangaBackup(
                            id = mangaId,
                            title = mihonManga.title ?: "Unknown",
                            url = mihonManga.url,
                            publicUrl = mihonManga.url,
                            coverUrl = mihonManga.thumbnailUrl ?: "",
                            source = sourceName
                        )
                    )
                )
            }
        }
        return historyList
    }

    fun mapReadChapters(): List<ReadChapterBackup> {
        val readChapters = mutableListOf<ReadChapterBackup>()
        backup.backupManga.forEach { mihonManga ->
            val sourceName = mapSource(mihonManga.source)
            val mangaId = generateMangaId(sourceName, mihonManga.url)
            
            mihonManga.chapters.forEach { mihonChapter ->
                if (mihonChapter.read == true) {
                    readChapters.add(
                        ReadChapterBackup(
                            mangaId = mangaId,
                            chapterId = mihonChapter.url.longHashCode(),
                            page = mihonChapter.lastPageRead?.toInt() ?: 0,
                            readAt = mihonChapter.lastModifiedAt ?: System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        return readChapters
    }

    fun mapScrobbling(): List<ScrobblingBackup> {
        val scrobblings = mutableListOf<ScrobblingBackup>()
        backup.backupManga.forEach { mihonManga ->
            val sourceName = mapSource(mihonManga.source)
            val mangaId = generateMangaId(sourceName, mihonManga.url)
            
            mihonManga.tracking.forEach { tracking ->
                scrobblings.add(
                    ScrobblingBackup(
                        scrobbler = tracking.syncId, // Note: Mihon syncId might need mapping to Kotatsu scrobbler IDs
                        id = tracking.mediaId?.toInt() ?: tracking.mediaIdInt ?: 0,
                        mangaId = mangaId,
                        targetId = tracking.libraryId,
                        status = tracking.status.toString(),
                        chapter = tracking.lastChapterRead?.toInt() ?: 0,
                        comment = null,
                        rating = tracking.score ?: 0f
                    )
                )
            }
        }
        return scrobblings
    }

    fun mapSources(): List<SourceBackup> {
        return backup.backupSources.map {
            SourceBackup(
                source = mapSource(it.sourceId),
                sortKey = 0,
                lastUsedAt = System.currentTimeMillis(),
                addedIn = 0,
                isEnabled = true
            )
        }
    }
}
