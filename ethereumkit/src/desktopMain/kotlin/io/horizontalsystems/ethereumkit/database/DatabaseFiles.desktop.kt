package io.horizontalsystems.ethereumkit.database

import io.horizontalsystems.ethereumkit.PlatformContext
import java.io.File

actual fun databaseFile(context: PlatformContext, name: String): File =
    File(name).takeIf { it.isAbsolute } ?: File(context.dataDir, name)

actual fun databaseNames(context: PlatformContext): List<String> = context.dataDir.list()?.toList().orEmpty()

// The same file family Android's Context.deleteDatabase removes.
actual fun deleteDatabase(context: PlatformContext, name: String) {
    val path = databaseFile(context, name).path
    listOf("", "-journal", "-shm", "-wal").forEach { suffix -> File(path + suffix).delete() }
}
