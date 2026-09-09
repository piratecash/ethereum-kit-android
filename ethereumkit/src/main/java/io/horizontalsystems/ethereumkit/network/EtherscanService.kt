package io.horizontalsystems.ethereumkit.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import io.horizontalsystems.ethereumkit.api.models.EtherscanResponse
import io.horizontalsystems.ethereumkit.core.retryWhenErrors
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.reactivex.Single
import okhttp3.EventListener
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.random.Random

class EtherscanService(
    baseUrl: String,
    apiKeys: List<String>,
    private val chainId: Int,
    eventListenerFactory: EventListener.Factory? = null,
) {
    private val apiKeys = apiKeys.filter { it.isNotBlank() }
    private val apiKeyIndex =
        AtomicInteger(if (this.apiKeys.isEmpty()) 0 else Random.nextInt(this.apiKeys.size))

    @Volatile
    private var lastUsedApiKey: String? = null

    private val logger = Logger.getLogger("EtherscanService")

    private val service: EtherscanServiceAPI

    private val gson: Gson

    init {
        val loggingInterceptor = HttpLoggingInterceptor {
            logger.info(it.replace(apiKeyRegex, "apikey=***"))
        }.setLevel(HttpLoggingInterceptor.Level.BASIC)

        val httpClient = SharedHttpClient.newClient {
            eventListenerFactory?.let { eventListenerFactory(it) }
            addInterceptor { chain ->
                val originalRequest = chain.request()
                val originalUrl = originalRequest.url

                val urlBuilder = originalUrl.newBuilder()
                    .addQueryParameter("chainid", chainId.toString())
                getNextApiKey()?.let { urlBuilder.addQueryParameter("apikey", it) }

                val request = originalRequest.newBuilder()
                    .header("User-Agent", "Mobile App Agent")
                    .url(urlBuilder.build())
                    .build()

                chain.proceed(request)
            }
            addInterceptor(loggingInterceptor)
        }

        gson = GsonBuilder()
            .setLenient()
            .create()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(httpClient)
            .build()

        service = retrofit.create(EtherscanServiceAPI::class.java)
    }

    private fun getNextApiKey(): String? {
        if (apiKeys.isEmpty()) return null

        val index = apiKeyIndex.getAndUpdate { i ->
            if (i + 1 >= apiKeys.size) 0 else i + 1
        }
        return apiKeys[index].also {
            lastUsedApiKey = it
        }
    }

    fun getTransactionList(address: Address, startBlock: Long): Single<EtherscanResponse> {
        return service.accountApi(
            action = "txlist",
            address = address.hex,
            startBlock = startBlock,
        ).map {
            parseResponse(it)
        }.retryInCaseErrorWithLogging("getTransactionList")
    }

    fun getInternalTransactionList(address: Address, startBlock: Long): Single<EtherscanResponse> {
        return service.accountApi(
            action = "txlistinternal",
            address = address.hex,
            startBlock = startBlock,
            sort = "asc",
        ).map {
            parseResponse(it)
        }.retryInCaseErrorWithLogging("getInternalTransactionList")
    }

    fun getTokenTransactions(address: Address, startBlock: Long): Single<EtherscanResponse> {
        return service.accountApi(
            action = "tokentx",
            address = address.hex,
            startBlock = startBlock,
        ).map {
            parseResponse(it)
        }.retryInCaseErrorWithLogging("getTokenTransactions")
    }

    fun getInternalTransactionsAsync(transactionHash: ByteArray): Single<EtherscanResponse> {
        return service.accountApi(
            action = "txlistinternal",
            txHash = transactionHash.toHexString(),
        ).map {
            parseResponse(it)
        }.retryInCaseErrorWithLogging("getInternalTransactionsAsync")
    }

    fun getEip721Transactions(address: Address, startBlock: Long): Single<EtherscanResponse> {
        return service.accountApi(
            action = "tokennfttx",
            address = address.hex,
            startBlock = startBlock,
        ).map {
            parseResponse(it)
        }.retryInCaseErrorWithLogging("getEip721Transactions")
    }

    fun getEip1155Transactions(address: Address, startBlock: Long): Single<EtherscanResponse> {
        return service.accountApi(
            action = "token1155tx",
            address = address.hex,
            startBlock = startBlock,
        ).map {
            parseResponse(it)
        }.retryInCaseErrorWithLogging("getEip1155Transactions")
    }

    private fun parseResponse(response: JsonElement): EtherscanResponse {
        try {
            val responseObj = response.asJsonObject
            val status = responseObj["status"].asJsonPrimitive.asString
            val message = responseObj["message"].asJsonPrimitive.asString

            // A "status 0" envelope with an array result is a normal empty list, not an error text.
            val resultElement = responseObj["result"]
            val errorText = resultElement?.takeIf { it.isJsonPrimitive }?.asString
            if (status == "0" && message == "NOTOK" && errorText != null) {
                if (errorText == "Max rate limit reached") {
                    throw RequestError.RateLimitExceed()
                } else if (errorText.startsWith("Invalid API Key") ||
                    errorText.startsWith("Too many invalid api key attempts")
                ) {
                    throw RequestError.InvalidApiKey()
                }
            }
            val result: List<Map<String, String>> = gson.fromJson(
                resultElement,
                object : TypeToken<List<Map<String, String>>>() {}.type
            )
            return EtherscanResponse(status, message, result)

        } catch (rateLimitExceeded: RequestError.RateLimitExceed) {
            throw rateLimitExceeded
        } catch (invalidApiKey: RequestError.InvalidApiKey) {
            throw invalidApiKey
        } catch (err: Throwable) {
            throw RequestError.ResponseError("Unexpected response: $response")
        }
    }

    private fun <T> Single<T>.retryInCaseErrorWithLogging(methodName: String) =
        this.onErrorResumeNext { error: Throwable -> Single.error(error.toRequestErrorOrSelf()) }
            .doOnError { error ->
                val currentApiKey = lastUsedApiKey?.takeLast(4) ?: "unknown"
                when (error) {
                    is RequestError.RateLimitExceed -> {
                        Timber.d("EtherscanService: Retrying $methodName due to RateLimitExceed. API key ending in $currentApiKey")
                    }
                    is RequestError.InvalidApiKey -> {
                        Timber.d("EtherscanService: Retrying $methodName due to InvalidApiKey. API key ending in $currentApiKey")
                    }
                }
            }.retryWhenErrors(
                RequestError.RateLimitExceed::class,
                RequestError.InvalidApiKey::class
            )

    // Blockscout PRO answers with an HTTP status and a bare {"error": ...} body, not the Etherscan envelope.
    private fun Throwable.toRequestErrorOrSelf(): Throwable = when ((this as? HttpException)?.code()) {
        429 -> RequestError.RateLimitExceed()
        401, 402 -> RequestError.InvalidApiKey()
        else -> this
    }

    open class RequestError(message: String? = null) : Exception(message ?: "") {
        class ResponseError(message: String) : RequestError(message)
        class RateLimitExceed : RequestError()
        class InvalidApiKey : RequestError()
    }

    companion object {
        private val apiKeyRegex = Regex("apikey=[^&\\s]+")
    }

    private interface EtherscanServiceAPI {
        @GET("api")
        fun accountApi(
            @Query("module") module: String = "account",
            @Query("action") action: String,
            @Query("address") address: String? = null,
            @Query("txhash") txHash: String? = null,
            @Query("startblock") startBlock: Long? = null,
            @Query("endblock") endBlock: Long? = null,
            @Query("sort") sort: String? = "desc"
        ): Single<JsonElement>
    }
}
