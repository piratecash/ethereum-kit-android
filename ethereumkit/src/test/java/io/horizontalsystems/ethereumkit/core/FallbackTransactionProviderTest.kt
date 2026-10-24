package io.horizontalsystems.ethereumkit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.ProviderTransaction
import io.horizontalsystems.ethereumkit.network.EtherscanService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.math.BigInteger

class FallbackTransactionProviderTest {

    private val primary = mockk<ITransactionProvider>()
    private val fallback = mockk<ITransactionProvider>()

    @Before
    fun setUp() {
        // The module's unit tests have no Android stubs, so a real log writer would hit android.util.Log.
        Logger.setLogWriters(emptyList())
    }

    @Test
    fun getTransactions_primarySucceeds_fallbackNotCalled() {
        every { primary.getTransactions(START_BLOCK) } returns Single.just(emptyList())
        val provider = provider()

        provider.getTransactions(START_BLOCK).blockingGet()

        verify(exactly = 0) { fallback.getTransactions(any()) }
        assertEquals(PRIMARY_HOST, provider.statusInfo["Last history source"])
        assertNull(provider.statusInfo["Last explorer error"])
    }

    @Test
    fun getTransactions_primaryFails_returnsFallbackResult() {
        every { primary.getTransactions(START_BLOCK) } returns Single.error(RuntimeException(PRIMARY_ERROR))
        every { fallback.getTransactions(START_BLOCK) } returns Single.just(listOf(transaction()))
        val provider = provider()

        val result = provider.getTransactions(START_BLOCK).blockingGet()

        assertEquals(1, result.size)
        assertEquals(FALLBACK_HOST, provider.statusInfo["Last history source"])
        assertTrue(provider.statusInfo.getValue("Last explorer error").toString().contains(PRIMARY_HOST))
    }

    @Test
    fun getTransactions_allSourcesFail_propagatesLastError() {
        every { primary.getTransactions(START_BLOCK) } returns Single.error(RuntimeException(PRIMARY_ERROR))
        every { fallback.getTransactions(START_BLOCK) } returns Single.error(IllegalStateException(FALLBACK_ERROR))
        val provider = provider()

        val error = errorOf { provider.getTransactions(START_BLOCK).blockingGet() }

        assertEquals(FALLBACK_ERROR, error?.message)
        assertTrue(provider.statusInfo.getValue("Last explorer error").toString().contains(FALLBACK_HOST))
    }

    @Test
    fun getTransactions_sourceRefusedWithText_statusLineHasText() {
        val refusal = "Free API access is not supported for this chain"
        every { primary.getTransactions(START_BLOCK) } returns Single.error(EtherscanService.RequestError.InvalidApiKey(refusal))
        every { fallback.getTransactions(START_BLOCK) } returns Single.just(emptyList())
        val provider = provider()

        provider.getTransactions(START_BLOCK).blockingGet()

        assertTrue(provider.statusInfo.getValue("Last explorer error").toString().contains(refusal))
    }

    @Test
    fun getTransactions_ioErrorWithKeyInUrl_statusLineHasNoUrlOrKey() {
        every { primary.getTransactions(START_BLOCK) } returns Single.error(IOException("failed https://x/api?apikey=SECRET"))
        every { fallback.getTransactions(START_BLOCK) } returns Single.just(emptyList())
        val provider = provider()

        provider.getTransactions(START_BLOCK).blockingGet()

        val status = provider.statusInfo.getValue("Last explorer error").toString()
        assertFalse(status.contains("SECRET"))
        assertFalse(status.contains("apikey"))
    }

    @Test
    fun getTransactions_responseErrorWithKeyInBody_statusLineHasClassNameOnly() {
        val error = EtherscanService.RequestError.ResponseError("Unexpected response: {\"url\":\"apikey=SECRET\"}")
        every { primary.getTransactions(START_BLOCK) } returns Single.error(error)
        every { fallback.getTransactions(START_BLOCK) } returns Single.just(emptyList())
        val provider = provider()

        provider.getTransactions(START_BLOCK).blockingGet()

        val status = provider.statusInfo.getValue("Last explorer error").toString()
        assertTrue(status.contains("ResponseError"))
        assertFalse(status.contains("SECRET"))
    }

    @Test
    fun statusInfo_beforeAnyCall_listsSourcesInOrder() {
        assertEquals("$PRIMARY_HOST -> $FALLBACK_HOST", provider().statusInfo["Transactions source"])
    }

    private fun provider() = FallbackTransactionProvider(
        listOf(
            FallbackTransactionProvider.Source(PRIMARY_HOST, primary),
            FallbackTransactionProvider.Source(FALLBACK_HOST, fallback)
        ),
        Logger.withTag("test")
    )

    private fun errorOf(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }

    private fun transaction() = ProviderTransaction(
        blockNumber = 1,
        timestamp = 1,
        hash = ByteArray(32),
        nonce = 0,
        transactionIndex = 0,
        from = Address("0x0000000000000000000000000000000000000001"),
        to = null,
        value = BigInteger.ONE,
        gasLimit = 21_000,
        gasPrice = 1,
        input = ByteArray(0)
    )

    companion object {
        private const val PRIMARY_HOST = "primary.example"
        private const val FALLBACK_HOST = "fallback.example"
        private const val PRIMARY_ERROR = "primary down"
        private const val FALLBACK_ERROR = "fallback down"
        private const val START_BLOCK = 100L
    }
}
