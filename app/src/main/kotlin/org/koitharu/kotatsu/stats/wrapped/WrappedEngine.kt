package org.koitharu.kotatsu.stats.wrapped

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.stats.data.StatsRepository
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WrappedEngine @Inject constructor(
    private val db: MangaDatabase,
    private val statsRepository: StatsRepository
) {

    suspend fun generateYearlyWrapped(year: Int): TsukimiWrapped {
        val calendar = Calendar.getInstance()
        calendar.set(year, Calendar.JANUARY, 1, 0, 0, 0)
        val startTime = calendar.timeInMillis
        
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, Calendar.DECEMBER)
        calendar.set(Calendar.DAY_OF_MONTH, 31)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val endTime = calendar.timeInMillis

        val dao = db.getStatsDao()
        val allStats = dao.findAll(startTime, endTime)
        
        val totalPages = allStats.sumOf { it.pages }
        val totalTimeMillis = allStats.sumOf { it.duration }
        
        val mangaCounts = allStats.groupBy { it.mangaId }
            .mapValues { it.value.sumOf { s -> s.pages } }
        val topMangaId = mangaCounts.maxByOrNull { it.value }?.key
        val topManga = topMangaId?.let { db.getMangaDao().find(it)?.manga?.toManga(emptySet(), null) }

        // Hour analysis
        val hourCounts = IntArray(24)
        for (stat in allStats) {
            calendar.timeInMillis = stat.startedAt
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            hourCounts[hour]++
        }
        val favoriteHour = hourCounts.indices.maxBy { hourCounts[it] }

        val persona = when {
            favoriteHour in 0..5 -> "The Night Owl"
            totalPages > 10000 -> "The Devourer"
            allStats.size > 300 -> "The Daily Habitualist"
            else -> "The Selective Connoisseur"
        }

        return TsukimiWrapped(
            year = year,
            totalPages = totalPages,
            totalTimeMinutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMillis),
            topManga = topManga,
            topGenre = "Action", // TODO: aggregate from tags
            readingStreak = 0, // TODO: calculate streak
            favoriteHour = favoriteHour,
            readingPersona = persona
        )
    }
}
