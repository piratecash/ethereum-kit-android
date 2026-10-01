package io.horizontalsystems.erc20kit.fixture

import java.math.BigInteger

/** Expected content of the plaintext fixture databases in `src/test/resources/databases`; no platform dependencies. */
object Erc20KitFixture {
    const val USDT_DB = "Erc20-1-fixturewallet-0xdac17f958d2ee523a2206206994597c13d831ec7"
    const val USDC_DB = "Erc20-1-fixturewallet-0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"

    val balances = mapOf(
        USDT_DB to BigInteger("1000000"),
        USDC_DB to BigInteger("2500000000000000000")
    )
}
