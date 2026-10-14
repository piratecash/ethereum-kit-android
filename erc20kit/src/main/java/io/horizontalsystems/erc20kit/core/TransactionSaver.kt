package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction

class TransactionSaver(
    private val storage: IEip20Storage
) {
    /** With [replaceExisting] false the save is purely additive: stored events are never deleted or rewritten. */
    suspend fun handle(transactions: List<ProviderTokenTransaction>, replaceExisting: Boolean = true) {
        if (transactions.isEmpty()) return

        val events = transactions.map { tx ->
            Eip20Event(
                hash = tx.hash,
                blockNumber = tx.blockNumber,
                contractAddress = tx.contractAddress,
                from = tx.from,
                to = tx.to,
                value = tx.value,
                tokenName = tx.tokenName,
                tokenSymbol = tx.tokenSymbol,
                tokenDecimal = tx.tokenDecimal
            )
        }

        if (!replaceExisting) {
            storage.saveIfAbsent(events)
            return
        }

        // Delete zero-value duplicates before saving new events with correct values
        events.forEach { newEvent ->
            if (newEvent.value > java.math.BigInteger.ZERO) {
                storage.deleteZeroValueDuplicate(
                    hash = newEvent.hash,
                    contractAddress = newEvent.contractAddress,
                    from = newEvent.from,
                    to = newEvent.to
                )
            }
        }

        storage.save(events)
    }
}