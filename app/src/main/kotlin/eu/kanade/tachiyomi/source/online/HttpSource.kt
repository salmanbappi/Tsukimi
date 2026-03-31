package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import io.reactivex.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * A simple implementation for sources from a website.
 */
@Suppress("unused")
abstract class HttpSource : CatalogueSource {

    /**
     * Network service.
     */
    protected val network: NetworkHelper by injectLazy()

    /**
     * Base url of the website without the trailing slash, like: http://mysite.com
     */
    abstract val baseUrl: String

    /**
     * Version id used to generate the source id. If the site completely changes and urls are
     * incompatible, you may increase this value and it'll be considered as a new source.
     */
    open val versionId = 1

    /**
     * ID of the source. By default it uses a generated id using the first 16 characters (64 bits)
     * of the MD5 of the string `"${name.lowercase()}/$lang/$versionId"`.
     */
    override val id by lazy { generateId(name, lang, versionId) }

    /**
     * Headers used for requests.
     */
    val headers: Headers by lazy { headersBuilder().build() }

    /**
     * Default network client for doing requests.
     */
    open val client: OkHttpClient
        get() = network.client

    /**
     * Generates a unique ID for the source based on the provided [name], [lang] and
     * [versionId].
     */
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    /**
     * Headers builder for requests. Implementations can override this method for custom headers.
     */
    protected open fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    /**
     * Visible name of the source.
     */
    override fun toString() = "$name (${lang.uppercase()})"

    // ======== Popular manga ========

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopularManga"))
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            popularMangaParse(client.newCall(popularMangaRequest(page)).execute())
        }
    }

    protected abstract fun popularMangaRequest(page: Int): Request

    protected abstract fun popularMangaParse(response: Response): MangasPage

    // ======== Search manga ========

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchManga"))
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.fromCallable {
            searchMangaParse(client.newCall(searchMangaRequest(page, query, filters)).execute())
        }
    }

    protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request

    protected abstract fun searchMangaParse(response: Response): MangasPage

    // ======== Latest updates ========

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.fromCallable {
            latestUpdatesParse(client.newCall(latestUpdatesRequest(page)).execute())
        }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request

    protected abstract fun latestUpdatesParse(response: Response): MangasPage

    // ======== Manga details ========

    override suspend fun getMangaDetails(manga: SManga): SManga {
        return fetchMangaDetails(manga).blockingFirst()
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getMangaDetails"))
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            mangaDetailsParse(client.newCall(mangaDetailsRequest(manga)).execute()).apply {
                initialized = true
            }
        }
    }

    open fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    protected abstract fun mangaDetailsParse(response: Response): SManga

    // ======== Chapter list ========

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        return fetchChapterList(manga).blockingFirst()
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getChapterList"))
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            chapterListParse(client.newCall(chapterListRequest(manga)).execute())
        }
    }

    protected open fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    protected abstract fun chapterListParse(response: Response): List<SChapter>

    // ======== Page list ========

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).blockingFirst()
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPageList"))
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            pageListParse(client.newCall(pageListRequest(chapter)).execute())
        }
    }

    protected open fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    protected abstract fun pageListParse(response: Response): List<Page>

    // ======== Image URL ========

    open suspend fun getImageUrl(page: Page): String {
        return fetchImageUrl(page).blockingFirst()
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getImageUrl"))
    open fun fetchImageUrl(page: Page): Observable<String> {
        return Observable.fromCallable {
            imageUrlParse(client.newCall(imageUrlRequest(page)).execute())
        }
    }

    protected open fun imageUrlRequest(page: Page): Request {
        return GET(page.url, headers)
    }

    protected abstract fun imageUrlParse(response: Response): String

    // ======== Image request ========

    open suspend fun getImage(page: Page): Response {
        return client.newCall(imageRequest(page)).execute()
    }

    protected open fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headers)
    }

    // ======== Helper methods ========

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (e: URISyntaxException) {
            orig
        }
    }

    open fun getMangaUrl(manga: SManga): String {
        return mangaDetailsRequest(manga).url.toString()
    }

    open fun getChapterUrl(chapter: SChapter): String {
        return pageListRequest(chapter).url.toString()
    }

    open fun prepareNewChapter(chapter: SChapter, manga: SManga) {}

    override fun getFilterList() = FilterList()
}
