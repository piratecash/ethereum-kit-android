package io.horizontalsystems.ethereumkit.decorations

import io.horizontalsystems.ethereumkit.core.AccountDelegationState
import io.horizontalsystems.ethereumkit.core.CurrentDelegate
import io.horizontalsystems.ethereumkit.core.ITransactionStorage
import io.horizontalsystems.ethereumkit.models.AccountDelegation
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.toAuthorizationDelegates
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecorationManagerAccountDelegationTest {

    private val account = Address("0x1111111111111111111111111111111111111111")
    private val contract = Address("0x64fc23203fcc4bcf09fc4b30669131333ba7936b")
    private val otherContract = Address("0x2222222222222222222222222222222222222222")
    private val zero = Address(ByteArray(20))
    private val state = AccountDelegationState()
    private val storage = mockk<ITransactionStorage> {
        coEvery { getInternalTransactionsByHashes(any()) } returns emptyList()
    }
    private val manager = DecorationManager(account, storage, state)

    @Test
    fun decorateTransactions_carrierStateUnknown_delegationNotActive() = runTest {
        assertEquals(AccountDelegation(contract, isActive = false), decorate(contract))
    }

    @Test
    fun decorateTransactions_currentIsDelegate_active() = runTest {
        state.current = CurrentDelegate.Known(contract)

        assertEquals(AccountDelegation(contract, isActive = true), decorate(contract))
    }

    @Test
    fun decorateTransactions_currentIsOtherContract_notActive() = runTest {
        state.current = CurrentDelegate.Known(otherContract)

        assertEquals(AccountDelegation(contract, isActive = false), decorate(contract))
    }

    @Test
    fun decorateTransactions_zeroDelegateWithEmptyCode_revocationNotActive() = runTest {
        state.current = CurrentDelegate.Known(null)

        assertEquals(AccountDelegation(zero, isActive = false), decorate(zero))
    }

    @Test
    fun decorateTransactions_currentIsEarlierOfTwoDelegates_picksCurrentActive() = runTest {
        state.current = CurrentDelegate.Known(contract)

        assertEquals(AccountDelegation(contract, isActive = true), decorate(contract, zero))
    }

    @Test
    fun decorateTransactions_noCurrentDelegate_picksLastNotActive() = runTest {
        state.current = CurrentDelegate.Known(null)

        assertEquals(AccountDelegation(zero, isActive = false), decorate(contract, zero))
    }

    @Test
    fun decorateTransactions_rowWithoutDelegates_noDelegation() = runTest {
        state.current = CurrentDelegate.Known(contract)

        assertNull(decorate())
    }

    private suspend fun decorate(vararg delegates: Address): AccountDelegation? {
        val transaction = Transaction(
            ByteArray(32) { 1 }, 1L, false, blockNumber = 1, from = account, to = account,
            type = 4, authorizationDelegates = delegates.toList().toAuthorizationDelegates()
        )
        return manager.decorateTransactions(listOf(transaction)).single().transaction.accountDelegation
    }
}
