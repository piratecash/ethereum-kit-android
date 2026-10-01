package io.horizontalsystems.nftkit.core

import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.nftkit.models.Nft
import io.horizontalsystems.nftkit.models.NftBalance
import io.horizontalsystems.nftkit.models.NftType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigInteger

class BalanceManager(
    private val balanceSyncManager: BalanceSyncManager,
    private val storage: Storage,
    existingNftBalances: List<NftBalance>
) : IBalanceSyncManagerListener {
    private val _nftBalances = MutableStateFlow(existingNftBalances)

    val nftBalancesFlow: Flow<List<NftBalance>>
        get() = _nftBalances.asStateFlow()

    val nftBalances: List<NftBalance>
        get() = _nftBalances.value

    suspend fun nftBalance(contractAddress: Address, tokenId: BigInteger): NftBalance? =
        storage.existingNftBalance(contractAddress, tokenId)

    private suspend fun handleNftsFromTransactions(type: NftType, nfts: List<Nft>) {
        val existingBalances = storage.nftBalances(type)
        val existingNfts = existingBalances.map { it.nft }
        val newNfts = nfts.filter { !existingNfts.contains(it) }

        storage.setNotSynced(existingNfts)
        storage.saveNftBalances(newNfts.map { NftBalance(it, 0, false) })

        balanceSyncManager.sync()
    }

    private suspend fun syncNftBalances() {
        _nftBalances.value = storage.existingNftBalances()
    }

    suspend fun didSync(nfts: List<Nft>, type: NftType) {
        handleNftsFromTransactions(type, nfts)
    }

    override suspend fun didFinishSyncBalances() {
        syncNftBalances()
    }
}