package io.horizontalsystems.merkleiokit

import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.api.core.ApiRpcSyncer
import io.horizontalsystems.ethereumkit.api.core.NodeApiProvider
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.TransactionBuilder
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.core.kitLogger
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.database.EthereumKitDatabases
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.models.FullTransaction
import io.horizontalsystems.ethereumkit.models.RawTransaction
import io.horizontalsystems.ethereumkit.models.Signature
import io.horizontalsystems.ethereumkit.network.ConnectionManager
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.reactivex.Single
import okhttp3.EventListener
import java.net.URI

class MerkleTransactionAdapter(
    val blockchain: MerkleRpcBlockchain,
    val syncer: MerkleTransactionSyncer,
    private val transactionManager: TransactionManager,
    private val sourceTag: String,
) {
    fun send(rawTransaction: RawTransaction, signature: Signature): Single<FullTransaction> {
        return blockchain.send(rawTransaction, signature, sourceTag) { transaction ->
            transactionManager.handle(listOf(transaction)).first()
        }
    }

    fun registerInKit(ethereumKit: EthereumKit) {
        ethereumKit.addNonceProvider(blockchain)
        ethereumKit.addTransactionSyncer(syncer)
        ethereumKit.addExtraDecorator(syncer)
    }

    companion object {
        val protectedKey = "protected"

        private val blockchainPathMap = mapOf(
            Chain.Ethereum to "eth",
            Chain.BinanceSmartChain to "bsc",
            Chain.Base to "base"
        )

        fun isProtected(transaction: FullTransaction): Boolean {
            return transaction.extra[protectedKey] == true
        }

        /**
         * Null for a chain Merkle does not serve. Opens the database with [databaseKey] (exactly 32 bytes);
         * run [migrateDatabase] with the same key first. Arguments and failures as in [EthereumKit.getInstance].
         */
        suspend fun getInstance(
            merkleIoPubKey: String,
            address: Address,
            chain: Chain,
            context: PlatformContext,
            walletId: String,
            databaseKey: ByteArray,
            transactionManager: TransactionManager,
            sourceTag: String,
            transactionSyncSourceStorage: TransactionSyncSourceStorage,
            eventListenerFactory: EventListener.Factory? = null,
        ): MerkleTransactionAdapter? {
            EthereumKitDatabases.requireValidDatabaseKey(databaseKey)
            EthereumKitDatabases.requireValidWalletId(walletId)
            val baseUrl = "https://mempool.merkle.io/rpc/"
            val blockchainPath = blockchainPathMap[chain] ?: return null

            val url = URI("$baseUrl$blockchainPath/$merkleIoPubKey")
            val rpcProvider = NodeApiProvider(
                listOf(url),
                EthereumKit.gson,
                eventListenerFactory = eventListenerFactory,
                logger = kitLogger(chain.id)
            )

            // Opened first: the syncer registers a connection listener, and nothing may suspend after that.
            val merkleDatabase = EthereumKitDatabases.open {
                MerkleDatabase.getInstance(context, databaseName(chain, walletId), databaseKey)
            }

            val connectionManager = ConnectionManager.getInstance(context)
            val rpcSyncer = ApiRpcSyncer(rpcProvider, connectionManager, chain.syncInterval)

            val transactionBuilder = TransactionBuilder(address, chain.id)

            val merkleTransactionHashManager =
                MerkleTransactionHashManager(merkleDatabase.merkleTransactionDao())

            val blockchain = MerkleRpcBlockchain(
                address = address,
                manager = merkleTransactionHashManager,
                syncer = rpcSyncer,
                transactionBuilder = transactionBuilder
            )

            val syncer = MerkleTransactionSyncer(
                manager = merkleTransactionHashManager,
                blockchain = blockchain,
                transactionManager = transactionManager,
                syncSourceStorage = transactionSyncSourceStorage
            )

            return MerkleTransactionAdapter(blockchain, syncer, transactionManager, sourceTag)
        }

        /** Encrypts the wallet's Merkle database on [chain]; same contract and failures as [EthereumKit.migrateDatabase]. */
        suspend fun migrateDatabase(
            context: PlatformContext,
            chain: Chain,
            walletId: String,
            databaseKey: ByteArray
        ): DatabaseMigrationResult {
            EthereumKitDatabases.requireValidDatabaseKey(databaseKey)
            EthereumKitDatabases.requireValidWalletId(walletId)
            return EthereumKitDatabases.migrate(context, migrationId(chain, walletId), listOf(databaseName(chain, walletId)), databaseKey)
        }

        /** Deletes the wallet's Merkle database on [chain]; same contract as [EthereumKit.clear]. */
        suspend fun clear(context: PlatformContext, chain: Chain, walletId: String) {
            EthereumKitDatabases.requireValidWalletId(walletId)
            EthereumKitDatabases.clear(context, migrationId(chain, walletId), listOf(databaseName(chain, walletId)))
        }

        private fun migrationId(chain: Chain, walletId: String) = EthereumKitDatabases.migrationId("merkle", chain, walletId)

        private fun databaseName(chain: Chain, walletId: String) = "MerkleIo-${chain.id}-$walletId"
    }
}
