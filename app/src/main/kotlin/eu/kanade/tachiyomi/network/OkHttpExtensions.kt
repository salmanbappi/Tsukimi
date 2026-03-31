package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import io.reactivex.Observable
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * OkHttp extension functions for Mihon compatibility.
 */

val jsonMime = "application/json; charset=utf-8".toMediaType()

fun Call.asObservable(): Observable<Response> {
    return Observable.create { emitter ->
        val call = clone()
        emitter.setCancellable { call.cancel() }

        try {
            val response = call.execute()
            if (!emitter.isDisposed) {
                emitter.onNext(response)
                emitter.onComplete()
            }
        } catch (e: Exception) {
            if (!emitter.isDisposed) {
                emitter.onError(e)
            }
        }
    }
}

fun Call.asObservableSuccess(): Observable<Response> {
    return asObservable().doOnNext { response ->
        if (!response.isSuccessful) {
            response.close()
            throw HttpException(response.code)
        }
    }
}

suspend fun Call.await(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    return suspendCancellableCoroutine { continuation ->
        val callback = object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) {
                    response.body.close()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                val exception = IOException(e.message, e).apply { stackTrace = callStack }
                continuation.resumeWithException(exception)
            }
        }

        enqueue(callback)

        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (ex: Throwable) {
                // Ignore cancel exception
            }
        }
    }
}

/**
 * @since extensions-lib 1.5
 */
suspend fun Call.awaitSuccess(): Response {
    val callStack = Exception().stackTrace.run { copyOfRange(1, size) }
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code).apply { stackTrace = callStack }
    }
    return response
}

fun OkHttpClient.newCachelessCallWithProgress(request: Request, listener: ProgressListener): Call {
    val progressClient = newBuilder()
        .cache(null)
        .addNetworkInterceptor { chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse.newBuilder()
                .body(ProgressResponseBody(originalResponse.body!!, listener))
                .build()
        }
        .build()

    return progressClient.newCall(request)
}

/**
 * Exception that handles HTTP codes considered not successful by OkHttp.
 * Use it to have a standardized error message in the app across the extensions.
 *
 * @since extensions-lib 1.5
 * @param code [Int] the HTTP status code
 */
class HttpException(val code: Int) : IllegalStateException("HTTP error $code")
