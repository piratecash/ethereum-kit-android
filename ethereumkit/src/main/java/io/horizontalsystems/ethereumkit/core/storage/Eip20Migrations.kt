package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val migration2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `Eip20Event_new` (
                `hash` BLOB NOT NULL,
                `blockNumber` INTEGER NOT NULL,
                `contractAddress` BLOB NOT NULL,
                `from` BLOB NOT NULL,
                `to` BLOB NOT NULL,
                `value` TEXT NOT NULL,
                `tokenName` TEXT NOT NULL,
                `tokenSymbol` TEXT NOT NULL,
                `tokenDecimal` INTEGER NOT NULL,
                PRIMARY KEY(`hash`, `contractAddress`, `from`, `to`, `value`)
            )
        """.trimIndent())

        database.execSQL("""
            INSERT INTO `Eip20Event_new` (
                `hash`, `blockNumber`, `contractAddress`, `from`, `to`, `value`,
                `tokenName`, `tokenSymbol`, `tokenDecimal`
            )
            SELECT e.`hash`, e.`blockNumber`, e.`contractAddress`, e.`from`, e.`to`, e.`value`,
                   e.`tokenName`, e.`tokenSymbol`, e.`tokenDecimal`
            FROM `Eip20Event` e
            INNER JOIN (
                SELECT MIN(`id`) AS `id`
                FROM `Eip20Event`
                GROUP BY `hash`, `contractAddress`, `from`, `to`, `value`
            ) grouped ON e.`id` = grouped.`id`
        """.trimIndent())

        database.execSQL("DROP TABLE `Eip20Event`")
        database.execSQL("ALTER TABLE `Eip20Event_new` RENAME TO `Eip20Event`")
    }
}

val migration3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `Eip20SyncState` (
                `contractAddress` TEXT NOT NULL,
                `lastScannedBlock` INTEGER NOT NULL,
                PRIMARY KEY(`contractAddress`)
            )
        """.trimIndent())
    }
}

val migration4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            ALTER TABLE `Eip20SyncState`
            ADD COLUMN `historicalMinScannedBlock` INTEGER
        """.trimIndent())
    }
}

val migration5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // SQLite doesn't support ALTER COLUMN, so we need to recreate the table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `Eip20SyncState_new` (
                `contractAddress` TEXT NOT NULL,
                `lastScannedBlock` INTEGER,
                `historicalMinScannedBlock` INTEGER,
                PRIMARY KEY(`contractAddress`)
            )
        """.trimIndent())

        database.execSQL("""
            INSERT INTO `Eip20SyncState_new` (`contractAddress`, `lastScannedBlock`, `historicalMinScannedBlock`)
            SELECT `contractAddress`, `lastScannedBlock`, `historicalMinScannedBlock`
            FROM `Eip20SyncState`
        """.trimIndent())

        database.execSQL("DROP TABLE `Eip20SyncState`")
        database.execSQL("ALTER TABLE `Eip20SyncState_new` RENAME TO `Eip20SyncState`")
    }
}
