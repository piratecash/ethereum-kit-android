package io.horizontalsystems.ethereumkit.core

import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.rx2.asCoroutineDispatcher
import kotlinx.coroutines.rx2.rxSingle

/** Storage work bridged into an Rx chain stays on the Rx io threads the blocking DAOs ran on. */
internal val rxIoDispatcher: CoroutineDispatcher
    get() = Schedulers.io().asCoroutineDispatcher()

// Never cancelled: a disposed subscriber must not stop recording what the network already accepted.
private val persistenceScope = CoroutineScope(SupervisorJob())

/**
 * [Single.flatMap] into [persist], which runs to the end once this Single succeeds, even if the subscriber
 * disposes; for recording the outcome of a broadcast. Public only for the kit's modules.
 */
fun <T : Any, R : Any> Single<T>.flatMapPersisting(persist: suspend (T) -> R): Single<R> =
    flatMap { value -> persisting { persist(value) } }

/** Starts [persist] at the call, not on subscription, so disposing the returned Single cannot skip it. */
internal fun <R : Any> persisting(persist: suspend () -> R): Single<R> {
    val result = persistenceScope.async(rxIoDispatcher) { persist() }
    return rxSingle(rxIoDispatcher) { result.await() }
}
