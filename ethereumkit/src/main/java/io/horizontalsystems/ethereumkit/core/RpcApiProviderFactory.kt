package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.api.core.IRpcApiProvider
import io.horizontalsystems.ethereumkit.api.core.NodeApiProvider
import io.horizontalsystems.ethereumkit.models.RpcSource
import okhttp3.EventListener

object RpcApiProviderFactory {

    private val providersCache = mutableMapOf<RpcSource, IRpcApiProvider>()

    @Synchronized
    fun nodeApiProvider(
        rpcSource: RpcSource,
        eventListenerFactory: EventListener.Factory? = null
    ): IRpcApiProvider {
        // A per-account eventListenerFactory must not be attributed to other accounts sharing
        // the same rpcSource via the cache, so bypass caching entirely when instrumented.
        if (eventListenerFactory != null) {
            return buildProvider(rpcSource, eventListenerFactory)
        }

        return providersCache.getOrPut(rpcSource) { buildProvider(rpcSource, null) }
    }

    private fun buildProvider(
        rpcSource: RpcSource,
        eventListenerFactory: EventListener.Factory?
    ): IRpcApiProvider = when (rpcSource) {
        is RpcSource.Http -> {
            NodeApiProvider(rpcSource.uris, EthereumKit.gson, rpcSource.auth, eventListenerFactory)
        }

        is RpcSource.WebSocket -> throw IllegalStateException("Websocket not supported")
    }

}
