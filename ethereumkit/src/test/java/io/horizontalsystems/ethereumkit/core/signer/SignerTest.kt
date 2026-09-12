package io.horizontalsystems.ethereumkit.core.signer

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.toRawHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.hdwalletkit.Mnemonic
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger

class SignerTest {

    @Test
    fun privateKey_standardMnemonic_matchesPreviousVersionForBothCoinTypes() {
        vectors.forEach { (chain, vector) ->
            assertEquals(vector.privateKey, Signer.privateKey(words, chain = chain))
            assertEquals(vector.privateKey, Signer.privateKey(seed, chain))
            assertEquals(vector.address, Signer.address(seed, chain).hex)
        }
    }

    @Test
    fun signByteArray_standardMnemonic_matchesPreviousVersion() {
        assertEquals(
            "f16ea9a3478698f695fd1401bfe27e9e4a7e8e3da94aa72b021125e31fa899cc573c48ea3fe1d4ab61a9db10c19032026e3ed2dbccba5a178235ac27f945043101",
            signer.signByteArray("hello".toByteArray()).toRawHexString(),
        )
    }

    @Test
    fun signByteArrayLegacy_standardMnemonic_matchesPreviousVersion() {
        assertEquals(
            "ae421bafdc60eb53da2819e24eadd709b792192c848a6a52487920309c2fa0ce509a7fa361bd2cbde3723495f4a5ceec1f7a485881c0b170f438f9916d44bea201",
            signer.signByteArrayLegacy("hello".toByteArray()).toRawHexString(),
        )
    }

    @Test
    fun signedTransaction_standardMnemonic_matchesPreviousVersion() {
        assertEquals(
            "f86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a03016c5b00acdf2ab6417652b9af1b5458ae73a8f2ddbc2ce03ccddde54184f71a0160362f6bf9e0af5a6f543153b85cfce8bce64cf08607f2cfd486fecb81eba1d",
            signer.signedTransaction(
                address = recipient,
                value = BigInteger("1000000000000000000"),
                transactionInput = byteArrayOf(),
                gasPrice = GasPrice.Legacy(20_000_000_000),
                gasLimit = 21_000,
                nonce = 9,
            ).toRawHexString(),
        )
        assertEquals(
            "02f86f01078459682f008506fc23ac0082520894353535353535353535353535353535353535353582303982abcdc080a0d2c46f802bc3a8a47050b99a1e39c950caf9ec67d562dd8589e95d3bf1c1459ea02ca804cd2816da128b45a3f28040da1da3e247479267583132cc5fd64e4668aa",
            signer.signedTransaction(
                address = recipient,
                value = BigInteger("12345"),
                transactionInput = byteArrayOf(0xab.toByte(), 0xcd.toByte()),
                gasPrice = GasPrice.Eip1559(
                    maxFeePerGas = 30_000_000_000,
                    maxPriorityFeePerGas = 1_500_000_000,
                ),
                gasLimit = 21_000,
                nonce = 7,
            ).toRawHexString(),
        )
    }

    companion object {
        private val words = List(11) { "test" } + "junk"
        private val seed = Mnemonic().toSeed(words)
        private val signer by lazy { Signer.getInstance(seed, Chain.Ethereum) }
        private val recipient = Address("0x3535353535353535353535353535353535353535")
        private val vectors = mapOf(
            Chain.Ethereum to DerivationVector(
                privateKey = BigInteger("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80", 16),
                address = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266",
            ),
            Chain.EthereumGoerli to DerivationVector(
                privateKey = BigInteger("7c299dda7c704f9d474b6ca5d7fee0b490c8decca493b5764541fe5ec6b65114", 16),
                address = "0x22310bf73bc88ae2d2c9a29bd87bc38fbac9e6b0",
            ),
        )

        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            EthereumKit.init()
        }
    }

    private data class DerivationVector(
        val privateKey: BigInteger,
        val address: String,
    )
}
