package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.ethereumkit.models.RawTransaction
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastResult
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastStatus
import io.horizontalsystems.ethereumkit.models.Signature
import io.horizontalsystems.ethereumkit.models.SignedRawTransaction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger

class EthereumKitRawTransactionTest {
    private val to = Address("0x3535353535353535353535353535353535353535")
    private val signature = Signature(
        v = 37,
        r = "1111111111111111111111111111111111111111111111111111111111111111".hexStringToByteArray(),
        s = "2222222222222222222222222222222222222222222222222222222222222222".hexStringToByteArray(),
    )

    companion object {
        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            EthereumKit.init()
        }
    }

    @Test
    fun signedRawTransaction_legacy_returnsEncodedRawAndHash() {
        val rawTransaction = RawTransaction(
            gasPrice = GasPrice.Legacy(20_000_000_000),
            gasLimit = 21_000,
            to = to,
            value = BigInteger("1000000000000000000"),
            nonce = 9,
        )

        val signed = signedRawTransaction(rawTransaction, signature, chainId = 1)

        assertEquals(
            "f86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a01111111111111111111111111111111111111111111111111111111111111111a02222222222222222222222222222222222222222222222222222222222222222",
            signed.raw.toRawHexString(),
        )
        assertEquals(
            "dc66fa1054069a45b6f2c9afa684a12c710c4f196a7159b9cc9ce9a95a0a307e",
            signed.hash.toRawHexString(),
        )
    }

    @Test
    fun signedRawTransaction_eip1559_returnsTypedEncodedRawAndHash() {
        val rawTransaction = RawTransaction(
            gasPrice = GasPrice.Eip1559(
                maxFeePerGas = 30_000_000_000,
                maxPriorityFeePerGas = 1_500_000_000,
            ),
            gasLimit = 21_000,
            to = to,
            value = BigInteger("12345"),
            nonce = 7,
            data = "abcd".hexStringToByteArray(),
        )
        val eip1559Signature = Signature(
            v = 1,
            r = signature.r,
            s = signature.s,
        )

        val signed = signedRawTransaction(rawTransaction, eip1559Signature, chainId = 1)

        assertEquals(
            "02f86f01078459682f008506fc23ac0082520894353535353535353535353535353535353535353582303982abcdc001a01111111111111111111111111111111111111111111111111111111111111111a02222222222222222222222222222222222222222222222222222222222222222",
            signed.raw.toRawHexString(),
        )
        assertEquals(
            "2f719f622a2988dcead69631958dc4060bce8d72e759effe20e0205429badaf9",
            signed.hash.toRawHexString(),
        )
    }

    @Test
    fun byteArrayModels_sameContent_areEqual() {
        val raw = byteArrayOf(1, 2, 3)
        val rawCopy = raw.copyOf()
        val hash = byteArrayOf(4, 5, 6)
        val hashCopy = hash.copyOf()

        assertEqualAndSameHashCode(
            SignedRawTransaction(raw, hash),
            SignedRawTransaction(rawCopy, hashCopy),
        )
        assertEqualAndSameHashCode(
            RawTransactionBroadcastResult(hash, RawTransactionBroadcastStatus.Submitted),
            RawTransactionBroadcastResult(hashCopy, RawTransactionBroadcastStatus.Submitted),
        )
        assertEqualAndSameHashCode(
            RawTransactionBroadcastRecord(
                hash = hash,
                rawTransaction = raw,
                firstSendTime = 1,
                lastSendTime = 2,
                retriesCount = 3,
                expiresAt = 4,
            ),
            RawTransactionBroadcastRecord(
                hash = hashCopy,
                rawTransaction = rawCopy,
                firstSendTime = 1,
                lastSendTime = 2,
                retriesCount = 3,
                expiresAt = 4,
            ),
        )
    }

    @Test
    fun strictHexToByteArray_validHexWithPrefix_returnsBytes() {
        val bytes = "0x0a10ff".strictHexToByteArray()

        assertArrayEquals(byteArrayOf(0x0a, 0x10, 0xff.toByte()), bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun strictHexToByteArray_oddLength_throws() {
        "abc".strictHexToByteArray()
    }

    @Test(expected = IllegalArgumentException::class)
    fun strictHexToByteArray_empty_throws() {
        "0x".strictHexToByteArray()
    }

    @Test(expected = IllegalArgumentException::class)
    fun strictHexToByteArray_nonHex_throws() {
        "0x00xz".strictHexToByteArray()
    }

    private fun assertEqualAndSameHashCode(first: Any, second: Any) {
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
