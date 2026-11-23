package io.horizontalsystems.erc20kit

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.erc20kit.core.Erc20Kit
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.fixture.ManifestPhase
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.ethereumkit.fixture.otherDatabaseKey
import io.horizontalsystems.ethereumkit.fixture.watchKit
import io.horizontalsystems.ethereumkit.fixture.writeManifest
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationInProgressException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
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

class Erc20KitDatabaseNameResolverDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val opaque = DatabaseNameResolver { "n${it.hashCode().toUInt()}" }
    private val usdtDefaultName = "Erc20-1-$WALLET-$USDT"

    @Test
    fun getInstance_defaultResolver_opensLiteralName() = runBlocking<Unit> {
        Erc20Kit.getInstance(context, watchKit(context, WALLET, databaseKey), Address(USDT), databaseKey)

        assertTrue(File(directory, usdtDefaultName).isFile)
    }

    @Test
    fun getInstance_kitWithResolver_tokenDatabaseFollowsTheKit() = runBlocking<Unit> {
        Erc20Kit.getInstance(context, watchKit(context, WALLET, databaseKey, nameResolver = opaque), Address(USDT), databaseKey)

        assertTrue(File(directory, opaque.resolve(usdtDefaultName)).isFile)
        assertEquals(emptyList<String>(), directory.list().orEmpty().filter { it.contains("Erc20") })
    }

    @Test
    fun getInstance_differentChainsAndTokens_namesArePairwiseDistinct() = runBlocking<Unit> {
        val ethereum = watchKit(context, WALLET, databaseKey, nameResolver = opaque)
        val bsc = watchKit(context, WALLET, databaseKey, Chain.BinanceSmartChain, opaque)

        Erc20Kit.getInstance(context, ethereum, Address(USDT), databaseKey)
        Erc20Kit.getInstance(context, ethereum, Address(OTHER_TOKEN), databaseKey)
        Erc20Kit.getInstance(context, bsc, Address(USDT), databaseKey)

        listOf(usdtDefaultName, "Erc20-1-$WALLET-$OTHER_TOKEN", "Erc20-56-$WALLET-$USDT").forEach {
            assertTrue(it, File(directory, opaque.resolve(it)).isFile)
        }
    }

    @Test
    fun clear_overrideResolverWithContracts_removesListedTokensOnly() = runBlocking {
        val kit = watchKit(context, WALLET, databaseKey, nameResolver = opaque)
        Erc20Kit.getInstance(context, kit, Address(USDT), databaseKey)
        Erc20Kit.getInstance(context, kit, Address(OTHER_TOKEN), databaseKey)

        Erc20Kit.clear(context, Chain.Ethereum, WALLET, opaque, listOf(Address(USDT)))

        assertFalse(File(directory, opaque.resolve(usdtDefaultName)).exists())
        assertTrue(File(directory, opaque.resolve("Erc20-1-$WALLET-$OTHER_TOKEN")).isFile)
    }

    @Test
    fun clear_overrideResolverWithoutContracts_cannotFindTokenFiles() = runBlocking {
        val kit = watchKit(context, WALLET, databaseKey, nameResolver = opaque)
        Erc20Kit.getInstance(context, kit, Address(USDT), databaseKey)

        Erc20Kit.clear(context, Chain.Ethereum, WALLET, opaque)

        assertTrue(File(directory, opaque.resolve(usdtDefaultName)).isFile)
    }

    @Test
    fun clear_overrideResolverDefaultNamedTokenBeside_keepsUnlistedOpaqueToken() = runBlocking {
        val opaqueKit = watchKit(context, WALLET, databaseKey, nameResolver = opaque)
        Erc20Kit.getInstance(context, opaqueKit, Address(USDT), databaseKey)
        Erc20Kit.getInstance(context, opaqueKit, Address(OTHER_TOKEN), databaseKey)
        Erc20Kit.getInstance(context, watchKit(context, WALLET, databaseKey), Address(OTHER_TOKEN), databaseKey)
        val otherDefaultName = "Erc20-1-$WALLET-$OTHER_TOKEN"

        Erc20Kit.clear(context, Chain.Ethereum, WALLET, opaque, listOf(Address(USDT)))

        assertFalse(File(directory, opaque.resolve(usdtDefaultName)).exists())
        assertTrue(File(directory, opaque.resolve(otherDefaultName)).isFile)
        assertTrue(File(directory, otherDefaultName).isFile)
    }

    @Test
    fun migrateDatabases_overrideResolverListedTokenOtherKey_throwsKeyMismatch() = runBlocking<Unit> {
        Erc20Kit.getInstance(context, watchKit(context, WALLET, databaseKey, nameResolver = opaque), Address(USDT), databaseKey)

        assertThrows(DatabaseKeyMismatchException::class.java) {
            runBlocking {
                Erc20Kit.migrateDatabases(context, Chain.Ethereum, WALLET, otherDatabaseKey, opaque, listOf(Address(USDT)))
            }
        }
    }

    @Test
    fun migrateDatabases_overrideResolverListedToken_reportsItEncrypted() = runBlocking {
        Erc20Kit.getInstance(context, watchKit(context, WALLET, databaseKey, nameResolver = opaque), Address(USDT), databaseKey)

        val result = Erc20Kit.migrateDatabases(context, Chain.Ethereum, WALLET, databaseKey, opaque, listOf(Address(USDT)))

        assertEquals(DatabaseMigrationResult(0, 1), result)
    }

    @Test
    fun migrateDatabases_overrideResolverDefaultNamedTokenBeside_leavesItAlone() = runBlocking {
        Erc20Kit.getInstance(context, watchKit(context, WALLET, databaseKey), Address(USDT), databaseKey)

        val result = Erc20Kit.migrateDatabases(context, Chain.Ethereum, WALLET, otherDatabaseKey, opaque)

        assertEquals(DatabaseMigrationResult(0, 0), result)
    }

    @Test
    fun migrateDatabases_overrideResolverInterruptedClearNoList_nextOpenWorks() = runBlocking<Unit> {
        val kit = watchKit(context, WALLET, databaseKey, nameResolver = opaque)
        val manifest = interruptClear(opaque)
        assertThrows(DatabaseMigrationInProgressException::class.java) {
            runBlocking { Erc20Kit.getInstance(context, kit, Address(USDT), databaseKey) }
        }

        val result = Erc20Kit.migrateDatabases(context, Chain.Ethereum, WALLET, databaseKey, opaque)

        assertEquals(DatabaseMigrationResult(0, 0), result)
        assertFalse(manifest.exists())
        Erc20Kit.getInstance(context, kit, Address(USDT), databaseKey)
    }

    @Test
    fun migrateDatabases_overrideResolverInterruptedClearListed_nextOpenWorks() = runBlocking<Unit> {
        val kit = watchKit(context, WALLET, databaseKey, nameResolver = opaque)
        val manifest = interruptClear(opaque)

        Erc20Kit.migrateDatabases(context, Chain.Ethereum, WALLET, databaseKey, opaque, listOf(Address(USDT)))

        assertFalse(manifest.exists())
        Erc20Kit.getInstance(context, kit, Address(USDT), databaseKey)
    }

    @Test
    fun migrateDatabases_defaultResolverInterruptedClearNoFilesLeft_nextOpenWorks() = runBlocking<Unit> {
        val kit = watchKit(context, WALLET, databaseKey)
        val manifest = writeManifest(directory, "erc20:1:$WALLET", ManifestPhase.CLEARING, listOf(File(directory, usdtDefaultName)))

        Erc20Kit.migrateDatabases(context, Chain.Ethereum, WALLET, databaseKey)

        assertFalse(manifest.exists())
        Erc20Kit.getInstance(context, kit, Address(USDT), databaseKey)
    }

    // What a process killed during a clear leaves behind: a half-deleted token and its CLEARING manifest.
    private fun interruptClear(nameResolver: DatabaseNameResolver): File {
        val token = File(directory, nameResolver.resolve(usdtDefaultName)).apply { writeText("x") }
        return writeManifest(
            directory,
            nameResolver.resolve("erc20:1:$WALLET"),
            ManifestPhase.CLEARING,
            listOf(token),
            nameResolver.resolve("ethereum-kit"),
        )
    }

    private companion object {
        const val WALLET = "w1"
        const val USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7"
        const val OTHER_TOKEN = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    }
}
