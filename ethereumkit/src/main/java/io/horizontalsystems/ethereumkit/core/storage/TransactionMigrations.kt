package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val migration13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
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

        database.execSQL("""
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

        database.execSQL("DROP TABLE `InternalTransaction`")
        database.execSQL("ALTER TABLE `InternalTransaction_new` RENAME TO `InternalTransaction`")
    }
}

val migration14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `TransactionSyncSource` (
                `transactionHash` BLOB NOT NULL,
                `source` TEXT NOT NULL,
                PRIMARY KEY(`transactionHash`)
            )
        """.trimIndent())
    }
}

val migration15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
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
