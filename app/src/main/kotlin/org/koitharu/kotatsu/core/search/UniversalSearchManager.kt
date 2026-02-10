package org.koitharu.kotatsu.core.search

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UniversalSearchManager @Inject constructor(
    private val repositoryFactory: MangaRepository.Factory
) {

    // Hardcoded stable sources for the "Meta-Search" (can be made dynamic later)
    // IDs need to be resolved to actual source instances in the real app
    // For this prototype, we assume we can get them by ID or Name
    private val trustedSourceIds = listOf(
        "MANGADEX", // Mangadex
        "MANGANATO", // Manganato
        "MANGASEE"   // MangaSee
    )

    suspend fun universalSearch(query: String): List<Manga> = coroutineScope {
        // Parallel execution: Search all trusted sources at once
        val jobs = trustedSourceIds.map { sourceId ->
            async {
                try {
                    // TODO: Need a way to resolve Source ID to MangaSource object here.
                    // For now, this is a conceptual stub to prove the architecture.
                    // val source = sourceManager.get(sourceId) 
                    // val repo = repositoryFactory.create(source)
                    // repo.getList(0, SortOrder.RELEVANCE, MangaListFilter(query = query))
                    emptyList<Manga>() // Placeholder
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        val results = jobs.awaitAll().flatten()
        
        // Deduplicate results based on Title (normalized)
        deduplicateResults(results)
    }

    private fun deduplicateResults(raw: List<Manga>): List<Manga> {
        val seen = mutableSetOf<String>()
        return raw.filter { manga ->
            val key = normalizeTitle(manga.title)
            if (key in seen) {
                false
            } else {
                seen.add(key)
                true
            }
        }
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
