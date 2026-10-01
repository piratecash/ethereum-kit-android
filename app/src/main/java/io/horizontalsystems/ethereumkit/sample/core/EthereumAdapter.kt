package io.horizontalsystems.ethereumkit.sample.core

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.signer.Signer
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.FullTransaction
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.reactivex.Single
import kotlinx.coroutines.rx2.rxSingle
import java.math.BigDecimal

class EthereumAdapter(
        private val ethereumKit: EthereumKit,
        private val signer: Signer
) : EthereumBaseAdapter(ethereumKit) {

    private val decimal = 18

    override fun send(
            address: Address,
            amount: BigDecimal,
            gasPrice: GasPrice,
            gasLimit: Long
    ): Single<FullTransaction> {
        val amountBigInt = amount.movePointRight(decimal).toBigInteger()
        val transactionData = ethereumKit.transferTransactionData(address, amountBigInt)
        return ethereumKit.rawTransaction(transactionData, gasPrice, gasLimit)
                .flatMap { rawTransaction ->
                    rxSingle { signer.signature(rawTransaction) }
                        .flatMap { signature -> ethereumKit.send(rawTransaction, signature) }
                }
    }

}
