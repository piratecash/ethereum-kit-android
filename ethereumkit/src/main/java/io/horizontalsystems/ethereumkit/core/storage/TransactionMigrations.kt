package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val migration13_14 = object : Migration(13, 14) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `InternalTransaction_new` (
                `hash` BLOB NOT NULL,
                `traceId` TEXT NOT NULL,
                `blockNumber` INTEGER NOT NULL,
                `from` BLOB NOT NULL,
                `to` BLOB NOT NULL,
                `value` TEXT NOT NULL,
                PRIMARY KEY(`hash`, `traceId`)
            )
        """.trimIndent())

        connection.execSQL("""
            INSERT INTO `InternalTransaction_new` (
                `hash`, `traceId`, `blockNumber`, `from`, `to`, `value`
            )
            SELECT
                `hash`,
                CAST(MIN(`id`) AS TEXT) AS `traceId`,
                `blockNumber`,
                `from`,
                `to`,
                `value`
            FROM `InternalTransaction`
            GROUP BY `hash`, `blockNumber`, `from`, `to`, `value`
        """.trimIndent())

        connection.execSQL("DROP TABLE `InternalTransaction`")
        connection.execSQL("ALTER TABLE `InternalTransaction_new` RENAME TO `InternalTransaction`")
    }
}

val migration14_15 = object : Migration(14, 15) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `TransactionSyncSource` (
                `transactionHash` BLOB NOT NULL,
                `source` TEXT NOT NULL,
                PRIMARY KEY(`transactionHash`)
            )
        """.trimIndent())
    }
}

val migration15_16 = object : Migration(15, 16) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `RawTransactionBroadcastRecord` (
                `hash` BLOB NOT NULL,
                `rawTransaction` BLOB NOT NULL,
                `firstSendTime` INTEGER NOT NULL,
                `lastSendTime` INTEGER NOT NULL,
                `retriesCount` INTEGER NOT NULL,
                `expiresAt` INTEGER NOT NULL,
                PRIMARY KEY(`hash`)
            )
        """.trimIndent())
    }
}

val migration16_17 = object : Migration(16, 17) {
    override fun migrate(connection: SQLiteConnection) {
        // Re-fetch authoritative native values that token syncers could previously overwrite.
        connection.execSQL("DELETE FROM `TransactionSyncerState`")
    }
}
