package io.horizontalsystems.ethereumkit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.api.core.IRpcApiProvider
import io.horizontalsystems.ethereumkit.api.core.NodeApiProvider
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.models.RpcSource
import okhttp3.EventListener

object RpcApiProviderFactory {

    private val providersCache = mutableMapOf<RpcSource, IRpcApiProvider>()

    @Synchronized
    fun nodeApiProvider(
        rpcSource: RpcSource,
        eventListenerFactory: EventListener.Factory? = null,
        chain: Chain? = null
    ): IRpcApiProvider {
        // A per-account eventListenerFactory must not be attributed to other accounts sharing
        // the same rpcSource via the cache, so bypass caching entirely when instrumented.
        if (eventListenerFactory != null) {
            return buildProvider(rpcSource, eventListenerFactory, chain)
        }

        return providersCache.getOrPut(rpcSource) { buildProvider(rpcSource, null, chain) }
    }

    private fun buildProvider(
        rpcSource: RpcSource,
        eventListenerFactory: EventListener.Factory?,
        chain: Chain?
    ): IRpcApiProvider = when (rpcSource) {
        is RpcSource.Http -> {
            val logger = chain?.let { kitLogger(it.id) } ?: Logger.withTag("EthereumKit")
            NodeApiProvider(rpcSource.uris, EthereumKit.gson, rpcSource.auth, eventListenerFactory, logger)
        }

        is RpcSource.WebSocket -> throw IllegalStateException("Websocket not supported")
    }

}
