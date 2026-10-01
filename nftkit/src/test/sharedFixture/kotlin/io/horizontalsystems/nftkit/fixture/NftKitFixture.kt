package io.horizontalsystems.nftkit.fixture

import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.nftkit.models.Eip1155Event
import io.horizontalsystems.nftkit.models.Eip721Event
import io.horizontalsystems.nftkit.models.NftBalanceRecord
import io.horizontalsystems.nftkit.models.NftType
import java.math.BigInteger

/** Expected content of the plaintext fixture database in `src/test/resources/databases`; no platform dependencies. */
object NftKitFixture {
    const val DB = "NftKit-1-fixturewallet"

    private val account = Address("0x1111111111111111111111111111111111111111")
    private val counterparty = Address("0x2222222222222222222222222222222222222222")
    private val collection721 = Address("0xbc4ca0eda7647a8ab7c2061c2e118a18a936f13d")
    private val collection1155 = Address("0x495f947276749ce646f68ac8c248420045cb7b5e")

    val balances = listOf(
        NftBalanceRecord(NftType.Eip721, collection721, BigInteger("1234"), "Ape #1234", balance = 1, synced = true),
        NftBalanceRecord(NftType.Eip1155, collection1155, BigInteger("77"), "Item 77", balance = 5, synced = false)
    )

    val eip721Events = listOf(
        Eip721Event(
            hash = ByteArray(32) { 0x71 }, blockNumber = 18_000_000, contractAddress = collection721,
            from = counterparty, to = account, tokenId = BigInteger("1234"),
            tokenName = "Bored Ape", tokenSymbol = "BAYC", tokenDecimal = 0, id = 1
        )
    )

    val eip1155Events = listOf(
        Eip1155Event(
            hash = ByteArray(32) { 0x72 }, blockNumber = 18_100_000, contractAddress = collection1155,
            from = counterparty, to = account, tokenId = BigInteger("77"), tokenValue = 5,
            tokenName = "Item", tokenSymbol = "ITM", id = 1
        )
    )

    fun NftBalanceRecord.snapshot() = listOf(type, contractAddress.hex, tokenId, tokenName, balance, synced)

    fun Eip721Event.snapshot() = listOf(
        hash.toHexString(), blockNumber, contractAddress.hex, from.hex, to.hex, tokenId, tokenName, tokenSymbol, tokenDecimal, id
    )

    fun Eip1155Event.snapshot() = listOf(
        hash.toHexString(), blockNumber, contractAddress.hex, from.hex, to.hex, tokenId, tokenValue, tokenName, tokenSymbol, id
    )
}
