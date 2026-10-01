package io.horizontalsystems.ethereumkit.core.storage

import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.Eip20SyncState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Eip20Storage(
    private val erc20EventDao: Eip20EventDao,
    private val syncStateDao: Eip20SyncStateDao
) : IEip20Storage {

    constructor(database: Eip20Database) : this(database.eip20EventDao(), database.eip20SyncStateDao())

    private val syncDataMutex = Mutex()

    override suspend fun getLastEvent(): Eip20Event? =
        erc20EventDao.getLastEip20Event()

    override suspend fun getEarliestEip20Event(): Eip20Event? =
        erc20EventDao.getEarliestEip20Event()

    override suspend fun save(events: List<Eip20Event>) {
        erc20EventDao.insertEip20Events(events)
    }

    override suspend fun getEvents(): List<Eip20Event> =
        erc20EventDao.getEip20Events()

    override suspend fun getEventsByHashes(hashes: List<ByteArray>): List<Eip20Event> =
        erc20EventDao.getEip20EventsByHashes(hashes)

    override suspend fun deleteZeroValueDuplicate(
        hash: ByteArray,
        contractAddress: Address,
        from: Address,
        to: Address
    ) {
        erc20EventDao.deleteZeroValueDuplicate(hash, contractAddress.raw, from.raw, to.raw)
    }

    override suspend fun getLastScannedBlock(): Long? =
        syncStateDao.get(CURSOR_KEY)?.lastScannedBlock

    override suspend fun getHistoricalMinScannedBlock(): Long? =
        syncStateDao.get(CURSOR_KEY)?.historicalMinScannedBlock

    override suspend fun saveSyncBlockInfo(
        lastScannedBlock: Long?,
        historicalMinScannedBlock: Long?
    ) {
        syncDataMutex.withLock {
            val currentState = syncStateDao.get(CURSOR_KEY)
            val newLastScannedBlock = lastScannedBlock ?: currentState?.lastScannedBlock

            val currentHistorical = currentState?.historicalMinScannedBlock
            val newHistoricalMinScannedBlock = when {
                historicalMinScannedBlock != null && currentHistorical != null ->
                    minOf(historicalMinScannedBlock, currentHistorical)
                historicalMinScannedBlock != null -> historicalMinScannedBlock
                else -> currentHistorical ?: lastScannedBlock
            }

            syncStateDao.insert(
                Eip20SyncState(
                    contractAddress = CURSOR_KEY,
                    lastScannedBlock = newLastScannedBlock,
                    historicalMinScannedBlock = newHistoricalMinScannedBlock
                )
            )
        }
    }

    override suspend fun clearForeignSyncState(chainHead: Long, margin: Long): Boolean =
        // Same lock as saveSyncBlockInfo: the sync that reads the cursor next must not race a write
        // that would re-persist the foreign value.
        syncDataMutex.withLock {
            val state = syncStateDao.get(CURSOR_KEY) ?: return@withLock false
            val limit = chainHead + margin
            val foreign = (state.lastScannedBlock ?: 0L) > limit ||
                    (state.historicalMinScannedBlock ?: 0L) > limit

            if (foreign) {
                syncStateDao.delete(CURSOR_KEY)
            }
            foreign
        }

    companion object {
        private const val CURSOR_KEY = "0x0000000000000000000000000000000000000000"
    }
}
