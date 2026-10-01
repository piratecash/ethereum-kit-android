package io.horizontalsystems.ethereumkit.database

import io.horizontalsystems.ethereumkit.PlatformContext
import java.io.File

// Public only for the kit's own modules (erc20kit, nftkit, merkleiokit).

/** Path computation only, no I/O: [name] is either a bare file name or an absolute path. */
expect fun databaseFile(context: PlatformContext, name: String): File

/** Every file in the database directory, SQLite companions (-wal, -shm, -journal) included. */
expect fun databaseNames(context: PlatformContext): List<String>

/** Deletes the database file [name] together with its SQLite companions. */
expect fun deleteDatabase(context: PlatformContext, name: String)
