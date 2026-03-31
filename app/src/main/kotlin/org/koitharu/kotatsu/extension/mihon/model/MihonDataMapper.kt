package org.koitharu.kotatsu.extension.mihon.model

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.Page
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.ContentRating

object MihonDataMapper {

    fun toKotoManga(sManga: SManga, source: MihonMangaSource): Manga {
        return Manga(
            id = sManga.url.hashCode().toLong(),
            title = sManga.title,
            altTitles = emptySet(),
            url = sManga.url,
            publicUrl = sManga.url,
            rating = 0f,
            contentRating = ContentRating.SAFE,
            coverUrl = sManga.thumbnail_url,
            tags = emptySet(),
            state = when(sManga.status) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                else -> MangaState.ONGOING
            },
            authors = sManga.author?.let { setOf(it) } ?: emptySet(),
            largeCoverUrl = sManga.thumbnail_url,
            description = sManga.description,
            chapters = null,
            source = source
        )
    }

    fun toMihonManga(manga: Manga): SManga {
        return SManga.create().apply {
            url = manga.url
            title = manga.title
            thumbnail_url = manga.coverUrl
            author = manga.authors.joinToString(", ")
            description = manga.description
            status = when(manga.state) {
                MangaState.ONGOING -> 1
                MangaState.FINISHED -> 2
                else -> 0
            }
        }
    }

    fun toKotoChapter(sChapter: SChapter, source: MihonMangaSource): MangaChapter {
        return MangaChapter(
            id = sChapter.url.hashCode().toLong(),
            url = sChapter.url,
            title = sChapter.name,
            volume = null,
            number = sChapter.chapter_number,
            uploadDate = sChapter.date_upload,
            branch = null,
            scanlator = sChapter.scanlator,
            source = source
        )
    }

    fun toMihonChapter(chapter: MangaChapter): SChapter {
        return SChapter.create().apply {
            url = chapter.url
            name = chapter.title ?: ""
            chapter_number = chapter.number
            date_upload = chapter.uploadDate ?: 0L
            scanlator = chapter.scanlator
        }
    }

    fun toKotoPage(page: Page, source: MihonMangaSource): MangaPage {
        return MangaPage(
            id = page.index.toLong(),
            url = page.imageUrl ?: page.url,
            preview = null,
            source = source
        )
    }
}
