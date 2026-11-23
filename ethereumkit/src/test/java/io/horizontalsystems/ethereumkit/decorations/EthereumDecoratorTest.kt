package io.horizontalsystems.ethereumkit.decorations

import io.horizontalsystems.ethereumkit.contracts.ContractEventInstance
import io.horizontalsystems.ethereumkit.contracts.ContractMethod
import io.horizontalsystems.ethereumkit.contracts.EmptyMethod
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.TransactionTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class EthereumDecoratorTest {

    private val walletAddress = Address("0x1111111111111111111111111111111111111111")
    private val senderAddress = Address("0x2222222222222222222222222222222222222222")
    private val recipientAddress = Address("0x3333333333333333333333333333333333333333")
    private val decorator = EthereumDecorator(walletAddress)

    @Test
    fun decoration_unrecognizedInputDirectIncomingValue_returnsIncomingDecoration() {
        val value = BigInteger.TEN

        val decoration = decorate(value = value)

        assertTrue(decoration is IncomingDecoration)
        decoration as IncomingDecoration
        assertEquals(senderAddress, decoration.from)
        assertEquals(value, decoration.value)
        assertEquals(
            listOf(
                TransactionTag.EVM_COIN,
                TransactionTag.EVM_COIN_INCOMING,
                TransactionTag.INCOMING,
                TransactionTag.fromAddress(senderAddress.hex)
            ),
            decoration.tags()
        )
    }

    @Test
    fun decoration_unrecognizedInputZeroValue_returnsNull() {
        assertNull(decorate(value = BigInteger.ZERO))
    }

    @Test
    fun decoration_unrecognizedInputWithEvent_returnsNull() {
        val event = ContractEventInstance(recipientAddress)

        assertNull(decorate(eventInstances = listOf(event)))
    }

    @Test
    fun decoration_unrecognizedInputWithInternalTransaction_returnsNull() {
        val internalTransaction = InternalTransaction(
            hash = ByteArray(32),
            traceId = "0",
            blockNumber = 1,
            from = senderAddress,
            to = walletAddress,
            value = BigInteger.ONE
        )

        assertNull(decorate(internalTransactions = listOf(internalTransaction)))
    }

    @Test
    fun decoration_recognizedInputDirectIncomingValue_returnsNull() {
        assertNull(decorate(contractMethod = ContractMethod()))
    }

    @Test
    fun decoration_unrecognizedInputOutgoingValue_returnsNull() {
        assertNull(decorate(from = walletAddress, to = recipientAddress))
    }

    @Test
    fun decoration_emptyInputIncomingValue_preservesIncomingDecoration() {
        val decoration = decorate(contractMethod = EmptyMethod())

        assertTrue(decoration is IncomingDecoration)
        decoration as IncomingDecoration
        assertEquals(senderAddress, decoration.from)
        assertEquals(BigInteger.ONE, decoration.value)
    }

    @Test
    fun decoration_missingRecipientWithoutUserEvents_preservesContractCreation() {
        assertTrue(decorate(to = null) is ContractCreationDecoration)
    }

    @Test
    fun decoration_missingRecipientWithUserEvent_returnsNull() {
        val event = object : ContractEventInstance(recipientAddress) {
            override fun tags(userAddress: Address) = listOf(TransactionTag.INCOMING)
        }

        assertNull(decorate(to = null, eventInstances = listOf(event)))
    }

    private fun decorate(
        from: Address = senderAddress,
        to: Address? = walletAddress,
        value: BigInteger = BigInteger.ONE,
        contractMethod: ContractMethod? = null,
        internalTransactions: List<InternalTransaction> = emptyList(),
        eventInstances: List<ContractEventInstance> = emptyList()
    ): TransactionDecoration? = decorator.decoration(
        from,
        to,
        value,
        contractMethod,
        internalTransactions,
        eventInstances
    )
}
