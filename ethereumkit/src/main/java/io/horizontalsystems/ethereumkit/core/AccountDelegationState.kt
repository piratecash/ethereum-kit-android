package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.models.Address

/** The account's delegate per its latest successful `eth_getCode`; one immutable value so readers never see it torn. */
class AccountDelegationState {
    @Volatile
    var current: CurrentDelegate = CurrentDelegate.Unknown
}

sealed interface CurrentDelegate {
    data object Unknown : CurrentDelegate

    /** [delegate] is null when the account's code is not a delegation designator. */
    data class Known(val delegate: Address?) : CurrentDelegate
}
