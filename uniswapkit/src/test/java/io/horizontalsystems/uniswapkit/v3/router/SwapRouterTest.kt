package io.horizontalsystems.uniswapkit.v3.router

import io.horizontalsystems.ethereumkit.contracts.ContractMethod
import io.horizontalsystems.ethereumkit.contracts.ContractMethodFactories
import io.horizontalsystems.ethereumkit.contracts.ContractMethodFactory
import io.horizontalsystems.ethereumkit.contracts.ContractMethodHelper
import io.horizontalsystems.ethereumkit.crypto.InternalBouncyCastleProvider
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.horizontalsystems.uniswapkit.models.DexType
import io.horizontalsystems.uniswapkit.models.Token
import io.horizontalsystems.uniswapkit.models.TradeOptions
import io.horizontalsystems.uniswapkit.models.TradeType
import io.horizontalsystems.uniswapkit.v3.FeeAmount
import io.horizontalsystems.uniswapkit.v3.SwapPath
import io.horizontalsystems.uniswapkit.v3.SwapPathItem
import io.horizontalsystems.uniswapkit.v3.TradeDataV3
import io.horizontalsystems.uniswapkit.v3.quoter.BestTrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.security.Security

class SwapRouterTest {

    @Test
    fun transactionData_exactInWithNativeInput_preservesQuotedInputFunding() {
        val result = transactionData(TradeType.ExactIn, nativeToken, outputToken)

        assertEquals(result.tradeData.amountIn, result.transactionData.value)
        assertTrue(decode(result.transactionData) is ExactInputSingleMethod)
    }

    @Test
    fun transactionData_exactOutWithNativeInput_fundsMaximumAndRefundsRemainder() {
        val result = transactionData(TradeType.ExactOut, nativeToken, outputToken)
        val methods = decodeMulticall(result.transactionData)

        assertTrue(result.tradeData.amountInMaximum > result.tradeData.amountIn)
        assertEquals(result.tradeData.amountInMaximum, result.transactionData.value)
        assertEquals(2, methods.size)
        assertTrue(methods[0] is ExactOutputSingleMethod)
        assertTrue(methods[1] is RefundETHMethod)
        assertEquals(
            result.tradeData.amountInMaximum,
            (methods[0] as ExactOutputSingleMethod).amountInMaximum,
        )
    }

    @Test
    fun transactionData_exactInWithNativeOutput_preservesSlippageMinimum() {
        val result = transactionData(TradeType.ExactIn, inputToken, nativeToken)
        val methods = decodeMulticall(result.transactionData)
        val unwrap = methods[1] as UnwrapWETH9Method

        assertEquals(BigInteger.ZERO, result.transactionData.value)
        assertEquals(2, methods.size)
        assertTrue(methods[0] is ExactInputSingleMethod)
        assertEquals(result.tradeData.amountOutMinimum, unwrap.amountMinimum)
        assertEquals(recipient, unwrap.recipient)
    }

    @Test
    fun transactionData_exactOutWithNativeOutput_unwrapsRequestedOutput() {
        val result = transactionData(TradeType.ExactOut, inputToken, nativeToken)
        val methods = decodeMulticall(result.transactionData)
        val unwrap = methods[1] as UnwrapWETH9Method

        assertTrue(result.tradeData.amountOutMinimum < result.tradeData.amountOut)
        assertEquals(BigInteger.ZERO, result.transactionData.value)
        assertEquals(2, methods.size)
        assertTrue(methods[0] is ExactOutputSingleMethod)
        assertEquals(result.tradeData.amountOut, unwrap.amountMinimum)
        assertEquals(recipient, unwrap.recipient)
    }

    @Test
    fun transactionData_exactInWithErc20Pair_preservesDirectSwap() {
        val result = transactionData(TradeType.ExactIn, inputToken, outputToken)

        assertEquals(BigInteger.ZERO, result.transactionData.value)
        assertTrue(decode(result.transactionData) is ExactInputSingleMethod)
    }

    @Test
    fun transactionData_exactOutWithErc20Pair_preservesDirectSwap() {
        val result = transactionData(TradeType.ExactOut, inputToken, outputToken)
        val method = decode(result.transactionData)

        assertEquals(BigInteger.ZERO, result.transactionData.value)
        assertTrue(method is ExactOutputSingleMethod)
        assertEquals(
            result.tradeData.amountInMaximum,
            (method as ExactOutputSingleMethod).amountInMaximum,
        )
    }

    private fun transactionData(
        tradeType: TradeType,
        tokenIn: Token,
        tokenOut: Token,
    ): TransactionResult {
        val trade = BestTrade(
            tradeType = tradeType,
            swapPath = SwapPath(
                listOf(
                    SwapPathItem(
                        token1 = tokenIn.address,
                        token2 = tokenOut.address,
                        fee = FeeAmount.MEDIUM_UNISWAP,
                    )
                )
            ),
            amountIn = BigInteger("1000"),
            amountOut = BigInteger("2000"),
            tokenIn = tokenIn,
            tokenOut = tokenOut,
        )
        val tradeData = TradeDataV3(
            trade = trade,
            options = TradeOptions(allowedSlippagePercent = BigDecimal("10")),
            priceImpact = null,
        )
        val transactionData = SwapRouter(DexType.Uniswap).transactionData(
            receiveAddress = recipient,
            chain = Chain.Ethereum,
            tradeData = tradeData,
        )
        return TransactionResult(tradeData, transactionData)
    }

    private fun decode(transactionData: TransactionData): ContractMethod =
        requireNotNull(methodFactories.createMethodFromInput(transactionData.input))

    private fun decodeMulticall(transactionData: TransactionData): List<ContractMethod> =
        requireNotNull(decode(transactionData) as? MulticallMethod).methods

    private data class TransactionResult(
        val tradeData: TradeDataV3,
        val transactionData: TransactionData,
    )

    private class RefundETHMethodFactory : ContractMethodFactory {
        override val methodId = ContractMethodHelper.getMethodId("refundETH()")

        override fun createMethod(inputArguments: ByteArray): ContractMethod = RefundETHMethod()
    }

    companion object {
        init {
            Security.addProvider(InternalBouncyCastleProvider.getInstance())
        }

        private val recipient = Address("0x0000000000000000000000000000000000000001")
        private val wethAddress = Address("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2")
        private val inputToken = Token.Erc20(
            Address("0x6B175474E89094C44Da98b954EedeAC495271d0F"),
            18,
        )
        private val outputToken = Token.Erc20(
            Address("0xA0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"),
            6,
        )
        private val nativeToken = Token.Ether(wethAddress)
        private val methodFactories = object : ContractMethodFactories() {
            init {
                registerMethodFactories(
                    listOf(
                        ExactInputSingleMethod.Factory(),
                        ExactOutputSingleMethod.Factory(),
                        UnwrapWETH9Method.Factory(),
                        RefundETHMethodFactory(),
                        MulticallMethod.Factory(this),
                    )
                )
            }
        }
    }
}
