package io.horizontalsystems.ethereumkit.fixture

import io.horizontalsystems.ethereumkit.api.models.AccountState
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.Eip20SyncState
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionSyncerState
import io.horizontalsystems.ethereumkit.models.TransactionTag
import java.math.BigInteger

/** Expected content of the plaintext fixture databases in `src/test/resources/databases`; no platform dependencies. */
object EthereumKitFixture {
    const val API_DB = "Ethereum-1-fixturewallet-api"
    const val TRANSACTIONS_DB = "Ethereum-1-fixturewallet-txs"
    const val EIP20_EVENTS_DB = "Ethereum-1-fixturewallet-erc20_events"
    const val EIP20_CURSOR_KEY = "0x0000000000000000000000000000000000000000"

    val account = Address("0x1111111111111111111111111111111111111111")
    val counterparty = Address("0x2222222222222222222222222222222222222222")
    val usdt = Address("0xdac17f958d2ee523a2206206994597c13d831ec7")

    const val LAST_BLOCK_HEIGHT = 20_000_000L
    val accountState = AccountState(balance = BigInteger("123456789012345678901234567890"), nonce = 42)

    fun hash(seed: Int) = ByteArray(32) { seed.toByte() }

    val incomingTransaction = Transaction(
        hash = hash(0x11), timestamp = 1_700_000_300, isFailed = false,
        blockNumber = 18_500_000, transactionIndex = 5, from = counterparty, to = account,
        value = BigInteger.TEN.pow(18), input = null, nonce = 3,
        gasPrice = 30_000_000_000, gasLimit = 21_000, gasUsed = 21_000
    )
    val tokenTransferTransaction = Transaction(
        hash = hash(0x22), timestamp = 1_700_000_200, isFailed = false,
        blockNumber = 18_499_990, transactionIndex = 2, from = account, to = usdt,
        value = BigInteger.ZERO, input = byteArrayOf(0xa9.toByte(), 0x05, 0x9c.toByte(), 0xbb.toByte(), 1, 2, 3), nonce = 6,
        maxFeePerGas = 40_000_000_000, maxPriorityFeePerGas = 2_000_000_000, gasLimit = 65_000, gasUsed = 51_234
    )
    val pendingTransaction = Transaction(
        hash = hash(0x33), timestamp = 1_700_000_400, isFailed = false,
        from = account, to = counterparty, value = BigInteger("5000000000000000"), nonce = 7,
        maxFeePerGas = 50_000_000_000, maxPriorityFeePerGas = 3_000_000_000, gasLimit = 21_000
    )
    val failedTransaction = Transaction(
        hash = hash(0x44), timestamp = 1_700_000_100, isFailed = true,
        blockNumber = 18_499_900, transactionIndex = 1, from = account, to = counterparty,
        value = BigInteger.ONE, nonce = 5, gasPrice = 25_000_000_000, gasLimit = 21_000, gasUsed = 21_000,
        replacedWith = hash(0x33)
    )
    val transactions = listOf(incomingTransaction, tokenTransferTransaction, pendingTransaction, failedTransaction)

    val tags = listOf(
        TransactionTag(TransactionTag.EVM_COIN_INCOMING, hash(0x11)),
        TransactionTag(TransactionTag.INCOMING, hash(0x11)),
        TransactionTag(TransactionTag.fromAddress(counterparty.hex), hash(0x11)),
        TransactionTag(TransactionTag.tokenOutgoing(usdt.hex), hash(0x22)),
        TransactionTag(TransactionTag.EIP20_TRANSFER, hash(0x22)),
        TransactionTag(TransactionTag.OUTGOING, hash(0x22)),
        TransactionTag(TransactionTag.EVM_COIN_OUTGOING, hash(0x33)),
        TransactionTag(TransactionTag.OUTGOING, hash(0x33)),
        TransactionTag(TransactionTag.EVM_COIN_OUTGOING, hash(0x44))
    )

    val internalTransaction = InternalTransaction(
        hash = hash(0x11), traceId = "0_1", blockNumber = 18_500_000,
        from = counterparty, to = account, value = BigInteger("42")
    )

    val syncerStates = listOf(TransactionSyncerState("ethereum", 18_500_000), TransactionSyncerState("eip20", 18_499_990))

    val syncSources = listOf(hash(0x11) to SyncSource.ETHERSCAN, hash(0x33) to SyncSource.MERKLE)

    val rawBroadcasts = listOf(
        RawTransactionBroadcastRecord(
            hash = hash(0x33), rawTransaction = byteArrayOf(0x02, 0x01, 0x02, 0x03),
            firstSendTime = 1_700_000_400_000, lastSendTime = 1_700_000_400_000, retriesCount = 0, expiresAt = 1_700_086_800_000
        ),
        RawTransactionBroadcastRecord(
            hash = hash(0x55), rawTransaction = byteArrayOf(0x02, 0x0a, 0x0b),
            firstSendTime = 1_700_000_500_000, lastSendTime = 1_700_000_560_000, retriesCount = 3, expiresAt = 1_700_086_900_000
        )
    )

    val eip20Events = listOf(
        Eip20Event(
            hash = hash(0x22), blockNumber = 18_499_990, contractAddress = usdt, from = account, to = counterparty,
            value = BigInteger("1000000"), tokenName = "Tether USD", tokenSymbol = "USDT", tokenDecimal = 6
        ),
        Eip20Event(
            hash = hash(0x66), blockNumber = 18_400_000, contractAddress = usdt, from = counterparty, to = account,
            value = BigInteger("2500000"), tokenName = "Tether USD", tokenSymbol = "USDT", tokenDecimal = 6
        )
    )
    val eip20SyncState = Eip20SyncState(EIP20_CURSOR_KEY, lastScannedBlock = 18_500_000, historicalMinScannedBlock = 17_000_000)

    fun Transaction.snapshot() = listOf(
        hash.toHexString(), timestamp, isFailed, blockNumber, transactionIndex, from?.hex, to?.hex, value,
        input.toHexString(), nonce, gasPrice, maxFeePerGas, maxPriorityFeePerGas, gasLimit, gasUsed, replacedWith.toHexString()
    )

    fun InternalTransaction.snapshot() = listOf(hash.toHexString(), traceId, blockNumber, from.hex, to.hex, value)

    fun TransactionTag.snapshot() = listOf(name, hash.toHexString())

    fun RawTransactionBroadcastRecord.snapshot() = listOf(
        hash.toHexString(), rawTransaction.toHexString(), firstSendTime, lastSendTime, retriesCount, expiresAt
    )

    fun Eip20Event.snapshot() = listOf(
        hash.toHexString(), blockNumber, contractAddress.hex, from.hex, to.hex, value, tokenName, tokenSymbol, tokenDecimal
    )
}
