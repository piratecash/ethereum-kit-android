package io.horizontalsystems.ethereumkit.ens

import io.horizontalsystems.ethereumkit.contracts.ContractMethodHelper
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.core.toRawHexString
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint16
import org.web3j.abi.datatypes.Address as AbiAddress

class EnsAbiTest {

    @Test
    fun namehash_matchesEnsReferenceVectors() {
        assertEquals("0x" + "00".repeat(32), EnsAbi.namehash("").toHexString())
        assertEquals(
            "0x93cdeb708b7545dc668eb9280176169d1c33cfd8ed6f04690a0bcc88a93fc4ae",
            EnsAbi.namehash("eth").toHexString()
        )
        assertEquals(
            "0xde9b09fd7c5f901e23a3f19fecc54828e9c848539801e86591bd9801b019f84f",
            EnsAbi.namehash("foo.eth").toHexString()
        )
    }

    @Test
    fun dnsEncode_vitalikEth_returnsLengthPrefixedLabels() {
        assertEquals(VITALIK_DNS, EnsAbi.dnsEncode("vitalik.eth").toHexString())
    }

    @Test
    fun encodeResolveCall_matchesReferenceCalldata() {
        val addrCall = "0x3b3b57de" + VITALIK_NAMEHASH
        val reference = FunctionEncoder.encode(
            Function(
                "resolve",
                listOf(DynamicBytes(VITALIK_DNS.hexStringToByteArray()), DynamicBytes(addrCall.hexStringToByteArray())),
                emptyList()
            )
        )

        assertEquals(reference, EnsAbi.resolveCall("vitalik.eth").toHexString())
    }

    @Test
    fun encodeBytes_variousLengths_matchesReferenceEncoder() {
        val values = listOf(0, 1, 31, 32, 33, 64).map { size -> ByteArray(size) { (it + 1).toByte() } }
        val reference = FunctionEncoder.encodeConstructor(values.map { DynamicBytes(it) })

        assertEquals(reference, EnsAbi.encodeBytes(*values.toTypedArray()).toRawHexString())
    }

    @Test
    fun encodeBatchResult_matchesReferenceEncoder() {
        val responses = listOf(byteArrayOf(1, 2, 3), ByteArray(0), ByteArray(40) { 7 })
        val reference = FunctionEncoder.encodeConstructor(
            listOf(
                DynamicArray(Bool::class.java, listOf(Bool(false), Bool(true), Bool(false))),
                DynamicArray(DynamicBytes::class.java, responses.map { DynamicBytes(it) })
            )
        )

        assertEquals(reference, EnsAbi.encodeBatchResult(listOf(false, true, false), responses).toRawHexString())
    }

    @Test
    fun encodeErrors_matchReferenceEncoder() {
        val httpError = FunctionEncoder.encodeConstructor(listOf(Uint16(404), Utf8String("Not Found")))
        val error = FunctionEncoder.encodeConstructor(listOf(Utf8String("Gateway request failed")))

        assertEquals(EnsAbi.HTTP_ERROR_SELECTOR + httpError, EnsAbi.httpError(404, "Not Found").toHexString())
        assertEquals(EnsAbi.ERROR_SELECTOR + error, EnsAbi.error("Gateway request failed").toHexString())
    }

    @Test
    fun array_canonicalBatchQuery_decodesEveryElementWithinBudget() {
        val requests = listOf(
            listOf("https://a.test/{sender}/{data}.json", "https://b.test/") to ByteArray(70) { 1 },
            emptyList<String>() to ByteArray(0),
            listOf("https://c.test/" + "x".repeat(100)) to byteArrayOf(2, 3)
        )
        val structs = requests.map { (urls, data) ->
            DynamicStruct(AbiAddress(SENDER), DynamicArray(Utf8String::class.java, urls.map(::Utf8String)), DynamicBytes(data))
        }
        val encoded = FunctionEncoder.encodeConstructor(listOf(DynamicArray(DynamicStruct::class.java, structs)))

        val decoded = AbiReader(encoded.hexStringToByteArray()).array(0) {
            Triple(it.address(0).hex, it.stringArray(32), it.bytes(64).toList())
        }

        assertEquals(requests.map { (urls, data) -> Triple(SENDER, urls, data.toList()) }, decoded)
    }

    @Test
    fun selectors_matchKeccakOfSignatures() {
        assertEquals(EnsAbi.RESOLVE_SELECTOR, selector("resolve(bytes,bytes)"))
        assertEquals(EnsAbi.ADDR_SELECTOR, selector("addr(bytes32)"))
        assertEquals(EnsAbi.OFFCHAIN_LOOKUP_SELECTOR, selector("OffchainLookup(address,string[],bytes,bytes4,bytes)"))
        assertEquals(EnsAbi.BATCH_QUERY_SELECTOR, selector("query((address,string[],bytes)[])"))
        assertEquals(EnsAbi.HTTP_ERROR_SELECTOR, selector("HttpError(uint16,string)"))
        assertEquals(EnsAbi.ERROR_SELECTOR, selector("Error(string)"))
        assertEquals("0xeEeEEEeE14D718C2B47D9923Deab1335E144EeEe", EnsResolver.UNIVERSAL_RESOLVER.eip55)
    }

    private fun selector(signature: String) = ContractMethodHelper.getMethodId(signature).toHexString()

    companion object {
        @JvmStatic
        @BeforeClass
        fun beforeClass() = EthereumKit.init()

        const val SENDER = "0x1234567890123456789012345678901234567890"
        const val VITALIK_DNS = "0x07766974616c696b0365746800"
        const val VITALIK_NAMEHASH = "ee6c4522aab0003e8d14cd40a6af439055fd2577951148c14b6cea9a53475835"
    }
}
