package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.reactivex.Observable

/**
 * A source that provides a catalogue of manga.
 */
interface CatalogueSource : Source {

    /**
     * Get a page with a list of manga.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     * @return a page with a list of manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getPopularManga(page: Int): MangasPage {
        return fetchPopularManga(page).blockingFirst()
    }

    /**
     * Get a page with a list of manga.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     * @return a page with a list of manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return fetchSearchManga(page, query, filters).blockingFirst()
    }

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     * @return a page with a list of manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getLatestUpdates(page: Int): MangasPage {
        return fetchLatestUpdates(page).blockingFirst()
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPopularManga"),
    )
    fun fetchPopularManga(page: Int): Observable<MangasPage>

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getSearchManga"),
    )
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage>

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getLatestUpdates"),
    )
    fun fetchLatestUpdates(page: Int): Observable<MangasPage>

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList = FilterList()
}
