package io.horizontalsystems.ethereumkit.network

import okhttp3.OkHttpClient

/**
 * Singleton provider for shared OkHttpClient instance.
 *
 * OkHttpClient is designed to be shared - it manages connection pooling, thread pools,
 * and other resources internally. Sharing a single client instance reduces memory usage
 * and enables connection reuse across different services.
 *
 * Use [newClient] to create customized instances that share the underlying connection
 * pool and dispatcher with the base instance.
 *
 * Note: This uses OkHttp's default timeouts (10s connect, 10s read, 10s write).
 * Individual services can override timeouts via [newClient] if needed.
 */
object SharedHttpClient {

    /**
     * Base OkHttpClient instance. All clients created via [newClient] share this
     * instance's connection pool and dispatcher.
     *
     * Uses OkHttp defaults to maintain backward compatibility with previous behavior.
     */
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    /**
     * Creates a new OkHttpClient that shares the connection pool and dispatcher
     * with the base [instance], but with custom configuration (e.g., interceptors, timeouts).
     *
     * @param configure Lambda to configure the OkHttpClient.Builder
     * @return A new OkHttpClient instance with shared resources
     */
    fun newClient(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient {
        return instance.newBuilder().apply(configure).build()
    }
}
