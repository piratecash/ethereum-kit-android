package io.horizontalsystems.ethereumkit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.ethereumkit.PlatformContext
import kotlinx.coroutines.Dispatchers

/** Room builder for a kit database file [name]; public only for the kit's own modules. */
inline fun <reified T : RoomDatabase> kitDatabaseBuilder(context: PlatformContext, name: String): RoomDatabase.Builder<T> {
    val file = databaseFile(context, name)
    // Unlike Android's getDatabasePath, a JVM driver does not create the parent directory.
    file.absoluteFile.parentFile?.mkdirs()
    return Room.databaseBuilder<T>(file.path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}
