package io.horizontalsystems.ethereumkit.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.ethereumkit.PlatformContext

/** Room builder for a kit database file [name]; public only for the kit's own modules. */
inline fun <reified T : RoomDatabase> kitDatabaseBuilder(context: PlatformContext, name: String): RoomDatabase.Builder<T> =
    Room.databaseBuilder(context, T::class.java, name)
        .allowMainThreadQueries()
