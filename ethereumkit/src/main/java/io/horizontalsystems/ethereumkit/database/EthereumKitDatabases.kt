package io.horizontalsystems.ethereumkit.database

import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.sqlcipher.room.SqlCipherDatabases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The SQLCipher namespace shared by every module of the kit; public only for those modules.
 * The engine rejects a concurrent migrate/clear and an open during any migration of the namespace,
 * so within the process they queue on one lock instead.
 */
object EthereumKitDatabases {
    // Names the on-disk manifest and lock files (`.ethereum-kit-sqlcipher*`); must never change.
    private val databases = SqlCipherDatabases("ethereum-kit")
    private val mutex = Mutex()

    /** Throws [IllegalArgumentException] unless [databaseKey] has exactly 32 bytes. */
    fun requireValidDatabaseKey(databaseKey: ByteArray) {
        require(databaseKey.size == DATABASE_KEY_SIZE) { "Database key must contain exactly $DATABASE_KEY_SIZE bytes" }
    }

    /** Throws [IllegalArgumentException] for a blank [walletId] or one that cannot be part of a file name. */
    fun requireValidWalletId(walletId: String) {
        require(walletId.isNotBlank()) { "Wallet id must not be blank" }
        requireNoPathOrMigrationName(walletId)
    }

    /** Id of the migration group of [module] for one wallet on one chain; a clear finds interrupted work by it. */
    fun migrationId(module: String, chain: Chain, walletId: String) = "$module:${chain.id}:$walletId"

    /** The directory that holds every database of the kit; computed, never created. */
    fun directory(context: PlatformContext): File =
        checkNotNull(databaseFile(context, DIRECTORY_PROBE).absoluteFile.parentFile) { "Database directory is unknown" }

    /** Migrates each of [databaseNames] in its own engine call, so a group never mixes plaintext and encrypted files. */
    suspend fun migrate(
        context: PlatformContext,
        migrationId: String,
        databaseNames: List<String>,
        databaseKey: ByteArray,
    ): DatabaseMigrationResult {
        requireValidDatabaseKey(databaseKey)
        databaseNames.forEach(::requireValidDatabaseName)
        val directory = directory(context).path
        return mutex.withLock {
            databaseNames.fold(DatabaseMigrationResult(0, 0)) { total, name ->
                val result = databases.migrateDatabases(directory, listOf(name), migrationId, databaseKey)
                DatabaseMigrationResult(
                    total.migratedDatabaseCount + result.migratedDatabaseCount,
                    total.alreadyEncryptedDatabaseCount + result.alreadyEncryptedDatabaseCount,
                )
            }
        }
    }

    /** Deletes [databaseNames] and whatever an interrupted migration or clear of [migrationId] left behind. */
    suspend fun clear(context: PlatformContext, migrationId: String, databaseNames: List<String>) {
        databaseNames.forEach(::requireValidDatabaseName)
        val directory = directory(context).path
        mutex.withLock {
            withContext(Dispatchers.IO) {
                databases.clearDatabases(directory, databaseNames, migrationId)
            }
        }
    }

    /** Builds and opens the database, so key and open failures surface here rather than on a later query. */
    suspend fun <T : RoomDatabase> open(build: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val database = build()
            try {
                database.useReaderConnection { }
                database
            } catch (error: Throwable) {
                database.close()
                throw error
            }
        }
    }

    /** Checks the file at [path] against [databaseKey] and configures [builder] to open it encrypted. */
    fun <T : RoomDatabase> encrypted(
        builder: RoomDatabase.Builder<T>,
        path: String,
        databaseKey: ByteArray,
    ): RoomDatabase.Builder<T> = databases.encrypted(builder, path, databaseKey)

    private fun requireValidDatabaseName(name: String) {
        require(name.isNotBlank()) { "Database name must not be blank" }
        require(RESERVED_PREFIXES.none(name::startsWith)) { "Database name uses a reserved migration prefix: $name" }
        requireNoPathOrMigrationName(name)
    }

    private fun requireNoPathOrMigrationName(value: String) {
        require(value.none { it == '/' || it == '\\' }) { "Must not contain a path separator: $value" }
        // Contains, not endsWith: the SQLite family of a staging file (-wal, -shm, ...) is recovered too.
        require(RESERVED_SUFFIXES.none(value::contains)) { "Must not contain a reserved migration suffix: $value" }
    }

    private const val DATABASE_KEY_SIZE = 32
    private const val DIRECTORY_PROBE = "ethereum-kit"

    // Mirror sqlcipher-room's private file names, so a kit database never collides with migration files.
    private val RESERVED_PREFIXES = listOf(
        ".ethereum-kit-sqlcipher",
        ".bitcoin-kit-sqlcipher",
        ".tron-kit-sqlcipher",
        ".stellar-kit-sqlcipher",
        ".solana-kit-sqlcipher",
        ".ton-kit-sqlcipher",
        ".ton-connect-sqlcipher",
    )
    private val RESERVED_SUFFIXES = listOf(".sqlcipher-migrating", ".plaintext-backup")
}
