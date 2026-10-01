package io.horizontalsystems.ethereumkit.core.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class TransactionMigrationsTest {

    @Test
    fun migration16_17_migrate_resetsTransactionSyncCursor() {
        var executedSql: String? = null
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
            executedSql = arguments?.single() as? String
            statement
        } as SQLiteConnection

        migration16_17.migrate(connection)

        assertEquals("DELETE FROM `TransactionSyncerState`", executedSql)
    }
}
