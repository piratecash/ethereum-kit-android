package io.horizontalsystems.merkleiokit

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.ethereumkit.fixture.watchKit
import io.horizontalsystems.ethereumkit.models.Chain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

class MerkleDatabaseNameResolverDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val opaque = DatabaseNameResolver { "n${it.hashCode().toUInt()}" }

    @Test
    fun getInstance_defaultResolver_opensLiteralName() = runBlocking<Unit> {
        open(watchKit(context, WALLET, databaseKey))

        assertTrue(File(directory, "MerkleIo-1-$WALLET").isFile)
    }

    @Test
    fun getInstance_differentChains_resolvedNamesStayDistinct() = runBlocking<Unit> {
        open(watchKit(context, WALLET, databaseKey, nameResolver = opaque), opaque)
        open(watchKit(context, WALLET, databaseKey, Chain.BinanceSmartChain, opaque), opaque)

        assertTrue(File(directory, opaque.resolve("MerkleIo-1-$WALLET")).isFile)
        assertTrue(File(directory, opaque.resolve("MerkleIo-56-$WALLET")).isFile)
        assertEquals(emptyList<String>(), directory.list().orEmpty().filter { it.contains("Merkle") })
    }

    @Test
    fun clear_overrideResolver_removesResolvedNameAndKeepsDefaultOne() = runBlocking {
        open(watchKit(context, WALLET, databaseKey, nameResolver = opaque), opaque)
        val foreign = File(directory, "MerkleIo-1-$WALLET").apply { writeText("x") }

        MerkleTransactionAdapter.clear(context, Chain.Ethereum, WALLET, opaque)

        assertFalse(File(directory, opaque.resolve("MerkleIo-1-$WALLET")).exists())
        assertTrue(foreign.exists())
    }

    private suspend fun open(kit: EthereumKit, nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity) {
        MerkleTransactionAdapter.getInstance(
            merkleIoPubKey = "key",
            address = kit.receiveAddress,
            chain = kit.chain,
            context = context,
            walletId = WALLET,
            databaseKey = databaseKey,
            transactionManager = kit.transactionManager,
            sourceTag = "test",
            transactionSyncSourceStorage = kit.transactionSyncSourceStorage,
            nameResolver = nameResolver,
        )
    }

    private companion object {
        const val WALLET = "w1"
    }
}
