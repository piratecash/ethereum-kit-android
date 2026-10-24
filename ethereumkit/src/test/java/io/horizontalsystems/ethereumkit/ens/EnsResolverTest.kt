package io.horizontalsystems.ethereumkit.ens

import co.touchlab.kermit.Logger as KermitLogger
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.core.toRawHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.RpcSource
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes4
import org.web3j.abi.datatypes.generated.Uint16
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import org.web3j.abi.datatypes.Address as AbiAddress

class EnsResolverTest {

    private lateinit var rpc: MockWebServer
    private val node = FakeNode()
    private val gateway = FakeGateway()

    @Before
    fun setUp() {
        rpc = MockWebServer()
        rpc.dispatcher = node
        rpc.start()
        // The module's unit tests have no Android stubs, so a real log writer would hit android.util.Log.
        KermitLogger.setLogWriters(emptyList())
    }

    @After
    fun tearDown() = rpc.shutdown()

    @Test
    fun resolve_onchainName_returnsAddress() {
        returns(resolveResult(ADDRESS))

        assertEquals(Address(ADDRESS), resolver().resolve(NAME).blockingGet())
        val call = node.ethCalls.single()
        assertEquals(UR, call.to)
        assertEquals(EnsAbi.resolveCall(NAME).toHexString(), call.data)
        assertEquals(HEAD_HEX, call.block)
        assertEquals(HEAD_HEX, node.requestedBlock)
    }

    @Test
    fun resolve_headerNumberDiffersFromHead_failsInvalidResponseWithoutEthCall() {
        node.headerNumber = "0x1312d01"
        returns(resolveResult(ADDRESS))

        assertFailure(EnsResolutionError.InvalidResponse::class)
        assertEquals(0, node.ethCalls.size)
    }

    @Test
    fun resolve_timestampOverflowingMillis_failsNodeNotSyncedWithoutEthCall() {
        assertHeadRejectedAsNotSynced(0x20c49ba5e353f8L)
    }

    @Test
    fun resolve_timestampInMilliseconds_failsNodeNotSyncedWithoutEthCall() {
        assertHeadRejectedAsNotSynced(System.currentTimeMillis())
    }

    @Test
    fun resolve_staleHead_failsNodeNotSyncedWithoutEthCall() {
        assertHeadRejectedAsNotSynced(System.currentTimeMillis() / 1000 - 4 * 60)
    }

    @Test
    fun resolve_headRequestFails_failsRpcWithoutEthCall() {
        node.headFails = true
        returns(resolveResult(ADDRESS))

        assertFailure(EnsResolutionError.Rpc::class)
        assertEquals(0, node.ethCalls.size)
    }

    @Test
    fun resolve_offchainName_followsLookupAndReturnsAddress() {
        reverts(lookup(listOf("https://gw-a.test/lookup")))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        assertEquals(Address(ADDRESS), resolver().resolve(NAME).blockingGet())

        val post = gateway.requests.single()
        assertEquals("POST", post.method)
        assertEquals("https://gw-a.test/lookup", post.url)
        val body = JsonParser.parseString(post.body).asJsonObject
        assertEquals(CALL_DATA.toHexString(), body["data"].asString)
        assertEquals(UR, body["sender"].asString)

        assertEquals(listOf(HEAD_HEX, HEAD_HEX), node.ethCalls.map { it.block })
        val callback = node.ethCalls[1]
        assertEquals(UR, callback.to)
        val expectedArgs = FunctionEncoder.encodeConstructor(
            listOf(DynamicBytes(GATEWAY_DATA.hexStringToByteArray()), DynamicBytes(EXTRA_DATA))
        )
        assertEquals(CALLBACK + expectedArgs, callback.data)
    }

    @Test
    fun resolve_gatewayUrlWithDataTemplate_usesGet() {
        reverts(lookup(listOf("https://gw-a.test/{sender}/{data}.json")))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        resolver().resolve(NAME).blockingGet()

        val get = gateway.requests.single()
        assertEquals("GET", get.method)
        assertEquals("https://gw-a.test/$UR/${CALL_DATA.toHexString()}.json", get.url)
    }

    @Test
    fun resolve_zeroAddress_failsNotFound() {
        returns(resolveResult(ZERO_ADDRESS))

        assertFailure(EnsResolutionError.NotFound::class)
    }

    @Test
    fun resolve_resolverNotFoundRevert_failsNotFound() {
        reverts(RESOLVER_NOT_FOUND)
        assertFailure(EnsResolutionError.NotFound::class)

        node.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted"}}"""))
        assertFailure(EnsResolutionError.NotFound::class)
    }

    @Test
    fun resolve_lookupSenderIsNotUniversalResolver_failsInvalidResponse() {
        reverts(lookup(listOf("https://gw-a.test/lookup"), sender = ADDRESS))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        assertFailure(EnsResolutionError.InvalidResponse::class)
        assertEquals(0, gateway.requests.size)
    }

    @Test
    fun resolve_moreThanLookupLimit_failsLookupLimit() {
        node.repeatedCallReply = revertResponse(lookup(listOf("https://gw-a.test/lookup")))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        assertFailure(EnsResolutionError.LookupLimit::class)
        assertEquals(EnsResolver.LOOKUP_LIMIT + 1, node.ethCalls.size)
        assertEquals(EnsResolver.LOOKUP_LIMIT, gateway.requests.size)
    }

    @Test
    fun resolve_gateway4xx_stopsWithoutNextUrl() {
        reverts(lookup(listOf("https://gw-a.test/lookup", "https://gw-b.test/lookup")))
        gateway.reply("gw-a.test", 404, "")
        gateway.reply("gw-b.test", 200, GATEWAY_OK)

        assertFailure(EnsResolutionError.Gateway::class)
        assertEquals(listOf("gw-a.test"), gateway.hosts())
    }

    @Test
    fun resolve_gateway5xx_triesNextUrl() {
        assertNextUrlTriedAfter(500, "")
    }

    @Test
    fun resolve_onlyNonHttpsUrls_failsGateway() {
        reverts(lookup(listOf("http://gw-a.test/lookup", "ftp://gw-a.test/lookup")))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        assertFailure(EnsResolutionError.Gateway::class)
        assertEquals(0, gateway.requests.size)
    }

    @Test
    fun resolve_gatewayInvalidPayload_triesNextUrl() {
        listOf("not json", """{"data":"0x123"}""", """{"data":"abcd"}""", """{"result":"0xabcd"}""").forEach { body ->
            assertNextUrlTriedAfter(200, body)
        }
    }

    @Test
    fun resolve_gatewayOversizedBody_triesNextUrl() {
        val oversized = """{"data":"0x${"00".repeat(EnsResolver.MAX_GATEWAY_RESPONSE_BYTES / 2 + 1)}"}"""

        assertNextUrlTriedAfter(200, oversized)
    }

    @Test
    fun resolve_gatewayRedirectsToHttp_notFollowed() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val httpTarget = MockWebServer().apply { start() }
        val httpsGateway = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", httpTarget.url("/lookup")))
            start()
        }
        val client = EnsResolver.newGatewayClient().newBuilder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        reverts(lookup(listOf(httpsGateway.url("/lookup").toString())))

        try {
            assertFailure(EnsResolutionError.Gateway::class, resolver(client))
            assertEquals(1, httpsGateway.requestCount)
            assertEquals(0, httpTarget.requestCount)
        } finally {
            httpsGateway.shutdown()
            httpTarget.shutdown()
        }
    }

    @Test
    fun resolve_malformedResult_failsInvalidResponse() {
        val wellFormed = resolveResult(ADDRESS)
        listOf(
            bytesResult(ByteArray(31)),
            bytesResult(ByteArray(64)),
            bytesResult(ByteArray(32) { if (it == 0) 1 else 0 }),
            wellFormed.dropLast(64),
            "0x" + "ff".repeat(32) + wellFormed.drop(66),
            "0x"
        ).forEach { result ->
            returns(result)
            assertFailure(EnsResolutionError.InvalidResponse::class)
        }
    }

    @Test
    fun resolve_malformedOffchainLookup_failsInvalidResponse() {
        listOf(
            "0x556f1830" + "00".repeat(40),
            lookup(listOf("https://gw-a.test/lookup")).dropLast(64)
        ).forEach { revert ->
            reverts(revert)
            assertFailure(EnsResolutionError.InvalidResponse::class)
        }
    }

    @Test
    fun resolve_localBatchGateway_fetchesInnerRequestsAndEncodesResult() {
        val inner = listOf(
            listOf("https://gw-a.test/{sender}/{data}.json") to byteArrayOf(1, 2),
            listOf("https://gw-b.test/lookup") to byteArrayOf(3)
        )
        reverts(lookup(listOf("https://hosted.test/", LOCAL_BATCH), callData = batchQuery(inner)))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)
        gateway.reply("gw-b.test", 404, "")

        assertEquals(Address(ADDRESS), resolver().resolve(NAME).blockingGet())

        assertEquals(listOf("gw-a.test", "gw-b.test"), gateway.hosts())
        assertEquals("https://gw-a.test/$RESOLVER/0x0102.json", gateway.requests[0].url)
        val notFound = EnsAbi.HTTP_ERROR_SELECTOR + FunctionEncoder.encodeConstructor(
            listOf(Uint16(404), Utf8String("HTTP request failed"))
        )
        assertBatchCallback(listOf(false, true), listOf(GATEWAY_DATA, notFound))
    }

    @Test
    fun resolve_localBatchInnerBatchGatewayUrl_notHandledLocallyAgain() {
        reverts(lookup(listOf(LOCAL_BATCH), callData = batchQuery(listOf(listOf(LOCAL_BATCH) to byteArrayOf(1)))))
        returns(resolveResult(ADDRESS))

        assertEquals(Address(ADDRESS), resolver().resolve(NAME).blockingGet())

        assertEquals(0, gateway.requests.size)
        val failed = EnsAbi.ERROR_SELECTOR + FunctionEncoder.encodeConstructor(listOf(Utf8String("Gateway request failed")))
        assertBatchCallback(listOf(true), listOf(failed))
    }

    @Test
    fun resolve_localBatchAboveRequestCap_failsInvalidResponse() {
        val inner = List(EnsResolver.MAX_BATCH_REQUESTS + 1) { listOf("https://gw-a.test/lookup") to byteArrayOf(1) }
        reverts(lookup(listOf(LOCAL_BATCH), callData = batchQuery(inner)))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        assertFailure(EnsResolutionError.InvalidResponse::class)
        assertEquals(0, gateway.requests.size)
    }

    @Test
    fun resolve_localBatchCountAliasingOneRequest_failsInvalidResponseBeforeFetching() {
        val count = 10_000
        val request = FunctionEncoder.encodeConstructor(
            listOf(
                AbiAddress(RESOLVER),
                DynamicArray(Utf8String::class.java, listOf(Utf8String("https://gw-a.test/lookup"))),
                DynamicBytes(byteArrayOf(1))
            )
        )
        val aliasedOffsets = word(count * 32).repeat(count)
        val query = EnsAbi.BATCH_QUERY_SELECTOR + word(32) + word(count) + aliasedOffsets + request
        reverts(lookup(listOf(LOCAL_BATCH), callData = query.hexStringToByteArray()))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        assertFailure(EnsResolutionError.InvalidResponse::class)
        assertEquals(0, gateway.requests.size)
    }

    @Test
    fun resolve_lookupWithElevenUrls_usesFirstAnsweringUrl() {
        reverts(lookup(listOf("https://gw-a.test/lookup") + List(10) { "https://gw-b.test/$it" }))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        assertEquals(Address(ADDRESS), resolver().resolve(NAME).blockingGet())
        assertEquals(listOf("gw-a.test"), gateway.hosts())
    }

    @Test
    fun resolve_localBatchNestedAliasing_failsInvalidResponseBeforeFetching() {
        val url = "https://gw-a.test/".padEnd(1024, 'a')
        val elements = 10
        val offsets = word(elements * 32).repeat(elements)
        // Tuple head (address, urls offset, data offset), then 10 url offsets; urls and data share one tail.
        val sharedTail = 0x80 + elements * 32
        val tuple = word(0) + word(0x60) + word(sharedTail) + word(elements) + offsets +
            word(url.length) + url.toByteArray().toRawHexString()
        val query = EnsAbi.BATCH_QUERY_SELECTOR + word(32) + word(elements) + offsets + tuple
        reverts(lookup(listOf(LOCAL_BATCH), callData = query.hexStringToByteArray()))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", 200, GATEWAY_OK)

        assertFailure(EnsResolutionError.InvalidResponse::class)
        assertEquals(0, gateway.requests.size)
    }

    @Test
    fun resolve_gatewayReturnsEmptyData_callbackCarriesEmptyBytes() {
        reverts(lookup(listOf("https://gw-a.test/lookup")))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", 200, """{"data":"0x"}""")

        assertEquals(Address(ADDRESS), resolver().resolve(NAME).blockingGet())

        val expectedArgs = FunctionEncoder.encodeConstructor(listOf(DynamicBytes(ByteArray(0)), DynamicBytes(EXTRA_DATA)))
        assertEquals(CALLBACK + expectedArgs, node.ethCalls[1].data)
    }

    @Test
    fun resolve_localBatchEntryWithEmptyData_encodesEmptyResponse() {
        reverts(lookup(listOf(LOCAL_BATCH), callData = batchQuery(listOf(listOf("https://gw-a.test/lookup") to byteArrayOf(1)))))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", 200, """{"data":"0x"}""")

        assertEquals(Address(ADDRESS), resolver().resolve(NAME).blockingGet())

        assertBatchCallback(listOf(false), listOf("0x"))
    }

    @Test
    fun resolve_dotlessOrUnnormalizableName_failsInvalidNameWithoutRpcCall() {
        listOf("vitalik", "a..eth", "a".repeat(256) + ".eth").forEach { name ->
            assertFailure(EnsResolutionError.InvalidName::class, name = name)
        }
        assertEquals(0, rpc.requestCount)
    }

    // Network check, skipped unless ENS_LIVE_RPC names a mainnet RPC that returns revert data.
    @Test
    fun resolve_liveMainnet_resolvesKnownNames() {
        val rpcUrl = System.getenv("ENS_LIVE_RPC")
        assumeTrue(rpcUrl != null)
        val resolver = EnsResolver(RpcSource.Http(listOf(URI(rpcUrl)), null))

        val resolved = listOf("vitalik.eth", "ur.gtest.eth", "test.offchaindemo.eth", "jesse.base.eth", "jesse.cb.id").associateWith {
            resolver.resolve(it).blockingGet().eip55
        }

        println("ENS live: $resolved")
        assertEquals("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", resolved["vitalik.eth"])
        assertEquals("0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE", resolved["ur.gtest.eth"])
    }

    private fun assertHeadRejectedAsNotSynced(timestamp: Long) {
        node.headTimestamp = timestamp
        returns(resolveResult(ADDRESS))

        assertFailure(EnsResolutionError.NodeNotSynced::class)
        assertEquals(0, node.ethCalls.size)
    }

    private fun assertBatchCallback(failures: List<Boolean>, responses: List<String>) {
        val batchResult = FunctionEncoder.encodeConstructor(
            listOf(
                DynamicArray(Bool::class.java, failures.map(::Bool)),
                DynamicArray(DynamicBytes::class.java, responses.map { DynamicBytes(it.hexStringToByteArray()) })
            )
        )
        val expectedArgs = FunctionEncoder.encodeConstructor(
            listOf(DynamicBytes(batchResult.hexStringToByteArray()), DynamicBytes(EXTRA_DATA))
        )
        assertEquals(CALLBACK + expectedArgs, node.ethCalls[1].data)
    }

    private fun word(value: Int) = value.toString(16).padStart(64, '0')

    private fun batchQuery(requests: List<Pair<List<String>, ByteArray>>): ByteArray {
        val structs = requests.map { (urls, data) ->
            DynamicStruct(AbiAddress(RESOLVER), DynamicArray(Utf8String::class.java, urls.map(::Utf8String)), DynamicBytes(data))
        }
        val encoded = FunctionEncoder.encodeConstructor(listOf(DynamicArray(DynamicStruct::class.java, structs)))
        return (EnsAbi.BATCH_QUERY_SELECTOR + encoded).hexStringToByteArray()
    }

    private fun assertNextUrlTriedAfter(code: Int, body: String) {
        gateway.requests.clear()
        reverts(lookup(listOf("https://gw-a.test/lookup", "https://gw-b.test/lookup")))
        returns(resolveResult(ADDRESS))
        gateway.reply("gw-a.test", code, body)
        gateway.reply("gw-b.test", 200, GATEWAY_OK)

        assertEquals(Address(ADDRESS), resolver().resolve(NAME).blockingGet())
        assertEquals(listOf("gw-a.test", "gw-b.test"), gateway.hosts())
    }

    private fun assertFailure(
        expected: KClass<out EnsResolutionError>,
        resolver: EnsResolver = resolver(),
        name: String = NAME
    ) {
        assertEquals(expected, resolver.resolve(name).test().errors().single()::class)
    }

    private fun resolver(client: OkHttpClient = EnsResolver.newGatewayClient().newBuilder().addInterceptor(gateway).build()) =
        EnsResolver(RpcSource.Http(listOf(URI(rpc.url("/").toString())), null), client)

    private fun returns(data: String) = node.enqueue(result("\"$data\""))

    private fun reverts(data: String) = node.enqueue(revertResponse(data))

    private fun revertResponse(data: String) =
        MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"execution reverted","data":"$data"}}""")

    private fun resolveResult(address: String) = bytesResult(("00".repeat(12) + address.removePrefix("0x")).hexStringToByteArray())

    private fun bytesResult(result: ByteArray) =
        "0x" + FunctionEncoder.encodeConstructor(listOf(DynamicBytes(result), AbiAddress(RESOLVER)))

    private fun lookup(urls: List<String>, sender: String = UR, callData: ByteArray = CALL_DATA) =
        "0x556f1830" + FunctionEncoder.encodeConstructor(
            listOf(
                AbiAddress(sender),
                DynamicArray(Utf8String::class.java, urls.map(::Utf8String)),
                DynamicBytes(callData),
                Bytes4(CALLBACK.hexStringToByteArray()),
                DynamicBytes(EXTRA_DATA)
            )
        )

    private class EthCall(val to: String, val data: String, val block: String)

    /** Answers the head requests itself and eth_call from the queue, recording every eth_call. */
    private class FakeNode : Dispatcher() {
        val ethCalls = CopyOnWriteArrayList<EthCall>()
        private val callReplies = ConcurrentLinkedQueue<MockResponse>()
        @Volatile var repeatedCallReply: MockResponse? = null
        @Volatile var headTimestamp = System.currentTimeMillis() / 1000
        @Volatile var headerNumber = HEAD_HEX
        @Volatile var requestedBlock: String? = null
        @Volatile var headFails = false

        fun enqueue(response: MockResponse) = callReplies.add(response)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            return when (json["method"].asString) {
                "eth_blockNumber" -> if (headFails) MockResponse().setResponseCode(500) else result("\"$HEAD_HEX\"")
                "eth_getBlockByNumber" -> {
                    requestedBlock = json["params"].asJsonArray[0].asString
                    result("""{"number":"$headerNumber","timestamp":"0x${headTimestamp.toString(16)}"}""")
                }
                else -> {
                    val params = json["params"].asJsonArray
                    val call = params[0] as JsonObject
                    ethCalls += EthCall(call["to"].asString, call["data"].asString, params[1].asString)
                    repeatedCallReply ?: callReplies.poll() ?: MockResponse().setResponseCode(500)
                }
            }
        }
    }

    private class GatewayRequest(val method: String, val url: String, val body: String)

    private class FakeGateway : Interceptor {
        val requests = mutableListOf<GatewayRequest>()
        private val replies = mutableMapOf<String, Pair<Int, String>>()

        fun reply(host: String, code: Int, body: String) {
            replies[host] = code to body
        }

        fun hosts() = requests.map { URI(it.url).host }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests += GatewayRequest(request.method, request.url.toString(), body)
            val (code, replyBody) = replies.getValue(request.url.host)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(replyBody.toResponseBody())
                .build()
        }
    }

    companion object {
        private const val NAME = "vitalik.eth"
        private const val ADDRESS = "0x1234567890123456789012345678901234567890"
        private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
        private const val RESOLVER = "0x231b0ee14048e9dccd1d247744d114a4eb5e8e63"
        private const val CALLBACK = "0x12345678"
        private const val HEAD_HEX = "0x1312d00"
        private const val LOCAL_BATCH = "x-batch-gateway:true"
        private const val GATEWAY_DATA = "0xabcdef"
        private const val GATEWAY_OK = """{"data":"$GATEWAY_DATA"}"""
        private const val RESOLVER_NOT_FOUND = "0x77209fe8" + "0000000000000000000000000000000000000000000000000000000000000020"
        private const val UR = "0xeeeeeeee14d718c2b47d9923deab1335e144eeee"
        private val CALL_DATA = byteArrayOf(1, 2, 3)
        private val EXTRA_DATA = byteArrayOf(9, 8)

        @JvmStatic
        @BeforeClass
        fun beforeClass() = EthereumKit.init()

        private fun result(json: String) = MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":$json}""")
    }
}
