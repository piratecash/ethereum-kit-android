package io.horizontalsystems.ethereumkit.network

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.reactivex.Single
import okhttp3.EventListener
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

class BlockscoutService(
    baseUrl: String,
    apiKeys: List<String>,
    eventListenerFactory: EventListener.Factory? = null
) {
    private val service: BlockscoutServiceApi
    private val apiKey = apiKeys.firstOrNull { it.isNotBlank() }

    init {
        val httpClient = SharedHttpClient.newClient {
            eventListenerFactory?.let { eventListenerFactory(it) }
            addInterceptor { chain ->
                // Cloudflare in front of Blockscout instances (e.g. robinhoodchain.blockscout.com)
                // returns 403 for user agents that don't look like a real browser, which breaks
                // transaction sync entirely. A full browser UA string is required to pass.
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                    .build()
                chain.proceed(request)
            }
        }
        val decimalLongAdapter = LongTypeAdapter(isHex = false)
        val decimalIntAdapter = IntTypeAdapter(isHex = false)
        val gson = GsonBuilder()
            .setLenient()
            .registerTypeAdapter(Long::class.java, decimalLongAdapter)
            .registerTypeAdapter(object : TypeToken<Long?>() {}.type, decimalLongAdapter)
            .registerTypeAdapter(Int::class.java, decimalIntAdapter)
            .registerTypeAdapter(object : TypeToken<Int?>() {}.type, decimalIntAdapter)
            .create()

        service = Retrofit.Builder()
            .baseUrl("${baseUrl.trimEnd('/')}/")
            .addCallAdapterFactory(RxJava2CallAdapterFactory.createAsync())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(httpClient)
            .build()
            .create(BlockscoutServiceApi::class.java)
    }

    fun getTransactions(address: String, startBlock: Long): Single<List<BlockscoutTransaction>> =
        fetchPages(startBlock, BlockscoutTransaction::blockNumber) { params ->
            service.transactions(address, apiKey, params)
        }

    fun getInternalTransactions(
        address: String,
        startBlock: Long
    ): Single<List<BlockscoutInternalTransaction>> =
        fetchPages(startBlock, BlockscoutInternalTransaction::blockNumber) { params ->
            service.internalTransactions(address, apiKey, params)
        }

    fun getInternalTransactions(txHash: String): Single<List<BlockscoutInternalTransaction>> =
        fetchPages(0, BlockscoutInternalTransaction::blockNumber) { params ->
            service.transactionInternalTransactions(txHash, apiKey, params)
        }

    fun getTokenTransfers(
        address: String,
        type: String,
        startBlock: Long
    ): Single<List<BlockscoutTokenTransfer>> =
        fetchPages(startBlock, BlockscoutTokenTransfer::blockNumber) { params ->
            service.tokenTransfers(address, type, apiKey, params)
        }

    private fun <T> fetchPages(
        startBlock: Long,
        blockNumber: (T) -> Long?,
        request: (Map<String, String>) -> Single<BlockscoutPage<T>>
    ): Single<List<T>> {
        fun nextPage(
            params: Map<String, String>,
            accumulated: List<T>,
            seenCursors: Set<Map<String, String>>
        ): Single<List<T>> = request(params).flatMap { response ->
            val items = response.items.orEmpty()
            val currentItems = items.filter { item ->
                blockNumber(item)?.let { it >= startBlock } == true
            }
            val result = accumulated + currentItems
            val reachedOlderBlock = items.any { item ->
                blockNumber(item)?.let { it < startBlock } == true
            }
            val nextParams = response.nextPageParams.toStringMap()

            if (reachedOlderBlock || nextParams.isEmpty() || nextParams in seenCursors) {
                Single.just(result)
            } else {
                nextPage(nextParams, result, seenCursors + nextParams)
            }
        }

        return nextPage(emptyMap(), emptyList(), emptySet())
    }

    private fun JsonElement?.toStringMap(): Map<String, String> {
        if (this == null || !isJsonObject) return emptyMap()

        return asJsonObject.entrySet().mapNotNull { (key, value) ->
            if (value.isJsonPrimitive) key to value.asString else null
        }.toMap()
    }

    private interface BlockscoutServiceApi {
        @GET("api/v2/addresses/{address}/transactions")
        fun transactions(
            @Path("address") address: String,
            @Query("apikey") apiKey: String?,
            @QueryMap params: Map<String, String>
        ): Single<BlockscoutPage<BlockscoutTransaction>>

        @GET("api/v2/addresses/{address}/internal-transactions")
        fun internalTransactions(
            @Path("address") address: String,
            @Query("apikey") apiKey: String?,
            @QueryMap params: Map<String, String>
        ): Single<BlockscoutPage<BlockscoutInternalTransaction>>

        @GET("api/v2/transactions/{txHash}/internal-transactions")
        fun transactionInternalTransactions(
            @Path("txHash") txHash: String,
            @Query("apikey") apiKey: String?,
            @QueryMap params: Map<String, String>
        ): Single<BlockscoutPage<BlockscoutInternalTransaction>>

        @GET("api/v2/addresses/{address}/token-transfers")
        fun tokenTransfers(
            @Path("address") address: String,
            @Query("type") type: String,
            @Query("apikey") apiKey: String?,
            @QueryMap params: Map<String, String>
        ): Single<BlockscoutPage<BlockscoutTokenTransfer>>
    }

}

data class BlockscoutPage<T>(
    @SerializedName("items") val items: List<T>?,
    @SerializedName("next_page_params") val nextPageParams: JsonElement?
)

data class BlockscoutAddress(
    @SerializedName("hash") val hash: String?
)

data class BlockscoutToken(
    @SerializedName("address_hash") val addressHash: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("symbol") val symbol: String?,
    @SerializedName("decimals") val decimals: String?
)

data class BlockscoutTotal(
    @SerializedName("value") val value: String?,
    @SerializedName("token_id") val tokenId: String?
)

data class BlockscoutTransaction(
    @SerializedName("hash") val hash: String?,
    @SerializedName("block_number") val blockNumber: Long?,
    @SerializedName("timestamp") val timestamp: String?,
    @SerializedName("nonce") val nonce: Long?,
    @SerializedName("position") val position: Int?,
    @SerializedName("from") val from: BlockscoutAddress?,
    @SerializedName("to") val to: BlockscoutAddress?,
    @SerializedName("value") val value: String?,
    @SerializedName("gas_limit") val gasLimit: String?,
    @SerializedName("gas_price") val gasPrice: String?,
    @SerializedName("gas_used") val gasUsed: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("raw_input") val rawInput: String?
)

data class BlockscoutInternalTransaction(
    @SerializedName("transaction_hash") val transactionHash: String?,
    @SerializedName("block_number") val blockNumber: Long?,
    @SerializedName("timestamp") val timestamp: String?,
    @SerializedName("from") val from: BlockscoutAddress?,
    @SerializedName("to") val to: BlockscoutAddress?,
    @SerializedName("value") val value: String?,
    @SerializedName("index") val index: Int?
)

data class BlockscoutTokenTransfer(
    @SerializedName("transaction_hash") val transactionHash: String?,
    @SerializedName("block_number") val blockNumber: Long?,
    @SerializedName("block_hash") val blockHash: String?,
    @SerializedName("timestamp") val timestamp: String?,
    @SerializedName("from") val from: BlockscoutAddress?,
    @SerializedName("to") val to: BlockscoutAddress?,
    @SerializedName("token") val token: BlockscoutToken?,
    @SerializedName("total") val total: BlockscoutTotal?
)
