package org.koitharu.kotatsu.extension.mihon.model

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.Page
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource

object MihonDataMapper {

    fun toKotoManga(sManga: SManga, source: MangaSource): Manga {
        return Manga(
            id = sManga.url.hashCode().toLong(),
            source = source.name,
            url = sManga.url,
            title = sManga.title,
            coverUrl = sManga.thumbnail_url ?: "",
            largeCoverUrl = sManga.thumbnail_url,
            description = sManga.description,
            authors = sManga.author,
            genres = sManga.genre?.split(", ") ?: emptyList(),
            isNsfw = false, // Should be refined based on genre
            chaptersCount = 0
        )
    }

    fun toMihonManga(manga: Manga): SManga {
        return SManga.create().apply {
            url = manga.url
            title = manga.title
            thumbnail_url = manga.coverUrl
            author = manga.authors
            description = manga.description
            genre = manga.genres.joinToString(", ")
        }
    }

    fun toKotoChapter(sChapter: SChapter, source: MangaSource): MangaChapter {
        return MangaChapter(
            id = sChapter.url.hashCode().toLong(),
            url = sChapter.url,
            name = sChapter.name,
            number = sChapter.chapter_number,
            date = sChapter.date_upload,
            scanlator = sChapter.scanlator,
            source = source.name
        )
    }

    fun toMihonChapter(chapter: MangaChapter): SChapter {
        return SChapter.create().apply {
            url = chapter.url
            name = chapter.name
            chapter_number = chapter.number
            date_upload = chapter.date
            scanlator = chapter.scanlator
        }
    }

    fun toKotoPage(page: Page): MangaPage {
        return MangaPage(
            id = page.index.toLong(),
            url = page.imageUrl ?: page.url,
            previewUrl = null
        )
    }
}
