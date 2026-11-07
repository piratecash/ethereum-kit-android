package io.horizontalsystems.ethereumkit.models

import com.google.gson.JsonParser
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcAuthorization
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransaction
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.crypto.CryptoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger

class Eip7702Test {

    @Test
    fun gsonParse_realActivationResponse_readsTypeAndAuthorization() {
        val tx = realTransaction()
        val authorization = tx.authorizationList.orEmpty().single()

        assertEquals(4L, tx.type)
        assertEquals(BigInteger.valueOf(56), authorization.chainId)
        assertEquals(delegate, authorization.address)
        assertEquals(BigInteger.valueOf(0x52), authorization.nonce)
        assertEquals(1L, authorization.yParity)
    }

    @Test
    fun authority_realTuple_recoversSigner() {
        assertEquals(user, realTransaction().authorizationList.orEmpty().single().authority())
    }

    @Test
    fun authority_highS_returnsNull() {
        val real = realTransaction().authorizationList.orEmpty().single()
        val flippedParity = 1 - requireNotNull(real.yParity)
        val malleated = RpcAuthorization(real.chainId, real.address, real.nonce, flippedParity, real.r, CryptoUtils.CURVE.n - real.s)

        assertNull(malleated.authority())
    }

    @Test
    fun authority_yParityOutOfRange_returnsNull() {
        val real = realTransaction().authorizationList.orEmpty().single()

        assertNull(RpcAuthorization(real.chainId, real.address, real.nonce, 2, real.r, real.s).authority())
        assertNull(RpcAuthorization(real.chainId, real.address, real.nonce, null, real.r, real.s).authority())
    }

    @Test
    fun authority_nonceAtOrAboveLimit_parsesExactlyAndReturnsNull() {
        val signedForZero = signAuthorization(chainId = 56, delegate = delegate, nonce = BigInteger.ZERO)
        val signedForLimit = signAuthorization(chainId = 56, delegate = delegate, nonce = nonceLimit)
        assertEquals(testAccount, signedForZero.authority())

        listOf("0xffffffffffffffff" to nonceLimit, "0x10000000000000000" to nonceLimit + BigInteger.ONE).forEach { (hex, expected) ->
            val parsed = parseAuthorization(signedForZero, nonceHex = hex)
            assertEquals(expected, parsed.nonce)
            assertNull(parsed.authority())
        }
        assertNull(signedForLimit.authority())
    }

    @Test
    fun ownDelegates_realTransaction_returnsDelegateForSigner() {
        assertEquals(listOf(delegate), realTransaction().ownDelegates(user, 56))
    }

    @Test
    fun ownDelegates_otherAccount_returnsEmpty() {
        assertEquals(emptyList<Address>(), realTransaction().ownDelegates(relayer, 56))
    }

    @Test
    fun ownDelegates_otherChain_returnsEmpty() {
        assertEquals(emptyList<Address>(), realTransaction().ownDelegates(user, 1))
    }

    @Test
    fun ownDelegates_chainIdZero_accepted() {
        val tx = transaction(signAuthorization(chainId = 0, delegate = delegate, nonce = BigInteger.ONE))

        assertEquals(listOf(delegate), tx.ownDelegates(testAccount, 56))
    }

    @Test
    fun ownDelegates_highSTuple_excluded() {
        val signed = signAuthorization(chainId = 56, delegate = delegate, nonce = BigInteger.ONE)
        val malleated = RpcAuthorization(signed.chainId, signed.address, signed.nonce, 1 - requireNotNull(signed.yParity), signed.r, CryptoUtils.CURVE.n - signed.s)

        assertEquals(listOf(delegate), transaction(signed).ownDelegates(testAccount, 56))
        assertEquals(emptyList<Address>(), transaction(malleated).ownDelegates(testAccount, 56))
    }

    @Test
    fun ownDelegates_twoOwnTuplesAmongForeign_returnsBothInOrder() {
        val other = Address("0x1111111111111111111111111111111111111111")
        val tx = transaction(
            signAuthorization(chainId = 56, delegate = delegate, nonce = BigInteger.ONE),
            realTransaction().authorizationList.orEmpty().single(),
            signAuthorization(chainId = 56, delegate = other, nonce = BigInteger.TWO),
        )

        assertEquals(listOf(delegate, other), tx.ownDelegates(testAccount, 56))
    }

    @Test
    fun ownDelegates_zeroAddressDelegate_kept() {
        val zero = Address(ByteArray(20))

        assertEquals(listOf(zero), transaction(signAuthorization(chainId = 56, delegate = zero, nonce = BigInteger.ONE)).ownDelegates(testAccount, 56))
    }

    @Test
    fun ownDelegates_noAuthorizationList_returnsEmpty() {
        assertEquals(emptyList<Address>(), transaction().ownDelegates(testAccount, 56))
    }

    @Test
    fun currentDelegate_delegationDesignator_returnsAddress() {
        assertEquals(delegate, currentDelegate("ef0100".hexStringToByteArray() + delegate.raw))
    }

    @Test
    fun currentDelegate_otherCode_returnsNull() {
        assertNull(currentDelegate(ByteArray(0)))
        assertNull(currentDelegate("6080604052".hexStringToByteArray()))
        assertNull(currentDelegate("ef0101".hexStringToByteArray() + delegate.raw))
        assertNull(currentDelegate("ef0100".hexStringToByteArray() + delegate.raw.copyOf(19)))
        assertNull(currentDelegate("ef0100".hexStringToByteArray() + delegate.raw + byteArrayOf(0)))
    }

    private fun realTransaction(): RpcTransaction {
        val json = requireNotNull(javaClass.classLoader?.getResource("rpc/eip7702_activation.json")).readText()
        val result = JsonParser.parseString(json).asJsonObject["result"]
        return EthereumKit.gson.fromJson(result, RpcTransaction::class.java)
    }

    private fun parseAuthorization(signed: RpcAuthorization, nonceHex: String): RpcAuthorization {
        val json = """
            {"chainId":"0x${signed.chainId.toString(16)}","address":"${signed.address.hex}","nonce":"$nonceHex",
             "yParity":"0x${signed.yParity}","r":"0x${signed.r.toString(16)}","s":"0x${signed.s.toString(16)}"}
        """
        return EthereumKit.gson.fromJson(json, RpcAuthorization::class.java)
    }

    private fun transaction(vararg authorizations: RpcAuthorization) =
        rpcTransaction(ByteArray(32), type = 4, authorizations = authorizations.toList().takeIf { it.isNotEmpty() })

    companion object {
        private val user = Address("0xd369b1bdb25a40bfb5877dbefcad29889fd90c1d")
        private val relayer = Address("0xfcfee4726b59b02b5aaed7bc996ee468157886c6")
        private val delegate = Address("0x64fc23203fcc4bcf09fc4b30669131333ba7936b")
        private val nonceLimit = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE

        @BeforeClass
        @JvmStatic
        fun setUp() {
            EthereumKit.init()
        }
    }
}
