package io.horizontalsystems.ethereumkit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.models.ProviderEip1155Transaction
import io.horizontalsystems.ethereumkit.models.ProviderEip721Transaction
import io.horizontalsystems.ethereumkit.models.ProviderInternalTransaction
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.ProviderTransaction
import io.reactivex.Single
import java.time.Instant

/**
 * Asks the configured explorers in their configured order, so a source that is out of credits or
 * down costs one failed call instead of the whole transaction history. Stateless per call: the
 * primary is tried again on every sync, so a recovered source is used again without a restart.
 */
class FallbackTransactionProvider(
    private val sources: List<Source>,
    private val log: Logger
) : ITransactionProvider {

    class Source(val host: String, val provider: ITransactionProvider)

    @Volatile
    private var lastSuccessfulHost: String? = null

    @Volatile
    private var lastError: String? = null

    private val orderedHosts = sources.joinToString(" -> ") { it.host }

    override val statusInfo: Map<String, Any>
        get() = buildMap {
            put("Transactions source", orderedHosts)
            lastSuccessfulHost?.let { put("Last history source", it) }
            lastError?.let { put("Last explorer error", it) }
        }

    override fun getTransactions(startBlock: Long): Single<List<ProviderTransaction>> =
        firstSuccessful { it.getTransactions(startBlock) }

    override fun getInternalTransactions(startBlock: Long): Single<List<ProviderInternalTransaction>> =
        firstSuccessful { it.getInternalTransactions(startBlock) }

    override fun getInternalTransactionsAsync(hash: ByteArray): Single<List<ProviderInternalTransaction>> =
        firstSuccessful { it.getInternalTransactionsAsync(hash) }

    override fun getTokenTransactions(startBlock: Long): Single<List<ProviderTokenTransaction>> =
        firstSuccessful { it.getTokenTransactions(startBlock) }

    override fun getEip721Transactions(startBlock: Long): Single<List<ProviderEip721Transaction>> =
        firstSuccessful { it.getEip721Transactions(startBlock) }

    override fun getEip1155Transactions(startBlock: Long): Single<List<ProviderEip1155Transaction>> =
        firstSuccessful { it.getEip1155Transactions(startBlock) }

    private fun <T> firstSuccessful(call: (ITransactionProvider) -> Single<T>): Single<T> =
        sources.indices.drop(1)
            .fold(attempt(sources.first(), call)) { chain, index ->
                chain.onErrorResumeNext { error: Throwable ->
                    log.w { "${sources[index - 1].host} failed (${error.errorName()}), switching to ${sources[index].host}" }
                    attempt(sources[index], call)
                }
            }
            .doOnError { log.w { "all transaction sources failed (${it.errorName()})" } }

    private fun <T> attempt(source: Source, call: (ITransactionProvider) -> Single<T>): Single<T> =
        call(source.provider)
            .doOnSuccess { lastSuccessfulHost = source.host }
            .doOnError { lastError = "${Instant.now()} ${source.host} ${it.errorName()}" }

    // Only the error class: an OkHttp message can carry the full URL, and that carries the API key.
    private fun Throwable.errorName() = javaClass.simpleName
}
