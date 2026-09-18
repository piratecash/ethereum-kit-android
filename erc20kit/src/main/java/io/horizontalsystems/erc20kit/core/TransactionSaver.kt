package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction

class TransactionSaver(
    private val storage: IEip20Storage
) {
    fun handle(transactions: List<ProviderTokenTransaction>) {
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