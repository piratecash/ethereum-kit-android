package io.horizontalsystems.ethereumkit.api.storage

import androidx.room.*
import io.horizontalsystems.ethereumkit.api.models.LastBlockHeight

@Dao
interface LastBlockHeightDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lastBlockHeight: LastBlockHeight)

    @Query("SELECT * FROM LastBlockHeight")
    suspend fun getLastBlockHeight(): LastBlockHeight?

}
