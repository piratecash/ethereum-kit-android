package io.horizontalsystems.ethereumkit.core.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class TransactionMigrationsTest {

    @Test
    fun migration16_17_migrate_resetsTransactionSyncCursor() {
        var executedSql: String? = null
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, arguments ->
            check(method.name == "execSQL")
            executedSql = arguments?.single() as? String
            null
        } as SupportSQLiteDatabase

        migration16_17.migrate(database)

        assertEquals("DELETE FROM `TransactionSyncerState`", executedSql)
    }
}
