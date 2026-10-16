package io.horizontalsystems.ethereumkit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.ethereumkit.PlatformContext
import kotlinx.coroutines.Dispatchers

/** Encrypted Room builder for a kit database file [name]; public only for the kit's own modules. */
inline fun <reified T : RoomDatabase> kitDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
): RoomDatabase.Builder<T> {
    val file = databaseFile(context, name)
    // Verifies the file against the key before any directory is created.
    val builder = EthereumKitDatabases.encrypted(Room.databaseBuilder<T>(file.path), file.path, databaseKey)
    // Unlike Android's getDatabasePath, a JVM driver does not create the parent directory.
    file.absoluteFile.parentFile?.mkdirs()
    return builder.setQueryCoroutineContext(Dispatchers.IO)
}
