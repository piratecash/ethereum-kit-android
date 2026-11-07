package io.horizontalsystems.ethereumkit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.crypto.CryptoUtils
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastResult
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastStatus
import io.reactivex.Observable
import io.reactivex.Single
import kotlinx.coroutines.rx2.rxSingle
import java.util.concurrent.TimeUnit

class RawTransactionBroadcaster(
    private val blockchain: IBlockchain,
    private val storage: IRawTransactionBroadcastStorage,
    private val currentTime: () -> Long = { System.currentTimeMillis() },
    private val networkTimeoutMs: Long = networkTimeout,
    private val logger: Logger,
) {
    private val inFlight = mutableSetOf<String>()
    private var retryRunning = false

    fun broadcast(rawTransaction: ByteArray): Single<RawTransactionBroadcastResult> = Single.defer {
        if (rawTransaction.isEmpty()) {
            return@defer Single.error(IllegalArgumentException("Raw transaction is empty"))
        }

        val hash = CryptoUtils.sha3(rawTransaction)
        if (!markInFlight(hash)) {
            return@defer rxSingle(rxIoDispatcher) { queueForRetry(hash, rawTransaction) }
        }

        blockchain.sendRawTransaction(rawTransaction)
            .withNetworkTimeout()
            .flatMapPersisting { rpcHash ->
                validateRpcHash(hash, rpcHash)
                deleteQueuedBroadcast(hash)
                RawTransactionBroadcastResult(hash, RawTransactionBroadcastStatus.Submitted)
            }
            .onErrorResumeNext { error: Throwable ->
                handleInitialBroadcastError(hash, rawTransaction, error)
            }
            .doFinally {
                clearInFlight(hash)
            }
    }

    fun retryQueued(): Single<Unit> = Single.defer {
        if (!beginRetry()) return@defer Single.just(Unit)

        rxSingle(rxIoDispatcher) { storage.getRawTransactionBroadcasts() }
            .flatMapObservable { Observable.fromIterable(it) }
            .concatMapSingle { record ->
                retry(record).onErrorReturn { error ->
                    logger.w(error) { "Raw transaction retry failed unexpectedly." }
                    Unit
                }
            }
            .ignoreElements()
            .toSingleDefault(Unit)
            .doFinally {
                endRetry()
            }
    }

    private fun retry(record: RawTransactionBroadcastRecord): Single<Unit> = Single.defer {
        val now = currentTime()
        if (record.expiresAt <= now || record.retriesCount >= maxRetriesCount) {
            return@defer rxSingle(rxIoDispatcher) {
                storage.deleteRawTransactionBroadcast(record)
                logger.w { "Dropping raw transaction broadcast after retries=${record.retriesCount}." }
            }
        }

        if (record.lastSendTime > now - retriesPeriod) {
            return@defer Single.just(Unit)
        }

        if (!markInFlight(record.hash)) {
            return@defer Single.just(Unit)
        }

        blockchain.transactionExists(record.hash, networkTimeoutMs)
            .flatMap { exists ->
                if (exists) {
                    rxSingle(rxIoDispatcher) { storage.deleteRawTransactionBroadcast(record) }
                } else {
                    retryBroadcast(record, now)
                }
            }
            .doFinally {
                clearInFlight(record.hash)
            }
    }

    private fun retryBroadcast(record: RawTransactionBroadcastRecord, now: Long): Single<Unit> {
        return blockchain.sendRawTransaction(record.rawTransaction)
            .withNetworkTimeout()
            .flatMapPersisting { rpcHash ->
                validateRpcHash(record.hash, rpcHash)
                storage.deleteRawTransactionBroadcast(record)
            }
            .onErrorResumeNext { error: Throwable ->
                handleRetryBroadcastError(record, error, now)
            }
    }

    private fun handleInitialBroadcastError(
        hash: ByteArray,
        rawTransaction: ByteArray,
        error: Throwable
    ): Single<RawTransactionBroadcastResult> {
        if (error is UnsupportedOperationException) {
            return persisting {
                deleteQueuedBroadcast(hash)
                throw error
            }
        }

        if (error.isKnownTransactionError()) {
            return persisting {
                deleteQueuedBroadcast(hash)
                RawTransactionBroadcastResult(hash, RawTransactionBroadcastStatus.AlreadyKnown)
            }
        }

        if (isPermanentError(error)) {
            return blockchain.transactionExists(hash, networkTimeoutMs).flatMapPersisting { exists ->
                deleteQueuedBroadcast(hash)
                if (!exists) throw error
                RawTransactionBroadcastResult(hash, RawTransactionBroadcastStatus.AlreadyKnown)
            }
        }

        return persisting { queueForRetry(hash, rawTransaction) }
    }

    private fun handleRetryBroadcastError(
        record: RawTransactionBroadcastRecord,
        error: Throwable,
        now: Long
    ): Single<Unit> {
        if (error is UnsupportedOperationException) {
            return persisting { storage.deleteRawTransactionBroadcast(record) }
        }

        if (error.isKnownTransactionError()) {
            return persisting { storage.deleteRawTransactionBroadcast(record) }
        }

        if (isPermanentError(error)) {
            return blockchain.transactionExists(record.hash, networkTimeoutMs).flatMapPersisting { exists ->
                storage.deleteRawTransactionBroadcast(record)
                if (!exists) {
                    logger.w(error) { "Dropping raw transaction broadcast after permanent error." }
                }
            }
        }

        return persisting {
            storage.updateRawTransactionBroadcast(
                record.copy(
                    lastSendTime = now,
                    retriesCount = record.retriesCount + 1,
                )
            )
        }
    }

    private suspend fun deleteQueuedBroadcast(hash: ByteArray) {
        storage.getRawTransactionBroadcast(hash)?.let { storage.deleteRawTransactionBroadcast(it) }
    }

    private suspend fun queueForRetry(hash: ByteArray, rawTransaction: ByteArray): RawTransactionBroadcastResult {
        val now = currentTime()
        val existingRecord = storage.getRawTransactionBroadcast(hash)

        if (existingRecord == null) {
            storage.addRawTransactionBroadcast(
                RawTransactionBroadcastRecord(
                    hash = hash,
                    rawTransaction = rawTransaction,
                    firstSendTime = now,
                    lastSendTime = now,
                    retriesCount = 0,
                    expiresAt = now + retryTtl,
                )
            )
        } else {
            storage.updateRawTransactionBroadcast(
                existingRecord.copy(
                    rawTransaction = rawTransaction,
                    lastSendTime = now,
                )
            )
        }

        return RawTransactionBroadcastResult(hash, RawTransactionBroadcastStatus.Queued)
    }

    private fun validateRpcHash(expectedHash: ByteArray, rpcHash: ByteArray) {
        if (rpcHash.isNotEmpty() && !rpcHash.contentEquals(expectedHash)) {
            throw RpcHashMismatch()
        }
    }

    @Synchronized
    private fun beginRetry(): Boolean {
        if (retryRunning) return false
        retryRunning = true
        return true
    }

    @Synchronized
    private fun endRetry() {
        retryRunning = false
    }

    @Synchronized
    private fun markInFlight(hash: ByteArray): Boolean {
        return inFlight.add(hash.toRawHexString())
    }

    @Synchronized
    private fun clearInFlight(hash: ByteArray) {
        inFlight.remove(hash.toRawHexString())
    }

    private fun isPermanentError(error: Throwable): Boolean {
        return error is RpcHashMismatch || error.isPermanentBroadcastError()
    }

    private fun <T> Single<T>.withNetworkTimeout(): Single<T> {
        return timeout(networkTimeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val maxRetriesCount = 10
        const val retriesPeriod = 60_000L
        const val retryTtl = 24 * 60 * 60 * 1000L
        const val networkTimeout = 30_000L
    }

    private class RpcHashMismatch : IllegalStateException("RPC returned a different transaction hash")
}
