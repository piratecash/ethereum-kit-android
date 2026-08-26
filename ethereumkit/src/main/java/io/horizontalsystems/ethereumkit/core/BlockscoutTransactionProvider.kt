package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.ProviderEip1155Transaction
import io.horizontalsystems.ethereumkit.models.ProviderEip721Transaction
import io.horizontalsystems.ethereumkit.models.ProviderInternalTransaction
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.ProviderTransaction
import io.horizontalsystems.ethereumkit.network.BlockscoutInternalTransaction
import io.horizontalsystems.ethereumkit.network.BlockscoutService
import io.horizontalsystems.ethereumkit.network.BlockscoutTokenTransfer
import io.horizontalsystems.ethereumkit.network.BlockscoutTotal
import io.horizontalsystems.ethereumkit.network.BlockscoutTransaction
import io.reactivex.Single
import java.time.Instant

class BlockscoutTransactionProvider(
    private val service: BlockscoutService,
    private val address: Address
) : ITransactionProvider {

    override fun getTransactions(startBlock: Long): Single<List<ProviderTransaction>> =
        service.getTransactions(address.hex, startBlock).map { transactions ->
            transactions.mapNotNull(::mapTransaction)
        }

    override fun getInternalTransactions(
        startBlock: Long
    ): Single<List<ProviderInternalTransaction>> =
        service.getInternalTransactions(address.hex, startBlock).map { transactions ->
            transactions.mapNotNull(::mapInternalTransaction)
        }

    override fun getInternalTransactionsAsync(
        hash: ByteArray
    ): Single<List<ProviderInternalTransaction>> =
        service.getInternalTransactions(hash.toHexString()).map { transactions ->
            transactions.mapNotNull(::mapInternalTransaction)
        }

    override fun getTokenTransactions(startBlock: Long): Single<List<ProviderTokenTransaction>> =
        service.getTokenTransfers(address.hex, ERC20, startBlock).map { transfers ->
            transfers.mapNotNull(::mapTokenTransaction)
        }

    override fun getEip721Transactions(
        startBlock: Long
    ): Single<List<ProviderEip721Transaction>> =
        service.getTokenTransfers(address.hex, ERC721, startBlock).map { transfers ->
            transfers.mapNotNull(::mapEip721Transaction)
        }

    override fun getEip1155Transactions(
        startBlock: Long
    ): Single<List<ProviderEip1155Transaction>> =
        service.getTokenTransfers(address.hex, ERC1155, startBlock).map { transfers ->
            transfers.mapNotNull(::mapEip1155Transaction)
        }

    private fun mapTransaction(tx: BlockscoutTransaction): ProviderTransaction? = tryOrNull {
        val isSuccessful = tx.status.toSuccessfulStatus()
        ProviderTransaction(
            blockNumber = requireNotNull(tx.blockNumber),
            timestamp = requireNotNull(tx.timestamp.toEpochSecondOrNull()),
            hash = requireNotNull(tx.hash?.hexStringToByteArrayOrNull()),
            nonce = tx.nonce ?: 0,
            transactionIndex = tx.position ?: 0,
            from = Address(requireNotNull(tx.from?.hash)),
            to = tx.to?.hash?.let(::Address),
            value = requireNotNull(tx.value?.toBigIntegerOrNull()),
            gasLimit = tx.gasLimit?.toLongOrNull() ?: 0,
            gasPrice = tx.gasPrice?.toLongOrNull() ?: 0,
            isError = isSuccessful?.let { if (it) 0 else 1 },
            txReceiptStatus = isSuccessful?.let { if (it) 1 else 0 },
            input = tx.rawInput?.hexStringToByteArrayOrNull() ?: ByteArray(0),
            gasUsed = tx.gasUsed?.toLongOrNull()
        )
    }

    private fun mapInternalTransaction(
        tx: BlockscoutInternalTransaction
    ): ProviderInternalTransaction? = tryOrNull {
        ProviderInternalTransaction(
            hash = requireNotNull(tx.transactionHash?.hexStringToByteArrayOrNull()),
            blockNumber = requireNotNull(tx.blockNumber),
            timestamp = requireNotNull(tx.timestamp.toEpochSecondOrNull()),
            from = Address(requireNotNull(tx.from?.hash)),
            to = Address(requireNotNull(tx.to?.hash)),
            value = requireNotNull(tx.value?.toBigIntegerOrNull()),
            traceId = (tx.index ?: 0).toString()
        )
    }

    private fun mapTokenTransaction(
        transfer: BlockscoutTokenTransfer
    ): ProviderTokenTransaction? = tryOrNull {
        val fields = transfer.requiredFields()
        ProviderTokenTransaction(
            blockNumber = fields.blockNumber,
            timestamp = fields.timestamp,
            hash = fields.hash,
            nonce = 0,
            blockHash = fields.blockHash,
            from = fields.from,
            contractAddress = fields.contractAddress,
            to = fields.to,
            value = requireNotNull(fields.total.value?.toBigIntegerOrNull()),
            tokenName = fields.tokenName,
            tokenSymbol = fields.tokenSymbol,
            tokenDecimal = fields.tokenDecimal,
            transactionIndex = 0,
            gasLimit = 0,
            gasPrice = 0,
            gasUsed = 0,
            cumulativeGasUsed = 0,
            input = null
        )
    }

    private fun mapEip721Transaction(
        transfer: BlockscoutTokenTransfer
    ): ProviderEip721Transaction? = tryOrNull {
        val fields = transfer.requiredFields()
        ProviderEip721Transaction(
            blockNumber = fields.blockNumber,
            timestamp = fields.timestamp,
            hash = fields.hash,
            nonce = 0,
            blockHash = fields.blockHash,
            transactionIndex = 0,
            gasLimit = 0,
            gasPrice = 0,
            gasUsed = 0,
            cumulativeGasUsed = 0,
            contractAddress = fields.contractAddress,
            from = fields.from,
            to = fields.to,
            tokenId = requireNotNull(fields.total.tokenId?.toBigIntegerOrNull()),
            tokenName = fields.tokenName,
            tokenSymbol = fields.tokenSymbol,
            tokenDecimal = fields.tokenDecimal
        )
    }

    private fun mapEip1155Transaction(
        transfer: BlockscoutTokenTransfer
    ): ProviderEip1155Transaction? = tryOrNull {
        val fields = transfer.requiredFields()
        ProviderEip1155Transaction(
            blockNumber = fields.blockNumber,
            timestamp = fields.timestamp,
            hash = fields.hash,
            nonce = 0,
            blockHash = fields.blockHash,
            transactionIndex = 0,
            gasLimit = 0,
            gasPrice = 0,
            gasUsed = 0,
            cumulativeGasUsed = 0,
            contractAddress = fields.contractAddress,
            from = fields.from,
            to = fields.to,
            tokenId = requireNotNull(fields.total.tokenId?.toBigIntegerOrNull()),
            tokenValue = fields.total.value?.toIntOrNull() ?: 0,
            tokenName = fields.tokenName,
            tokenSymbol = fields.tokenSymbol
        )
    }

    private fun BlockscoutTokenTransfer.requiredFields(): TokenTransferFields {
        val token = requireNotNull(token)
        return TokenTransferFields(
            blockNumber = requireNotNull(blockNumber),
            timestamp = requireNotNull(timestamp.toEpochSecondOrNull()),
            hash = requireNotNull(transactionHash?.hexStringToByteArrayOrNull()),
            blockHash = blockHash?.hexStringToByteArrayOrNull() ?: ByteArray(0),
            from = Address(requireNotNull(from?.hash)),
            to = Address(requireNotNull(to?.hash)),
            contractAddress = Address(requireNotNull(token.addressHash)),
            tokenName = token.name.orEmpty(),
            tokenSymbol = token.symbol.orEmpty(),
            tokenDecimal = token.decimals?.toIntOrNull() ?: 0,
            total = requireNotNull(total)
        )
    }

    private fun String?.toEpochSecondOrNull(): Long? = tryOrNull {
        this?.let { Instant.parse(it).epochSecond }
    }

    private fun String?.toSuccessfulStatus(): Boolean? = when (this?.lowercase()) {
        "ok" -> true
        "error" -> false
        else -> null
    }

    private inline fun <T> tryOrNull(block: () -> T): T? = try {
        block()
    } catch (error: Exception) {
        null
    }

    private data class TokenTransferFields(
        val blockNumber: Long,
        val timestamp: Long,
        val hash: ByteArray,
        val blockHash: ByteArray,
        val from: Address,
        val to: Address,
        val contractAddress: Address,
        val tokenName: String,
        val tokenSymbol: String,
        val tokenDecimal: Int,
        val total: BlockscoutTotal
    )

    companion object {
        private const val ERC20 = "ERC-20"
        private const val ERC721 = "ERC-721"
        private const val ERC1155 = "ERC-1155"
    }
}
