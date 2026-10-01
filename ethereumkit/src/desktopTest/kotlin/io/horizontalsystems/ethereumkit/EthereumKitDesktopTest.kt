package io.horizontalsystems.ethereumkit

import io.horizontalsystems.ethereumkit.api.storage.ApiDatabase
import io.horizontalsystems.ethereumkit.api.storage.ApiStorage
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.storage.Eip20Database
import io.horizontalsystems.ethereumkit.core.storage.Eip20Storage
import io.horizontalsystems.ethereumkit.core.storage.TransactionDatabase
import io.horizontalsystems.ethereumkit.core.storage.TransactionStorage
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.crypto.CryptoUtils
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.snapshot
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.models.RpcSource
import io.horizontalsystems.ethereumkit.models.TransactionSource
import io.horizontalsystems.ethereumkit.models.TransactionTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class EthereumKitDesktopTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context by lazy { PlatformContext(tempFolder.newFolder("databases")) }

    @Test
    fun apiDatabase_desktop_writesAndReadsThroughDao() = runTest {
        val storage = ApiStorage(ApiDatabase.getInstance(context, EthereumKitFixture.API_DB, databaseKey))

        storage.saveAccountState(EthereumKitFixture.accountState)
        storage.saveLastBlockHeight(EthereumKitFixture.LAST_BLOCK_HEIGHT)

        assertEquals(EthereumKitFixture.accountState, storage.getAccountState())
        assertEquals(EthereumKitFixture.LAST_BLOCK_HEIGHT, storage.getLastBlockHeight())
    }

    @Test
    fun transactionDatabase_desktop_writesAndReadsThroughDaoAndRawQuery() = runTest {
        val storage = TransactionStorage(TransactionDatabase.getInstance(context, EthereumKitFixture.TRANSACTIONS_DB, databaseKey))

        storage.save(EthereumKitFixture.transactions)
        storage.saveTags(EthereumKitFixture.tags)
        EthereumKitFixture.rawBroadcasts.forEach { storage.addRawTransactionBroadcast(it) }

        assertEquals(
            listOf(EthereumKitFixture.pendingTransaction, EthereumKitFixture.tokenTransferTransaction).map { it.snapshot() },
            storage.getTransactionsBefore(listOf(listOf(TransactionTag.OUTGOING)), null, null).map { it.snapshot() }
        )
        assertEquals(
            EthereumKitFixture.rawBroadcasts.map { it.snapshot() }.sortedBy { it.toString() },
            storage.getRawTransactionBroadcasts().map { it.snapshot() }.sortedBy { it.toString() }
        )
    }

    @Test
    fun eip20Database_desktop_writesAndReadsThroughDao() = runTest {
        val storage = Eip20Storage(Eip20Database.getInstance(context, EthereumKitFixture.EIP20_EVENTS_DB, databaseKey))

        storage.save(EthereumKitFixture.eip20Events)

        assertEquals(
            EthereumKitFixture.eip20Events.map { it.snapshot() }.sortedBy { it.toString() },
            storage.getEvents().map { it.snapshot() }.sortedBy { it.toString() }
        )
    }

    @Test
    fun getInstance_desktopAddress_createsKitAndItsDatabaseFiles() = runTest {
        val kit = EthereumKit.getInstance(
            application = context,
            address = EthereumKitFixture.account,
            chain = Chain.Ethereum,
            rpcSource = RpcSource.Http(listOf(URI("http://127.0.0.1:1")), null),
            transactionSource = TransactionSource.ethereum(listOf("key")),
            walletId = "desktopwallet",
            databaseKey = databaseKey
        )

        assertEquals(EthereumKitFixture.account, kit.receiveAddress)
        // Room opens a file on first use; the factory itself reads only the api and erc20_events databases.
        assertEquals(emptyList<String>(), kit.getTagTokenContractAddresses())
        listOf("api", "txs", "erc20_events").forEach { suffix ->
            assertTrue(suffix, File(context.dataDir, "Ethereum-1-desktopwallet-$suffix").isFile)
        }
    }

    @Test
    fun sha3_desktopJvm_usesBundledKeccakDigest() {
        EthereumKit.init()

        assertEquals(
            "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            CryptoUtils.sha3(ByteArray(0)).toHexString()
        )
    }
}
