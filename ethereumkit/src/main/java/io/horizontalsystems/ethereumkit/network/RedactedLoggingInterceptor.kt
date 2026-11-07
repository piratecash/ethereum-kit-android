package io.horizontalsystems.ethereumkit.network

import co.touchlab.kermit.Logger
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** RPC/WebSocket URLs can carry API keys in the path or query, so only scheme+host is ever logged. */
fun HttpUrl.hostOnly(): String = "$scheme://$host"

/** BASIC-level request logging without ever exposing a URL's path or query, body, or headers. */
class RedactedLoggingInterceptor(private val logger: Logger) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val redactedUrl = request.url.hostOnly()
        logger.d { "--> ${request.method} $redactedUrl" }

        val startNs = System.nanoTime()
        val response = chain.proceed(request)
        val tookMs = (System.nanoTime() - startNs) / 1_000_000

        logger.d { "<-- ${response.code} $redactedUrl (${tookMs}ms)" }
        return response
    }
}
