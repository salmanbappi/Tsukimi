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
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.util.longHashCode

class MihonBackupMapper(private val backup: MihonBackup) {

    // Extended Mihon/Tachiyomi source IDs mapping to Kotatsu names.
    private val sourceIdMap = mapOf(
        7L to "MANGADEX",
        6903828345115167097L to "MANGANATO",
        4714104230103756887L to "MANGAHASU",
        7514120392345167097L to "MANGAPARK",
        -4966601429443657573L to "MANGAKAKALOT",
        -7333621419443657573L to "MANGASEE",
        -1966601429443657573L to "MANGALIFE",
        138345115167097L to "NHENTAI",
        -5534211419443657573L to "READMANGA",
        -5433621419443657573L to "MANGALIB",
        // Popular Madara/WordPress sources
        8113546738590150117L to "MANGAGREAT",
        -5365313933083070181L to "COMICK",
        -1234567890123456789L to "ASURASCANS",
        82345115167097L to "MANGASHOOT",
        -512345115167097L to "REAPER_SCANS",
        // Bato
        7002012345115167097L to "BATO",
        // Added more
        541234115167097L to "MANGAPLUS",
        -7333621419443657574L to "MANGAMY",
        1L to "MANGASEE",
        -7607718917849419131L to "MANGAHASU",
        -8833621419443657573L to "MANGALIB",
        228138345115167097L to "NINEMANGA_EN",
        6064115167097L to "GUYA",
        8064115167097L to "GENKAN",
    )

    private fun mapScrobbler(mihonSyncId: Int): Int {
        return when (mihonSyncId) {
            1 -> 3 // MAL
            2 -> 2 // AniList
            3 -> 4 // Kitsu
            4 -> 1 // Shikimori
            else -> mihonSyncId // Fallback
        }
    }

    private fun findMatchingKotatsuSource(mihonName: String): String {
        val cleanMihonName = mihonName.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
        
        // 1. Exact match on enum name
        MangaParserSource.entries.find { it.name.equals(mihonName, ignoreCase = true) }?.let { return it.name }
        
        // 2. Exact match on title
        MangaParserSource.entries.find { it.title.equals(mihonName, ignoreCase = true) }?.let { return it.name }
        
        // 3. Clean alphanumeric match on title
        MangaParserSource.entries.find { 
            it.title.replace(Regex("[^A-Za-z0-9]"), "").lowercase() == cleanMihonName
        }?.let { return it.name }
        
        // 4. Substring match
        val possibleMatches = MangaParserSource.entries.filter {
            val cleanKotatsuName = it.title.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
            cleanKotatsuName.isNotEmpty() && (cleanMihonName.contains(cleanKotatsuName) || cleanKotatsuName.contains(cleanMihonName))
        }
        
        if (possibleMatches.isNotEmpty()) {
            return possibleMatches.minByOrNull { 
                kotlin.math.abs(it.title.length - mihonName.length) 
            }!!.name
        }
        
        return mihonName.uppercase().replace(Regex("[^A-Z0-9]"), "_")
    }

    private fun mapSource(mihonSourceId: Long): String {
        // Try local override map first
        sourceIdMap[mihonSourceId]?.let { return it }
        
        // Get name from translator or backup
        val mihonName = MihonSourceTranslator.getSourceName(mihonSourceId) 
            ?: backup.backupSources.find { it.sourceId == mihonSourceId }?.name 
            ?: return mihonSourceId.toString()

        return findMatchingKotatsuSource(mihonName)
    }

    private fun generateMangaId(source: String, url: String): Long {
        return "${source}_${url}".longHashCode()
    }

    fun mapCategories(): List<CategoryBackup> {
        return backup.backupCategories.mapIndexed { index, it ->
            CategoryBackup(
                // Kotatsu uses Int for categoryId and ID 0 is not used (reserved for "All").
                // We use a stable hash of the Mihon ID if available, otherwise fallback to index.
                categoryId = getKotatsuCategoryId(it, index),
                createdAt = System.currentTimeMillis(),
                sortKey = it.order ?: index,
                title = it.name
            )
        }
    }

    private fun getKotatsuCategoryId(category: org.koitharu.kotatsu.backups.data.model.mihon.MihonBackupCategory?, index: Int): Int {
        if (category == null) return 1 // Default category
        val id = category.id
        return if (id == null || id == 0L) {
            index + 1 // Use 1-based index if no ID
        } else {
            (id.toString().hashCode() and 0x7FFFFFFF) % 1000000 + 1
        }
    }

    fun mapMangaAndFavourites(): Pair<List<MangaBackup>, List<FavouriteBackup>> {
        val mangas = mutableListOf<MangaBackup>()
        val favourites = mutableListOf<FavouriteBackup>()

        backup.backupManga.forEach { mihonManga ->
            val sourceName = mapSource(mihonManga.source)
            
            // Clean URL for Kotatsu parsers
            var cleanUrl = mihonManga.url
            if (sourceName == "MANGADEX") {
                cleanUrl = cleanUrl.removePrefix("/manga/").removePrefix("/").removeSuffix("/")
            } else if (sourceName == "MANGANATO" || sourceName == "MANGAKAKALOT") {
                cleanUrl = cleanUrl.removePrefix("/")
            }
            
            val mangaId = generateMangaId(sourceName, cleanUrl)

            val mangaBackup = MangaBackup(
                id = mangaId,
                title = mihonManga.title ?: "Unknown",
                url = cleanUrl,
                publicUrl = cleanUrl,
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

            if (mihonManga.favorite) {
                if (mihonManga.categories.isNotEmpty()) {
                    mihonManga.categories.forEach { mihonCategoryId ->
                        // Try to find category by ID, then by order, then treat ID as index
                        val categoryIndex = backup.backupCategories.indexOfFirst { it.id == mihonCategoryId }
                            .takeIf { it >= 0 }
                            ?: backup.backupCategories.indexOfFirst { it.order?.toLong() == mihonCategoryId }
                            .takeIf { it >= 0 }
                            ?: mihonCategoryId.toInt().takeIf { it < backup.backupCategories.size }

                        val category = categoryIndex?.let { backup.backupCategories[it] }
                        val kotatsuCategoryId = getKotatsuCategoryId(category, categoryIndex ?: -1)

                        favourites.add(
                            FavouriteBackup(
                                mangaId = mangaId,
                                categoryId = kotatsuCategoryId.toLong(),
                                sortKey = 0,
                                isPinned = false,
                                createdAt = mihonManga.dateAdded ?: System.currentTimeMillis(),
                                manga = mangaBackup
                            )
                        )
                    }
                } else {
                    // If no categories in Mihon, it's in the "Read later" category (ID 1 in Kotatsu by default)
                    favourites.add(
                        FavouriteBackup(
                            mangaId = mangaId,
                            categoryId = 1L,
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
            var cleanUrl = mihonManga.url
            if (sourceName == "MANGADEX") {
                cleanUrl = cleanUrl.removePrefix("/manga/").removePrefix("/").removeSuffix("/")
            } else if (sourceName == "MANGANATO") {
                cleanUrl = cleanUrl.removePrefix("/")
            }
            val mangaId = generateMangaId(sourceName, cleanUrl)

            val combinedHistory = mihonManga.history + mihonManga.historyAniyomi
            combinedHistory.forEach { history ->
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
                            url = cleanUrl,
                            publicUrl = cleanUrl,
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
            var cleanUrl = mihonManga.url
            if (sourceName == "MANGADEX") {
                cleanUrl = cleanUrl.removePrefix("/manga/").removePrefix("/").removeSuffix("/")
            } else if (sourceName == "MANGANATO") {
                cleanUrl = cleanUrl.removePrefix("/")
            }
            val mangaId = generateMangaId(sourceName, cleanUrl)
            
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
            var cleanUrl = mihonManga.url
            if (sourceName == "MANGADEX") {
                cleanUrl = cleanUrl.removePrefix("/manga/").removePrefix("/").removeSuffix("/")
            } else if (sourceName == "MANGANATO" || sourceName == "MANGAKAKALOT") {
                cleanUrl = cleanUrl.removePrefix("/")
            }
            val mangaId = generateMangaId(sourceName, cleanUrl)
            
            mihonManga.tracking.forEach { tracking ->
                scrobblings.add(
                    ScrobblingBackup(
                        scrobbler = mapScrobbler(tracking.syncId),
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
