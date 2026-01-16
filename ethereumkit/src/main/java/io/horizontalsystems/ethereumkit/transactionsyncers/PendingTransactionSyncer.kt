package io.horizontalsystems.ethereumkit.transactionsyncers

import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransactionReceipt
import io.horizontalsystems.ethereumkit.core.IBlockchain
import io.horizontalsystems.ethereumkit.core.ITransactionStorage
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.models.Transaction
import io.reactivex.Single
import timber.log.Timber

/**
 * Syncer that checks pending transactions (blockNumber = null) for confirmation status.
 *
 * When a transaction is sent from the wallet, it's stored with blockNumber = null.
 * This syncer queries the blockchain via RPC to check if pending transactions
 * have been confirmed (included in a block), and updates their blockNumber accordingly.
 */
class PendingTransactionSyncer(
    private val storage: ITransactionStorage,
    private val blockchain: IBlockchain
) : ITransactionSyncer {

    override fun getTransactionsSingle(): Single<Pair<List<Transaction>, Boolean>> {
        val pendingTransactions = storage.getPendingTransactions()

        if (pendingTransactions.isEmpty()) {
            return Single.just(Pair(listOf(), false))
        }

        Timber.i("Checking ${pendingTransactions.size} pending transaction(s) for confirmation")

        val singles = pendingTransactions.map { pendingTx ->
            blockchain.getTransactionReceipt(pendingTx.hash)
                .map { receipt ->
                    // Transaction is confirmed - update with block info
                    Transaction(
                        hash = pendingTx.hash,
                        timestamp = pendingTx.timestamp,
                        isFailed = determineFailedStatus(receipt, pendingTx.gasLimit),
                        blockNumber = receipt.blockNumber,
                        transactionIndex = receipt.transactionIndex.toInt(),
                        from = receipt.from,  // Use authoritative source from receipt
                        to = receipt.to ?: pendingTx.to,  // Receipt to can be null for contract deployments
                        value = pendingTx.value,
                        input = pendingTx.input,
                        nonce = pendingTx.nonce,
                        gasPrice = receipt.effectiveGasPrice,  // Use actual gas price paid
                        maxFeePerGas = pendingTx.maxFeePerGas,
                        maxPriorityFeePerGas = pendingTx.maxPriorityFeePerGas,
                        gasLimit = pendingTx.gasLimit,
                        gasUsed = receipt.gasUsed
                    )
                }
                .onErrorResumeNext { error ->
                    Timber.e("Network error checking ${pendingTx.hashString}: ${error.message}")
                    Single.just(pendingTx)
                }
        }

        return Single.zip(singles) { results ->
            val transactions = results.map { it as Transaction }
            val confirmedTransactions = transactions.filter { it.blockNumber != null }

            if (confirmedTransactions.isNotEmpty()) {
                Timber.i("Found ${confirmedTransactions.size} confirmed transaction(s) out of ${transactions.size}")
            }

            // Only return transactions that have been updated (confirmed)
            Pair(confirmedTransactions, false)
        }
    }

    /**
     * Determines if a transaction failed based on the receipt status.
     *
     * Post-Byzantium (EIP-658): status field is present
     *   - status == 0: transaction failed (reverted)
     *   - status == 1: transaction succeeded
     *
     * Pre-Byzantium: status field is null
     *   - Fallback heuristic: if all gas was consumed, transaction likely failed
     *   - This is not 100% reliable but is the best available indicator
     *
     * @param receipt The transaction receipt from the blockchain
     * @param gasLimit The gas limit from the original transaction (for fallback heuristic)
     * @return true if the transaction failed, false otherwise
     */
    private fun determineFailedStatus(receipt: RpcTransactionReceipt, gasLimit: Long?): Boolean {
        return when (receipt.status) {
            0L -> true   // Explicitly failed (post-Byzantium)
            1L -> false  // Explicitly succeeded (post-Byzantium)
            null -> {
                // Pre-Byzantium fallback: if all gas was consumed, likely failed
                // Note: This heuristic can have false positives for gas-intensive operations
                gasLimit?.let { limit -> receipt.gasUsed >= limit } ?: false
            }
            else -> false  // Unknown status value, assume success
        }
    }
}
