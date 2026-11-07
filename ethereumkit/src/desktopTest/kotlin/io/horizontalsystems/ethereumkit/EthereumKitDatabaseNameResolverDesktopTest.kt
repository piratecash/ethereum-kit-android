package io.horizontalsystems.ethereumkit

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.ethereumkit.fixture.watchKit
import io.horizontalsystems.ethereumkit.models.Chain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

// Android keeps the default names forever: a change here makes every existing wallet resync from scratch.
class EthereumKitDatabaseNameResolverDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val opaque = DatabaseNameResolver { "n${it.hashCode().toUInt()}" }

    @Test
    fun getInstance_defaultResolver_opensLiteralNames() = runBlocking<Unit> {
        watchKit(context, WALLET, databaseKey)

        assertEquals(setOf("Ethereum-1-w1-api", "Ethereum-1-w1-txs", "Ethereum-1-w1-erc20_events"), databaseFiles())
    }

    @Test
    fun getInstance_differentChainsAndWallets_namesArePairwiseDistinct() = runBlocking<Unit> {
        watchKit(context, WALLET, databaseKey)
        watchKit(context, WALLET, databaseKey, Chain.BinanceSmartChain)
        watchKit(context, "w2", databaseKey)
        val defaults = databaseFiles()
        assertEquals(9, defaults.size)
        assertTrue(defaults.containsAll(listOf("Ethereum-56-w1-api", "Ethereum-1-w2-api")))

        watchKit(context, WALLET, databaseKey, nameResolver = opaque)
        watchKit(context, WALLET, databaseKey, Chain.BinanceSmartChain, opaque)
        watchKit(context, "w2", databaseKey, nameResolver = opaque)

        assertEquals(defaults + defaults.map(opaque::resolve), databaseFiles())
    }

    @Test
    fun getInstance_overrideResolver_leavesNoDefaultNameOnDisk() = runBlocking<Unit> {
        watchKit(context, WALLET, databaseKey, nameResolver = opaque)

        assertEquals(
            listOf("Ethereum-1-w1-api", "Ethereum-1-w1-txs", "Ethereum-1-w1-erc20_events").map(opaque::resolve).toSet(),
            databaseFiles(),
        )
        assertEquals(emptyList<String>(), directory.list().orEmpty().filter { it.contains("thereum") })
    }

    @Test
    fun migrateDatabase_overrideResolver_namespaceFilesUseResolvedName() = runBlocking<Unit> {
        EthereumKit.migrateDatabase(context, Chain.Ethereum, WALLET, databaseKey, opaque)

        assertEquals(listOf(".${opaque.resolve("ethereum-kit")}-sqlcipher.lock"), directory.list().orEmpty().toList())
    }

    @Test
    fun clear_defaultResolver_removesLiteralNamesIncludingSpv() = runBlocking {
        watchKit(context, WALLET, databaseKey)
        File(directory, "Ethereum-1-w1-spv").writeText("x")

        EthereumKit.clear(context, Chain.Ethereum, WALLET)

        assertEquals(emptySet<String>(), databaseFiles())
    }

    @Test
    fun clear_overrideResolver_removesResolvedNamesAndKeepsDefaultOnes() = runBlocking {
        watchKit(context, WALLET, databaseKey, nameResolver = opaque)
        val spv = File(directory, opaque.resolve("Ethereum-1-w1-spv")).apply { writeText("x") }
        val foreign = File(directory, "Ethereum-1-w1-api").apply { writeText("x") }

        EthereumKit.clear(context, Chain.Ethereum, WALLET, opaque)

        assertFalse(spv.exists())
        assertEquals(setOf(foreign.name), databaseFiles())
    }

    @Test
    fun getInstance_resolvedNameInResolvedNamespacePrefix_throws() {
        val reserved = DatabaseNameResolver { if (it == "ethereum-kit") "ns" else ".ns-sqlcipher-x" }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { watchKit(context, WALLET, databaseKey, nameResolver = reserved) }
        }
    }

    @Test
    fun clear_resolvedNameWithPathSeparator_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { EthereumKit.clear(context, Chain.Ethereum, WALLET) { "../$it" } }
        }
    }

    // Skips namespace files and Room's JVM open locks, which are not databases.
    private fun databaseFiles(): Set<String> =
        directory.list().orEmpty().filterNot { it.startsWith(".") || it.endsWith(".lck") || it.endsWith("-wal") || it.endsWith("-shm") }.toSet()

    private companion object {
        const val WALLET = "w1"
    }
}
