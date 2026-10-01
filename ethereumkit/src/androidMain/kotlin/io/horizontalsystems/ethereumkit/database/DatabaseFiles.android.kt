package io.horizontalsystems.ethereumkit.database

import io.horizontalsystems.ethereumkit.PlatformContext
import java.io.File

actual fun databaseFile(context: PlatformContext, name: String): File = context.getDatabasePath(name)

actual fun databaseNames(context: PlatformContext): List<String> = context.databaseList().toList()

actual fun deleteDatabase(context: PlatformContext, name: String) {
    context.deleteDatabase(name)
}
