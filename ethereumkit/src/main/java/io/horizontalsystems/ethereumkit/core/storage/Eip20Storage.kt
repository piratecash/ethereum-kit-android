package io.horizontalsystems.ethereumkit.core.storage

import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.Eip20SyncState

class Eip20Storage(database: Eip20Database) : IEip20Storage {
    private val erc20EventDao = database.eip20EventDao()
    private val syncStateDao = database.eip20SyncStateDao()

    override fun getLastEvent(): Eip20Event? =
        erc20EventDao.getLastEip20Event()

    override fun save(events: List<Eip20Event>) {
        erc20EventDao.insertEip20Events(events)
    }

    override fun getEvents(): List<Eip20Event> =
        erc20EventDao.getEip20Events()

    override fun getEventsByHashes(hashes: List<ByteArray>): List<Eip20Event> =
        erc20EventDao.getEip20EventsByHashes(hashes)

    override fun deleteZeroValueDuplicate(hash: ByteArray, contractAddress: Address, from: Address, to: Address) {
        erc20EventDao.deleteZeroValueDuplicate(hash, contractAddress.raw, from.raw, to.raw)
    }

    override fun getLastScannedBlock(): Long? =
        syncStateDao.get(CURSOR_KEY)?.lastScannedBlock

    override fun saveLastScannedBlock(blockNumber: Long) {
        syncStateDao.insert(Eip20SyncState(CURSOR_KEY, blockNumber))
    }

    companion object {
        private const val CURSOR_KEY = "0x0000000000000000000000000000000000000000"
    }
}
