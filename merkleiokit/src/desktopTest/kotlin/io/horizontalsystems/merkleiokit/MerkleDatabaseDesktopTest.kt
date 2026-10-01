package io.horizontalsystems.merkleiokit

import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.merkleiokit.fixture.MerkleIoFixture
import io.horizontalsystems.merkleiokit.fixture.MerkleIoFixture.snapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MerkleDatabaseDesktopTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun merkleDatabase_desktop_writesAndReadsThroughDao() = runTest {
        val database = MerkleDatabase.getInstance(PlatformContext(tempFolder.newFolder("databases")), MerkleIoFixture.DB)
        val manager = MerkleTransactionHashManager(database.merkleTransactionDao())

        MerkleIoFixture.hashes.forEach { manager.save(it) }

        assertEquals(
            MerkleIoFixture.hashes.map { it.snapshot() }.sortedBy { it.toString() },
            manager.hashes().map { it.snapshot() }.sortedBy { it.toString() }
        )
    }
}
