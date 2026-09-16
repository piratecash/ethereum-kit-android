package io.horizontalsystems.ethereumkit.core.storage

import io.horizontalsystems.ethereumkit.models.Eip20SyncState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Eip20StorageTest {

    private val syncStateDao = mockk<Eip20SyncStateDao>(relaxed = true)
    private val storage = Eip20Storage(mockk(relaxed = true), syncStateDao)

    @Test
    fun clearForeignSyncState_aboveMargin_deletesRow() = runBlocking {
        givenSyncState(lastScannedBlock = FOREIGN_CURSOR, historicalMinScannedBlock = FOREIGN_CURSOR)

        assertTrue(storage.clearForeignSyncState(CHAIN_HEAD, MARGIN))

        verify(exactly = 1) { syncStateDao.delete(CURSOR_KEY) }
    }

    @Test
    fun clearForeignSyncState_onlyHistoricalAboveMargin_deletesRow() = runBlocking {
        givenSyncState(lastScannedBlock = CHAIN_HEAD, historicalMinScannedBlock = FOREIGN_CURSOR)

        assertTrue(storage.clearForeignSyncState(CHAIN_HEAD, MARGIN))

        verify(exactly = 1) { syncStateDao.delete(CURSOR_KEY) }
    }

    @Test
    fun clearForeignSyncState_withinMargin_keepsRow() = runBlocking {
        givenSyncState(
            lastScannedBlock = CHAIN_HEAD + MARGIN,
            historicalMinScannedBlock = CHAIN_HEAD + MARGIN
        )

        assertFalse(storage.clearForeignSyncState(CHAIN_HEAD, MARGIN))

        verify(exactly = 0) { syncStateDao.delete(any()) }
    }

    @Test
    fun clearForeignSyncState_noRow_keepsRow() = runBlocking {
        every { syncStateDao.get(CURSOR_KEY) } returns null

        assertFalse(storage.clearForeignSyncState(CHAIN_HEAD, MARGIN))

        verify(exactly = 0) { syncStateDao.delete(any()) }
    }

    private fun givenSyncState(lastScannedBlock: Long?, historicalMinScannedBlock: Long?) {
        every { syncStateDao.get(CURSOR_KEY) } returns Eip20SyncState(
            contractAddress = CURSOR_KEY,
            lastScannedBlock = lastScannedBlock,
            historicalMinScannedBlock = historicalMinScannedBlock
        )
    }

    companion object {
        private const val CURSOR_KEY = "0x0000000000000000000000000000000000000000"
        private const val CHAIN_HEAD = 60_000_000L
        private const val MARGIN = 1_000_000L
        private const val FOREIGN_CURSOR = 121_100_220L
    }
}
