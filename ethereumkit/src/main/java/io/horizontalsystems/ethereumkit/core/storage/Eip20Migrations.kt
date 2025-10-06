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
