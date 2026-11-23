package io.horizontalsystems.ethereumkit.ens

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.github.adraffy.ens.ENSNormalize
import io.horizontalsystems.ethereumkit.api.jsonrpc.BlockNumberJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.GetBlockByNumberJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.JsonRpc
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.RpcApiProviderFactory
import io.horizontalsystems.ethereumkit.core.strictHexToByteArray
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.DefaultBlockParameter
import io.horizontalsystems.ethereumkit.models.RpcSource
import io.horizontalsystems.ethereumkit.network.SharedHttpClient
import io.reactivex.Single
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException

/** Resolves ENS names through the Universal Resolver, following EIP-3668 offchain lookups. */
class EnsResolver internal constructor(
    private val rpcSource: RpcSource,
    private val gatewayClient: OkHttpClient
) {
    constructor(rpcSource: RpcSource) : this(rpcSource, newGatewayClient())

    /** Mainnet address of [name]; every failure is emitted as [EnsResolutionError] through `onError`. */
    fun resolve(name: String): Single<Address> = Single
        .fromCallable { resolveNormalized(normalize(name)) }
        .onErrorResumeNext { error: Throwable ->
            Single.error(error as? EnsResolutionError ?: EnsResolutionError.Rpc(error))
        }

    private fun normalize(name: String): String {
        if (!name.contains('.')) throw EnsResolutionError.InvalidName()
        return try {
            ENSNormalize.ENSIP15.normalize(name)
        } catch (e: RuntimeException) {
            throw EnsResolutionError.InvalidName()
        }
    }

    private fun resolveNormalized(name: String): Address {
        // Built first: dnsEncode is the last local name check and must reject before any RPC call.
        var calldata = EnsAbi.resolveCall(name)
        val block = DefaultBlockParameter.BlockNumber(syncedHeadNumber())
        var lookups = 0
        while (true) {
            val result = call(UNIVERSAL_RESOLVER, calldata, block)
            val error = result.exceptionOrNull() ?: return decodeAddress(result.getOrThrow())
            val lookup = offchainLookup(error)
            if (++lookups > LOOKUP_LIMIT) throw EnsResolutionError.LookupLimit()
            // EIP-3668: the callback goes to the sender, which must be the contract that was called.
            if (lookup.request.sender != UNIVERSAL_RESOLVER) throw EnsResolutionError.InvalidResponse()
            calldata = lookup.callbackFunction + EnsAbi.encodeBytes(gatewayResponse(lookup.request), lookup.extraData)
        }
    }

    // A lagging node would answer with outdated records, i.e. possibly a former owner's address.
    private fun syncedHeadNumber(): Long {
        val provider = RpcApiProviderFactory.nodeApiProvider(rpcSource)
        val number = provider.single(BlockNumberJsonRpc()).blockingGet()
        val head = provider.single(GetBlockByNumberJsonRpc(number)).blockingGet()
        if (head.number != number) throw EnsResolutionError.InvalidResponse()
        // No arithmetic on the node's timestamp: a huge value would overflow into "fresh".
        val nowSeconds = System.currentTimeMillis() / 1000
        if (head.timestamp !in (nowSeconds - SYNC_THRESHOLD_SECONDS)..(nowSeconds + SYNC_THRESHOLD_SECONDS)) {
            throw EnsResolutionError.NodeNotSynced()
        }
        return number
    }

    private fun call(to: Address, data: ByteArray, block: DefaultBlockParameter): Result<ByteArray> =
        EthereumKit.call(rpcSource, to, data, block)
            .map { Result.success(it) }
            .onErrorReturn { Result.failure(it) }
            .blockingGet()

    private fun decodeAddress(returned: ByteArray): Address {
        val result = AbiReader(returned).bytes(0)
        if (result.size != 32) throw EnsResolutionError.InvalidResponse()
        val address = AbiReader(result).address(0)
        if (address == ZERO_ADDRESS) throw EnsResolutionError.NotFound()
        return address
    }

    private fun offchainLookup(error: Throwable): OffchainLookup {
        if (error !is JsonRpc.ResponseError.RpcError) throw EnsResolutionError.Rpc(error)
        val revertData = (error.error.data as? JsonPrimitive)?.takeIf { it.isString }?.asString
        if (revertData == null || !revertData.startsWith(EnsAbi.OFFCHAIN_LOOKUP_SELECTOR, ignoreCase = true)) {
            throw EnsResolutionError.NotFound()
        }
        val revert = hexOrNull(revertData) ?: throw EnsResolutionError.InvalidResponse()
        val reader = AbiReader(revert.copyOfRange(4, revert.size))
        return OffchainLookup(
            request = gatewayRequest(reader),
            callbackFunction = reader.bytes4(96),
            extraData = reader.bytes(128)
        )
    }

    // OffchainLookup and a batch gateway query share the (address, string[], bytes) head layout.
    private fun gatewayRequest(reader: AbiReader) =
        GatewayRequest(sender = reader.address(0), urls = reader.stringArray(32), data = reader.bytes(64))

    // The local batch gateway replaces the hosted one, which is rate-limited by some resolvers' gateways.
    private fun gatewayResponse(request: GatewayRequest): ByteArray =
        if (LOCAL_BATCH_GATEWAY in request.urls) localBatchGateway(request.data) else fetch(request)

    private fun localBatchGateway(query: ByteArray): ByteArray {
        if (!query.toHexString().startsWith(EnsAbi.BATCH_QUERY_SELECTOR)) throw EnsResolutionError.InvalidResponse()
        val requests = AbiReader(query.copyOfRange(4, query.size)).array(0, ::gatewayRequest)
        if (requests.size > MAX_BATCH_REQUESTS) throw EnsResolutionError.InvalidResponse()
        val results = requests.map { request ->
            try {
                false to fetch(request)
            } catch (e: EnsResolutionError.Gateway) {
                true to e.revertData()
            }
        }
        return EnsAbi.encodeBatchResult(results.map { it.first }, results.map { it.second })
    }

    private fun EnsResolutionError.Gateway.revertData(): ByteArray =
        status?.let { EnsAbi.httpError(it, "HTTP request failed") } ?: EnsAbi.error("Gateway request failed")

    // Only https URLs are tried, so an inner `x-batch-gateway:true` is skipped rather than handled locally again.
    private fun fetch(request: GatewayRequest): ByteArray {
        val sender = request.sender.hex
        val data = request.data.toHexString()
        return request.urls
            .filter { it.startsWith("https://") }
            .firstNotNullOfOrNull { fetchUrl(it, sender, data) }
            ?: throw EnsResolutionError.Gateway()
    }

    /** `null` means this URL failed and the next one is tried; a 4xx stops the whole request. */
    private fun fetchUrl(template: String, sender: String, data: String): ByteArray? {
        val url = template.replace("{sender}", sender).replace("{data}", data).toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(url).apply {
            if (!template.contains("{data}")) post(postBody(sender, data))
        }.build()
        return try {
            gatewayClient.newCall(request).execute().use { response ->
                if (response.code in 400..499) throw EnsResolutionError.Gateway(response.code)
                response.body?.takeIf { response.isSuccessful }?.let(::gatewayData)
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun postBody(sender: String, data: String) = JsonObject().apply {
        addProperty("data", data)
        addProperty("sender", sender)
    }.toString().toRequestBody(JSON)

    private fun gatewayData(body: ResponseBody): ByteArray? {
        val source = body.source()
        if (source.request(MAX_GATEWAY_RESPONSE_BYTES + 1L)) return null
        val json = try {
            JsonParser.parseString(source.buffer.readUtf8()).asJsonObject
        } catch (e: RuntimeException) {
            return null
        }
        val data = (json.get("data") as? JsonPrimitive)?.takeIf { it.isString }?.asString ?: return null
        return hexOrNull(data)
    }

    private fun hexOrNull(hex: String): ByteArray? {
        if (hex == "0x") return ByteArray(0)
        if (!hex.startsWith("0x")) return null
        return try {
            hex.strictHexToByteArray()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private class GatewayRequest(val sender: Address, val urls: List<String>, val data: ByteArray)

    private class OffchainLookup(val request: GatewayRequest, val callbackFunction: ByteArray, val extraData: ByteArray)

    companion object {
        // Lowercase: a checksummed literal would run keccak during class init, before EthereumKit.init().
        val UNIVERSAL_RESOLVER = Address("0xeeeeeeee14d718c2b47d9923deab1335e144eeee")
        const val LOOKUP_LIMIT = 4
        const val MAX_GATEWAY_RESPONSE_BYTES = 64 * 1024
        const val MAX_BATCH_REQUESTS = 10
        const val SYNC_THRESHOLD_SECONDS = 180L
        const val LOCAL_BATCH_GATEWAY = "x-batch-gateway:true"

        private val ZERO_ADDRESS = Address(ByteArray(20))
        private val JSON = "application/json".toMediaType()

        // Not following https -> http redirects keeps every gateway request on https.
        internal fun newGatewayClient(): OkHttpClient = SharedHttpClient.newClient { followSslRedirects(false) }
    }
}

sealed class EnsResolutionError : Throwable() {
    class InvalidName : EnsResolutionError()
    class NotFound : EnsResolutionError()
    class InvalidResponse : EnsResolutionError()
    class LookupLimit : EnsResolutionError()
    class NodeNotSynced : EnsResolutionError()
    /** [status] is set when a gateway answered 4xx. */
    class Gateway(val status: Int? = null) : EnsResolutionError()
    class Rpc(override val cause: Throwable) : EnsResolutionError()
}
