package io.horizontalsystems.merkleiokit.fixture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.fixture.DatabaseFixtureFiles
import io.horizontalsystems.merkleiokit.MerkleDatabase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Skipped by `test`; run only to regenerate the fixtures (see [DatabaseFixtureFiles.assumeRegenerating]). */
@RunWith(RobolectricTestRunner::class)
class DatabaseFixtureGenerator {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = DatabaseFixtureFiles.assumeRegenerating()

    @Test
    fun generateMerkleDatabase() {
        val database = MerkleDatabase.getInstance(context, MerkleIoFixture.DB)
        MerkleIoFixture.hashes.forEach(database.merkleTransactionDao()::save)
        DatabaseFixtureFiles.export(context, database, MerkleIoFixture.DB)
    }
}
