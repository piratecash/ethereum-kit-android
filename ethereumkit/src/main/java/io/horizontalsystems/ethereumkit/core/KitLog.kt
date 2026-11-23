package io.horizontalsystems.ethereumkit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.models.Chain

/** Tag carries the chain so lines from kits of different networks stay distinguishable in logcat. */
fun kitLogger(chainId: Int): Logger =
    Logger.withTag("EthereumKit:" + (Chain.entries.firstOrNull { it.id == chainId }?.name ?: chainId.toString()))
