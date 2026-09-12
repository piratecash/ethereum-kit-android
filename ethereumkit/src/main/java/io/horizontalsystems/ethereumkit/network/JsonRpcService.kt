package io.horizontalsystems.ethereumkit.network

import io.horizontalsystems.ethereumkit.api.core.RpcResponse
import io.reactivex.Single
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.net.URI

/**
 * Retrofit service interface for JSON-RPC calls.
 *
 * This is a unified interface used by both NodeApiProvider and BinanceTokenTransactionProvider
 * for making JSON-RPC requests to Ethereum-compatible nodes.
 */
interface JsonRpcService {

    @POST
    @Headers("Content-Type: application/json", "Accept: application/json")
    fun call(@Url uri: URI, @Body jsonRpc: String): Single<RpcResponse>
}
