package org.koitharu.kotatsu.backups.domain.mihon

import org.koitharu.kotatsu.backups.data.model.CategoryBackup
import org.koitharu.kotatsu.backups.data.model.FavouriteBackup
import org.koitharu.kotatsu.backups.data.model.HistoryBackup
import org.koitharu.kotatsu.backups.data.model.MangaBackup
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

    // Maps Mihon source IDs (e.g. 12345) to known Kotatsu source names (e.g. "MANGADEX") if possible.
    // As a fallback, we keep the numeric string representation.
    private fun mapSource(mihonSourceId: Long): String {
        val source = backup.backupSources.find { it.sourceId == mihonSourceId }
        val name = source?.name ?: mihonSourceId.toString()
        // Convert something like "MangaDex" to "MANGADEX" to match Kotatsu's style,
        // although some extensions might not perfectly align without a full dictionary.
        return name.uppercase().replace(" ", "_")
    }

    private fun generateMangaId(source: String, url: String): Long {
        return "${source}_${url}".longHashCode()
    }

    fun mapCategories(): List<CategoryBackup> {
        return backup.backupCategories.map {
            CategoryBackup(
                categoryId = it.order?.toInt() ?: it.id?.toInt() ?: 0,
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
                contentRating = if (mihonManga.genre.contains("Adult") || mihonManga.genre.contains("Hentai")) ContentRating.ADULT.name else null,
                isNsfw = mihonManga.genre.contains("Adult") || mihonManga.genre.contains("Hentai"),
                rating = RATING_UNKNOWN,
            )
            mangas.add(mangaBackup)

            if (mihonManga.favorite == true) {
                // If the manga belongs to categories, map them. Otherwise, use default (0)
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

            // Only map history if they have reading history on a chapter
            mihonManga.history.forEach { history ->
                // Try to find the associated chapter to get its number
                val chapter = mihonManga.chapters.find { it.url == history.url }
                val chapterId = history.url.longHashCode()

                historyList.add(
                    HistoryBackup(
                        mangaId = mangaId,
                        createdAt = history.lastRead,
                        updatedAt = history.lastRead,
                        chapterId = chapterId,
                        page = 0, // Mihon does not store exact page scroll reliably in the basic export
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
}
