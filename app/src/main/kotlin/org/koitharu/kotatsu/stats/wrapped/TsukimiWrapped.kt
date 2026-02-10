package org.koitharu.kotatsu.stats.wrapped

import org.koitharu.kotatsu.parsers.model.Manga
import java.util.Calendar

data class TsukimiWrapped(
    val year: Int,
    val totalPages: Int,
    val totalTimeMinutes: Long,
    val topManga: Manga?,
    val topGenre: String?,
    val readingStreak: Int,
    val favoriteHour: Int, // 0-23
    val readingPersona: String // e.g. "The Night Owl", "The Completionist"
)
