package io.horizontalsystems.ethereumkit.fixture

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import io.horizontalsystems.sqlcipher.SqlCipherMigration
import org.junit.Assert.assertArrayEquals
import java.io.File
import java.security.MessageDigest

// Desktop-only helpers for the kit's SQLCipher wiring, shared by the desktop tests of every module.

internal const val NAMESPACE = "ethereum-kit"
internal const val STAGING_SUFFIX = ".sqlcipher-migrating"
internal const val BACKUP_SUFFIX = ".plaintext-backup"
internal val LOCK_FILE_NAME = ".$NAMESPACE-sqlcipher.lock"

/** Every row of every table, blobs as hex, so two files compare value for value. */
internal fun plaintextTables(file: File): Map<String, List<List<String?>>> =
    BundledSQLiteDriver().open(file.path).use(::readTables)

internal fun encryptedTables(file: File, key: ByteArray): Map<String, List<List<String?>>> =
    SqlCipherDriver(key).use { driver -> driver.open(file.path).use(::readTables) }

@OptIn(ExperimentalStdlibApi::class)
private fun readTables(connection: SQLiteConnection): Map<String, List<List<String?>>> {
    val tables = connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { statement ->
        buildList { while (statement.step()) add(statement.getText(0)) }
    }
    return tables.associateWith { table ->
        connection.prepare("SELECT * FROM `$table` ORDER BY rowid").use { statement ->
            buildList {
                while (statement.step()) {
                    add(List(statement.getColumnCount()) { column ->
                        when (statement.getColumnType(column)) {
                            SQLITE_DATA_NULL -> null
                            SQLITE_DATA_BLOB -> statement.getBlob(column).toHexString()
                            else -> statement.getText(column)
                        }
                    })
                }
            }
        }
    }
}

internal enum class ManifestPhase { PREPARING, STAGED, COMMITTED, CLEARING }

/** The files a migration of [database] killed after staging leaves: plaintext intact, ciphertext beside it. */
internal fun interruptStagedMigration(database: File, migrationId: String, key: ByteArray): File {
    SqlCipherMigration.exportPlaintext(database.path, "${database.path}$STAGING_SUFFIX", key)
    return writeManifest(database.parentFile, migrationId, ManifestPhase.STAGED, listOf(database))
}

/** A sqlcipher-room manifest (format version 1) for [migrationId], as the kit would have written it. */
internal fun writeManifest(directory: File, migrationId: String, phase: ManifestPhase, databases: List<File>): File {
    val entries = databases.joinToString(",") { database ->
        """{"databasePath":${jsonString(database.path)},"stagingPath":${jsonString("${database.path}$STAGING_SUFFIX")}}"""
    }
    return manifestFile(directory, migrationId).apply {
        writeText("""{"version":1,"phase":"${phase.name}","entries":[$entries]}""")
    }
}

// Same derivation as sqlcipher-room: SHA-256 prefix of the migration id in hex.
internal fun manifestFile(directory: File, migrationId: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(migrationId.encodeToByteArray())
    val id = digest.take(8).joinToString("") { "%02x".format(it) }
    return File(directory, ".$NAMESPACE-sqlcipher-$id.json")
}

private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

internal fun migrationArtifacts(directory: File): List<String> = directory.list().orEmpty().filter { name ->
    name.endsWith(".json") || name.contains(STAGING_SUFFIX) || name.contains(BACKUP_SUFFIX)
}

/** Other kits' manifests and locks plus [otherDatabases]; the map holds their bytes to prove they stay untouched. */
internal fun foreignFiles(directory: File, otherDatabases: List<File> = emptyList()): Map<File, ByteArray> {
    val files = listOf("bitcoin-kit", "tron-kit", "ton-kit").flatMap { namespace ->
        listOf(
            File(directory, ".$namespace-sqlcipher-0123456789abcdef.json").apply {
                writeText("""{"version":1,"phase":"STAGED","entries":[]}""")
            },
            File(directory, ".$namespace-sqlcipher.lock").apply { writeText("") },
        )
    }
    return (files + otherDatabases).associateWith(File::readBytes)
}

internal fun assertUnchanged(snapshot: Map<File, ByteArray>) {
    snapshot.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
}

/** Files of the [databaseName] family left after a clear, apart from Room's JVM open lock, which no engine knows. */
internal fun databaseFamily(directory: File, databaseName: String): List<String> = directory.list().orEmpty()
    .filter { it.startsWith(databaseName) && it != "$databaseName.lck" }
