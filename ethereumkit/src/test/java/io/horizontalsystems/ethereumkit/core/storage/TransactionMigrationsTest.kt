package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class TransactionMigrationsTest {

    @Test
    fun migration16_17_migrate_resetsTransactionSyncCursor() {
        assertEquals(listOf("DELETE FROM `TransactionSyncerState`"), executedSql(migration16_17))
    }

    @Test
    fun migration17_18_migrate_addsNullableTypeAndDelegatesColumns() {
        assertEquals(
            listOf(
                "ALTER TABLE `Transaction` ADD COLUMN `type` INTEGER",
                "ALTER TABLE `Transaction` ADD COLUMN `authorizationDelegates` BLOB",
            ),
            executedSql(migration17_18)
        )
    }

    private fun executedSql(migration: Migration): List<String> {
        val executed = mutableListOf<String>()
        val statement = Proxy.newProxyInstance(
            SQLiteStatement::class.java.classLoader,
            arrayOf(SQLiteStatement::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "step" -> false
                "close" -> null
                else -> error("Unexpected ${method.name}")
            }
        } as SQLiteStatement
        val connection = Proxy.newProxyInstance(
            SQLiteConnection::class.java.classLoader,
            arrayOf(SQLiteConnection::class.java)
        ) { _, method, arguments ->
            check(method.name == "prepare")
            executed += arguments?.single() as String
            statement
        } as SQLiteConnection

        migration.migrate(connection)

        return executed
    }
}
