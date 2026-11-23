package io.horizontalsystems.erc20kit.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.core.storage.Eip20Database
import io.horizontalsystems.ethereumkit.core.storage.Eip20Storage
import io.horizontalsystems.ethereumkit.models.Eip20Event
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
class TransactionSaverTest {

    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), Eip20Database::class.java
    ).build()
    private val storage = Eip20Storage(database)
    private val saver = TransactionSaver(storage)

    @After
    fun tearDown() = database.close()

    @Test
    fun handle_replaceExistingFalse_keepsStoredEventsAndZeroValueEvent() = runTest {
        storage.save(listOf(storedEvent(BigInteger.ZERO), storedEvent(BigInteger.ONE)))

        saver.handle(
            listOf(rpcTransfer(BigInteger.ZERO), rpcTransfer(BigInteger.ONE)),
            replaceExisting = false
        )

        val events = storage.getEvents().sortedBy { it.value }
        assertEquals(listOf(BigInteger.ZERO, BigInteger.ONE), events.map { it.value })
        assertEquals(listOf(6, 6), events.map { it.tokenDecimal })
        assertEquals(listOf("Explorer", "Explorer"), events.map { it.tokenName })
    }

    private fun storedEvent(value: BigInteger) = Eip20Event(
        hash = testHash,
        blockNumber = 100L,
        contractAddress = tokenContractAddress,
        from = transferSenderAddress,
        to = transferRecipientAddress,
        value = value,
        tokenName = "Explorer",
        tokenSymbol = "EXP",
        tokenDecimal = 6
    )

    private fun rpcTransfer(value: BigInteger) =
        createTokenTransaction(100L).copy(value = value, tokenName = "Rpc", tokenSymbol = "RPC", tokenDecimal = 18)
}
