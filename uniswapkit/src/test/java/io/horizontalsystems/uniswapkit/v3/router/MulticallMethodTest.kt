package io.horizontalsystems.uniswapkit.v3.router

import io.horizontalsystems.ethereumkit.contracts.BytesArray
import io.horizontalsystems.ethereumkit.contracts.ContractMethodHelper
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.core.toRawHexString
import io.horizontalsystems.ethereumkit.crypto.InternalBouncyCastleProvider
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.uniswapkit.v3.contract.UniswapV3ContractMethodFactories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.Security

class MulticallMethodTest {

    @Test
    fun decodeABI_realSwapRouterMulticall_returnsElements() {
        val decoded = ContractMethodHelper.decodeABI(realInput.copyOfRange(4, realInput.size), listOf(BytesArray::class))
        val elements = (decoded.single() as BytesArray).array

        assertEquals(listOf(260, 68), elements.map { it.size })
        assertEquals(listOf("414bf389", "49404b7c"), elements.map { it.copyOf(4).toRawHexString() })
    }

    @Test
    fun createMethodFromInput_realSwapRouterMulticall_decodesKnownInnerCalls() {
        val method = UniswapV3ContractMethodFactories.createMethodFromInput(realInput) as MulticallMethod
        val unwrap = method.methods.single() as UnwrapWETH9Method

        assertEquals(BigInteger("1651155089df9e", 16), unwrap.amountMinimum)
        assertEquals(Address("0x61384196310fc3ccca28519de129d535b3d52381"), unwrap.recipient)
    }

    @Test
    fun createMethodFromInput_nestedMulticall_skipsNestedElement() {
        val unwrap = UnwrapWETH9Method(BigInteger.TEN, Address("0x0000000000000000000000000000000000000001"))
        val input = MulticallMethod(listOf(unwrap, MulticallMethod(listOf(unwrap)))).encodedABI()

        val method = UniswapV3ContractMethodFactories.createMethodFromInput(input) as MulticallMethod

        assertEquals(listOf(UnwrapWETH9Method::class), method.methods.map { it::class })
    }

    @Test
    fun createMethodFromInput_malformedMulticall_returnsNull() {
        val sharedTail = word(0x20) + word(8) + (1..8).flatMap { word(0x100).toList() }.toByteArray() +
            word(0x80) + ByteArray(0x80)
        val cases = mapOf(
            "truncated" to realInput.copyOf(realInput.size - 40),
            "length 2^256-1" to multicall(word(0x20) + word(1) + word(0x20) + ByteArray(32) { -1 }),
            "length 2^32" to multicall(word(0x20) + word(1) + word(0x20) + word(BigInteger.ONE.shiftLeft(32))),
            "count Int.MAX_VALUE" to multicall(word(0x20) + word(Int.MAX_VALUE) + word(0x20)),
            "offset outside data" to multicall(word(0x20) + word(1) + word(0x1000)),
            "shared tail" to multicall(sharedTail),
        )

        cases.forEach { (name, input) ->
            assertNull(name, UniswapV3ContractMethodFactories.createMethodFromInput(input))
        }
    }

    private fun multicall(arguments: ByteArray) = "ac9650d8".hexStringToByteArray() + arguments

    private fun word(value: Int) = word(value.toBigInteger())

    private fun word(value: BigInteger): ByteArray =
        value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

    companion object {
        init {
            Security.addProvider(InternalBouncyCastleProvider.getInstance())
        }

        // Mainnet tx 0xf905c871a13779f2d4867cf7a66232c6582afb90cd5a5e9d155b1eebed660d48 to SwapRouter
        private val realInput = (
            "ac9650d8" +
                "0000000000000000000000000000000000000000000000000000000000000020" +
                "0000000000000000000000000000000000000000000000000000000000000002" +
                "0000000000000000000000000000000000000000000000000000000000000040" +
                "0000000000000000000000000000000000000000000000000000000000000180" +
                "0000000000000000000000000000000000000000000000000000000000000104" +
                "414bf389" +
                "0000000000000000000000001c13522ca36e5e13c591c0803083d5bb9282fe48" +
                "000000000000000000000000c02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" +
                "0000000000000000000000000000000000000000000000000000000000002710" +
                "000000000000000000000000e592427a0aece92de3edee1f18e0157c05861564" +
                "000000000000000000000000000000000000000000000000000000006ad22255" +
                "0000000000000000000000000000000000000000000000c03bf30eff7133275c" +
                "000000000000000000000000000000000000000000000000001651155089df9e" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000044" +
                "49404b7c" +
                "000000000000000000000000000000000000000000000000001651155089df9e" +
                "00000000000000000000000061384196310fc3ccca28519de129d535b3d52381" +
                "00000000000000000000000000000000000000000000000000000000"
            ).hexStringToByteArray()
    }
}
