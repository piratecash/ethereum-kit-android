package io.horizontalsystems.ethereumkit.core

import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.rx2.asCoroutineDispatcher

/** Storage work bridged into an Rx chain stays on the Rx io threads the blocking DAOs ran on. */
internal val rxIoDispatcher: CoroutineDispatcher
    get() = Schedulers.io().asCoroutineDispatcher()
