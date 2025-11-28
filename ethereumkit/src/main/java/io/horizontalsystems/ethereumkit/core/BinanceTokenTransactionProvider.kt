package io.horizontalsystems.ethereumkit.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.horizontalsystems.ethereumkit.api.core.RpcResponse
import io.horizontalsystems.ethereumkit.network.AddressTypeAdapter
import io.horizontalsystems.ethereumkit.network.BigIntegerTypeAdapter
import io.horizontalsystems.ethereumkit.network.ByteArrayTypeAdapter
import io.horizontalsystems.ethereumkit.network.DefaultBlockParameterTypeAdapter
import io.horizontalsystems.ethereumkit.network.IntTypeAdapter
import io.horizontalsystems.ethereumkit.network.LongTypeAdapter
import java.math.BigInteger
import io.horizontalsystems.ethereumkit.api.jsonrpc.BlockNumberJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.CallJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.GetBlockByNumberJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.GetFilterLogsJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.GetTransactionByHashJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.GetTransactionReceiptJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.JsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.NewFilterJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransaction
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransactionReceipt
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.DefaultBlockParameter
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.TransactionLog
import io.reactivex.Single
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

class BinanceTokenTransactionProvider(
    private val uris: List<URI>,
    private val address: Address,
    private val chainId: Int
) : TokenTransactionProvider {

    private val service: RpcService
    private var currentRpcId = AtomicInteger(0)

    private val gson: Gson

    // In-memory caches
    private val tokenMetaCache = mutableMapOf<Address, TokenMeta>()
    private val blockTimestampCache = mutableMapOf<Long, Long>()
    private val txCache = mutableMapOf<String, RpcTransaction>()
    private val receiptCache = mutableMapOf<String, RpcTransactionReceipt>()

    init {
        val loggingInterceptor = HttpLoggingInterceptor { message -> Timber.d(message) }
            .setLevel(HttpLoggingInterceptor.Level.BASIC)

        val httpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)

        gson = GsonBuilder()
            .setLenient()
            .registerTypeAdapter(BigInteger::class.java, BigIntegerTypeAdapter())
            .registerTypeAdapter(Long::class.java, LongTypeAdapter())
            .registerTypeAdapter(object : TypeToken<Long?>() {}.type, LongTypeAdapter())
            .registerTypeAdapter(Int::class.java, IntTypeAdapter())
            .registerTypeAdapter(object : TypeToken<Int?>() {}.type, IntTypeAdapter())
            .registerTypeAdapter(ByteArray::class.java, ByteArrayTypeAdapter())
            .registerTypeAdapter(Address::class.java, AddressTypeAdapter())
            .registerTypeHierarchyAdapter(DefaultBlockParameter::class.java, DefaultBlockParameterTypeAdapter())
            .create()

        val retrofit = Retrofit.Builder()
            .baseUrl("${uris.first()}/")
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(httpClient.build())
            .build()

        service = retrofit.create(RpcService::class.java)
    }

    override fun getTokenTransactions(startBlock: Long): Single<TokenTransactionProvider.TokenTransactionsResult> {
        return Single.create { emitter ->
            try {
                val endBlock = fetchBlockNumber()
                val realStartBlock = when {
                    startBlock > 0 -> startBlock
                    else -> maxOf(0, endBlock + startBlock)
                }
                val allLogs = fetchAllLogsWithAdaptiveChunking(realStartBlock, endBlock)

                // Convert logs to ProviderTokenTransaction
                val transactions = allLogs
                    .distinctBy { it.transactionHash.toHexString() + it.logIndex }
                    .mapNotNull { log -> convertLogToTransaction(log) }
                    .sortedByDescending { it.blockNumber }

                emitter.onSuccess(
                    TokenTransactionProvider.TokenTransactionsResult(
                        transactions,
                        endBlock
                    )
                )
            } catch (e: Throwable) {
                emitter.onError(e)
            }
        }
    }

    private fun fetchBlockNumber(): Long {
        val rpc = BlockNumberJsonRpc()
        return executeRpcWithFallback(rpc)
    }

    /**
     * Adaptive chunking: start with large chunk (10M), halve on error until MIN_CHUNK_SIZE.
     * If MIN_CHUNK_SIZE fails, switch to next URI and continue from current position.
     */
    private fun fetchAllLogsWithAdaptiveChunking(startBlock: Long, endBlock: Long): List<TransactionLog> {
        val allLogs = mutableListOf<TransactionLog>()
        var currentFrom = startBlock
        var currentChunkSize = INITIAL_CHUNK_SIZE
        var uriIndex = 0
        var lastError: Throwable = Error("No URIs available")

        while (currentFrom <= endBlock && uriIndex < uris.size) {
            val uri = uris[uriIndex]
            val to = minOf(currentFrom + currentChunkSize - 1, endBlock)
            try {
                val chunkLogs = fetchLogsForChunk(currentFrom, to, uri)
                Timber.d("Fetched logs (${chunkLogs.size} from $currentFrom to $to (${to-currentFrom}), left ${endBlock-to} blocks on $uri for chanid $chainId")
                allLogs.addAll(chunkLogs)
                currentFrom = to + 1
                // Reset to initial size on success
                currentChunkSize = INITIAL_CHUNK_SIZE
            } catch (e: Throwable) {
                lastError = e
                if (currentChunkSize > MIN_CHUNK_SIZE) {
                    // Halve the chunk size and retry on same URI
                    currentChunkSize = maxOf(currentChunkSize / 2, MIN_CHUNK_SIZE)
                    Timber.w("Chunk failed, reducing size to $currentChunkSize")
                } else {
                    // Min chunk size failed, switch to next URI and reset chunk size
                    uriIndex++
                    currentChunkSize = INITIAL_CHUNK_SIZE
                    Timber.w("Min chunk failed on $uri, switching to next URI")
                }
            }
        }

        if (currentFrom <= endBlock) {
            // All URIs exhausted before completing
            throw lastError
        }

        return allLogs
    }

    private fun fetchLogsForChunk(fromBlock: Long, toBlock: Long, uri: URI): List<TransactionLog> {
        val walletPadded = "0x" + address.hex.removePrefix("0x").lowercase().padStart(64, '0')
        val transferTopic = TRANSFER_EVENT_SIGNATURE

        // Incoming transfers (to = wallet)
        val incomingFilter = NewFilterJsonRpc(
            fromBlock = fromBlock,
            toBlock = toBlock,
            topics = listOf(transferTopic, null, walletPadded)
        )
        val incomingFilterId = executeRpcOnUri(incomingFilter, uri)
        val incomingLogs = executeRpcOnUri(GetFilterLogsJsonRpc(incomingFilterId), uri)

        // Outgoing transfers (from = wallet)
        val outgoingFilter = NewFilterJsonRpc(
            fromBlock = fromBlock,
            toBlock = toBlock,
            topics = listOf(transferTopic, walletPadded, null)
        )
        val outgoingFilterId = executeRpcOnUri(outgoingFilter, uri)
        val outgoingLogs = executeRpcOnUri(GetFilterLogsJsonRpc(outgoingFilterId), uri)

        return incomingLogs + outgoingLogs
    }

    private fun convertLogToTransaction(log: TransactionLog): ProviderTokenTransaction? {
        return try {
            val txHashHex = log.transactionHash.toHexString()
            val tokenMeta = getTokenMeta(log.address)
            val timestamp = getBlockTimestamp(log.blockNumber)
            val tx = getTransaction(txHashHex)
            val receipt = getReceipt(txHashHex)

            // Validate chainId from transaction signature (EIP-155)
            tx?.let { transaction ->
                val txChainId = transaction.chainId ?: extractChainIdFromV(transaction.v)
                if (txChainId != null && txChainId != chainId.toLong()) {
                    Timber.w("Skipping tx from chainId=$txChainId (expected $chainId): $txHashHex")
                    return null
                }
            }

            // Parse value from log data (Transfer event: value is in data field)
            val value = if (log.data.isNotEmpty()) {
                log.data.toBigInteger()
            } else {
                BigInteger.ZERO
            }

            // Parse from/to from topics
            // topics[0] = event signature
            // topics[1] = from (padded)
            // topics[2] = to (padded)
            val from = if (log.topics.size > 1) {
                Address(log.topics[1].takeLast(40))
            } else {
                tx?.from ?: Address("0x0000000000000000000000000000000000000000")
            }

            val to = if (log.topics.size > 2) {
                Address(log.topics[2].takeLast(40))
            } else {
                tx?.to ?: Address("0x0000000000000000000000000000000000000000")
            }

            ProviderTokenTransaction(
                blockNumber = log.blockNumber,
                timestamp = timestamp,
                hash = log.transactionHash,
                nonce = tx?.nonce ?: 0,
                blockHash = log.blockHash,
                from = from,
                contractAddress = log.address,
                to = to,
                value = value,
                tokenName = tokenMeta.name,
                tokenSymbol = tokenMeta.symbol,
                tokenDecimal = tokenMeta.decimals,
                transactionIndex = log.transactionIndex,
                gasLimit = tx?.gasLimit ?: 0,
                gasPrice = tx?.gasPrice ?: 0,
                gasUsed = receipt?.gasUsed ?: 0,
                cumulativeGasUsed = receipt?.cumulativeGasUsed ?: 0,
                input = tx?.input ?: ByteArray(0)
            )
        } catch (e: Throwable) {
            Timber.w(e, "Failed to convert log to transaction")
            null
        }
    }

    private fun getTokenMeta(contractAddress: Address): TokenMeta {
        tokenMetaCache[contractAddress]?.let { return it }

        val name = fetchTokenString(contractAddress, NAME_METHOD_ID) ?: "Unknown"
        val symbol = fetchTokenString(contractAddress, SYMBOL_METHOD_ID) ?: "Unknown"
        val decimals = fetchTokenDecimals(contractAddress)

        val meta = TokenMeta(name, symbol, decimals)
        tokenMetaCache[contractAddress] = meta
        return meta
    }

    private fun fetchTokenString(contractAddress: Address, methodId: ByteArray): String? {
        return try {
            val rpc = CallJsonRpc(contractAddress, methodId, DefaultBlockParameter.Latest)
            val result = executeRpcWithFallback(rpc)
            decodeAbiString(result)
        } catch (e: Throwable) {
            null
        }
    }

    private fun fetchTokenDecimals(contractAddress: Address): Int {
        return try {
            val rpc = CallJsonRpc(contractAddress, DECIMALS_METHOD_ID, DefaultBlockParameter.Latest)
            val result = executeRpcWithFallback(rpc)
            if (result.isNotEmpty()) {
                result.toBigInteger().toInt()
            } else {
                18
            }
        } catch (e: Throwable) {
            18
        }
    }

    private fun decodeAbiString(data: ByteArray): String? {
        if (data.isEmpty()) return null

        // Try bytes32 packed string (32 bytes, null-terminated)
        if (data.size == 32) {
            val nullIndex = data.indexOf(0)
            val length = if (nullIndex >= 0) nullIndex else data.size
            return String(data, 0, length, Charsets.UTF_8).takeIf { it.isNotBlank() }
        }

        // Try ABI string decoding: offset(32) + length(32) + data
        if (data.size >= 64) {
            try {
                val offset = data.copyOfRange(0, 32).toBigInteger().toInt()
                if (offset + 32 <= data.size) {
                    val length = data.copyOfRange(offset, offset + 32).toBigInteger().toInt()
                    val start = offset + 32
                    val end = start + length
                    if (end <= data.size && length <= 4096) {
                        return String(
                            data,
                            start,
                            length,
                            Charsets.UTF_8
                        ).takeIf { it.isNotBlank() }
                    }
                }
            } catch (e: Throwable) {
                // Fall through
            }
        }

        // Fallback: try to decode whole buffer
        return String(data, Charsets.UTF_8).trim('\u0000').takeIf { it.isNotBlank() }
    }

    private fun getBlockTimestamp(blockNumber: Long): Long {
        blockTimestampCache[blockNumber]?.let { return it }

        val rpc = GetBlockByNumberJsonRpc(blockNumber)
        val block = executeRpcWithFallback(rpc)
        blockTimestampCache[blockNumber] = block.timestamp
        return block.timestamp
    }

    private fun getTransaction(txHashHex: String): RpcTransaction? {
        txCache[txHashHex]?.let { return it }

        return try {
            val rpc = GetTransactionByHashJsonRpc(txHashHex.hexStringToByteArray())
            val tx = executeRpcWithFallback(rpc)
            txCache[txHashHex] = tx
            tx
        } catch (e: Throwable) {
            null
        }
    }

    private fun getReceipt(txHashHex: String): RpcTransactionReceipt? {
        receiptCache[txHashHex]?.let { return it }

        return try {
            val rpc = GetTransactionReceiptJsonRpc(txHashHex.hexStringToByteArray())
            val receipt = executeRpcWithFallback(rpc)
            receiptCache[txHashHex] = receipt
            receipt
        } catch (e: Throwable) {
            null
        }
    }

    private fun <T : Any> executeRpcWithFallback(rpc: JsonRpc<T>): T {
        rpc.id = currentRpcId.incrementAndGet()

        var lastError: Throwable = Error("No URIs available")
        for (uri in uris) {
            try {
                return executeRpcOnUri(rpc, uri)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError
    }

    private fun <T : Any> executeRpcOnUri(rpc: JsonRpc<T>, uri: URI): T {
        rpc.id = currentRpcId.incrementAndGet()
        val response = service.rpcCall(uri, gson.toJson(rpc)).blockingGet()
        return rpc.parseResponse(response, gson)
    }

    private fun ByteArray.toBigInteger(): java.math.BigInteger {
        return if (isEmpty()) java.math.BigInteger.ZERO else java.math.BigInteger(1, this)
    }

    private fun ByteArray.toHexString(): String {
        return "0x" + joinToString("") { "%02x".format(it) }
    }

    private fun String.hexStringToByteArray(): ByteArray {
        val hex = removePrefix("0x")
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    data class TokenMeta(
        val name: String,
        val symbol: String,
        val decimals: Int
    )

    private interface RpcService {
        @POST
        @Headers("Content-Type: application/json", "Accept: application/json")
        fun rpcCall(@Url uri: URI, @Body jsonRpc: String): Single<RpcResponse>
    }

    companion object {
        private const val INITIAL_CHUNK_SIZE = 50_000L
        private const val MIN_CHUNK_SIZE = 4999L

        // Transfer(address indexed from, address indexed to, uint256 value)
        private const val TRANSFER_EVENT_SIGNATURE =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

        // Method IDs
        private val NAME_METHOD_ID = "06fdde03".hexToByteArray()     // name()
        private val SYMBOL_METHOD_ID = "95d89b41".hexToByteArray()   // symbol()
        private val DECIMALS_METHOD_ID = "313ce567".hexToByteArray() // decimals()

        private fun String.hexToByteArray(): ByteArray {
            return ByteArray(length / 2) { i ->
                substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /**
         * Extract chainId from EIP-155 signature v value.
         * Formula: chainId = (v - 35) / 2
         * Returns null if pre-EIP155 (v=27/28) or invalid.
         */
        private fun extractChainIdFromV(vHex: String?): Long? {
            val v = vHex?.removePrefix("0x")?.toLongOrNull(16) ?: return null
            return if (v >= 37) (v - 35) / 2 else null
        }
    }
}
