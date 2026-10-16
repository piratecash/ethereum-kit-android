package io.horizontalsystems.ethereumkit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.ethereumkit.PlatformContext

/** Encrypted Room builder for a kit database file [name]; public only for the kit's own modules. */
inline fun <reified T : RoomDatabase> kitDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
): RoomDatabase.Builder<T> = EthereumKitDatabases.encrypted(
    Room.databaseBuilder(context, T::class.java, name),
    databaseFile(context, name).path,
    databaseKey,
)
