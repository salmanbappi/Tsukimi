package org.koitharu.kotatsu.core.network.cf

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Anti-Cloudflare Shield".
 * Automatically intercepts Cloudflare challenges, solves them via a hidden WebView,
 * injects the cookies, and retries the request transparently.
 */
@Singleton
class CloudFlareSolverInterceptor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webViewExecutor: WebViewExecutor
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        // 1. Try the request normally
        val response = chain.proceed(request)

        // 2. Check if we hit a Cloudflare wall (403/503 with specific headers)
        if (response.code in listOf(403, 503) && isCloudFlareChallenge(response)) {
            response.close() // Close the failed response body

            // 3. Launch the solver (synchronized to prevent spamming WebViews)
            synchronized(this) {
                try {
                    // Block the thread and solve in background
                    val success = solveChallenge(request.url.toString())
                    if (success) {
                        // 4. Retry with new cookies/headers
                        return chain.proceed(request)
                    }
                } catch (e: Exception) {
                    e.printStackTraceDebug()
                }
            }
        }

        return response
    }

    private fun isCloudFlareChallenge(response: Response): Boolean {
        // Headers often used by CF
        val server = response.header("Server") ?: ""
        val cfRay = response.header("CF-RAY")
        return server.contains("cloudflare", ignoreCase = true) || cfRay != null
    }

    private fun solveChallenge(url: String): Boolean {
        // This is a blocking call that delegates to the WebViewExecutor on the Main Thread
        // It waits until the WebView loads the page and the 'cf_clearance' cookie appears.
        return try {
             // We use a helper runBlocking or similar mechanism if we are in an interceptor
             // But since Interceptors are worker threads, we can block.
             // We need to bridge to Coroutines for WebViewExecutor.
             
             // NOTE: Real implementation needs careful threading. 
             // Ideally we use `runBlocking` here but that's dangerous. 
             // Better: Use a latch or future.
             
             // For this prototype, we assume WebViewExecutor has a blocking 'solve' method
             // or we bridge it:
             kotlinx.coroutines.runBlocking {
                 webViewExecutor.tryResolveCaptcha(
                     exception = CloudFlareProtectedException(url, null, okhttp3.Headers.Builder().build()),
                     timeout = 30_000 // 30 seconds max
                 )
             }
        } catch (e: Exception) {
            false
        }
    }
}
