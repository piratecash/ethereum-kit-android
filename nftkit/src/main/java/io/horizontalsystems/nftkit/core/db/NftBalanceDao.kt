package io.horizontalsystems.nftkit.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.nftkit.models.NftBalance
import io.horizontalsystems.nftkit.models.NftBalanceRecord
import io.horizontalsystems.nftkit.models.NftType
import java.math.BigInteger

@Dao
interface NftBalanceDao {

    @Query("SELECT * FROM NftBalanceRecord WHERE type = :type")
    suspend fun nftBalances(type: NftType): List<NftBalance>

    @Query("SELECT * FROM NftBalanceRecord WHERE balance > 0")
    suspend fun existingNftBalances(): List<NftBalance>

    @Query("SELECT * FROM NftBalanceRecord WHERE synced = 0")
    suspend fun nonSyncedNftBalances(): List<NftBalance>

    @Query("SELECT * FROM NftBalanceRecord WHERE contractAddress = :contractAddress AND tokenId = :tokenId AND balance > 0")
    suspend fun existingNftBalance(contractAddress: Address, tokenId: BigInteger): NftBalance?

    @Query("UPDATE NftBalanceRecord SET synced = 1, balance = :balance WHERE contractAddress = :contractAddress AND tokenId = :tokenId")
    suspend fun setSynced(contractAddress: Address, tokenId: BigInteger, balance: Int)

    @Query("UPDATE NftBalanceRecord SET synced = 0 WHERE contractAddress = :contractAddress AND tokenId = :tokenId")
    suspend fun setNotSynced(contractAddress: Address, tokenId: BigInteger)

    @Insert
    suspend fun insertAll(balances: List<NftBalanceRecord>)
}