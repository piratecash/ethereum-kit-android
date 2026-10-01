package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.models.Address
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxSingle
import java.math.BigInteger

class BalanceManager(private val contractAddress: Address,
                     private val address: Address,
                     private val storage: ITokenBalanceStorage,
                     private val dataProvider: IDataProvider,
                     storedBalance: BigInteger?) : IBalanceManager {

    private val disposables = CompositeDisposable()

    override var listener: IBalanceManagerListener? = null

    @Volatile
    override var balance: BigInteger? = storedBalance
        private set

    override fun sync() {
        dataProvider.getBalance(contractAddress, address)
                .flatMap { balance ->
                    rxSingle(Dispatchers.IO) {
                        storage.save(balance)
                        balance
                    }
                }
                .subscribeOn(Schedulers.io())
                .subscribe({ balance ->
                    this.balance = balance
                    listener?.onSyncBalanceSuccess(balance)
                }, {
                    listener?.onSyncBalanceError(it)
                })
                .let {
                    disposables.add(it)
                }
    }

}
