package io.horizontalsystems.uniswapkit

import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.uniswapkit.models.DexType
import io.horizontalsystems.uniswapkit.v3.pool.PoolManager
import io.horizontalsystems.uniswapkit.v3.quoter.QuoterV2
import io.horizontalsystems.uniswapkit.v3.router.SwapRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class RobinhoodContractsTest {

    @Test
    fun robinhoodChain_uniswapContracts_matchOfficialDeployment() {
        assertEquals(WETH, TokenFactory.getWethAddress(Chain.RobinhoodChain).hex)
        assertEquals(FACTORY, PoolManager(DexType.Uniswap).factoryAddress(Chain.RobinhoodChain))
        assertEquals(
            QUOTER,
            QuoterV2(TokenFactory(), DexType.Uniswap).quoterAddress(Chain.RobinhoodChain)
        )
        assertEquals(
            ROUTER,
            SwapRouter(DexType.Uniswap).swapRouterAddress(Chain.RobinhoodChain).hex
        )
    }

    companion object {
        private const val WETH = "0x0bd7d308f8e1639fab988df18a8011f41eacad73"
        private const val FACTORY = "0x1f7d7550b1b028f7571e69a784071f0205fd2efa"
        private const val QUOTER = "0x33e885ed0ec9bf04ecfb19341582aadcb4c8a9e7"
        private const val ROUTER = "0xcaf681a66d020601342297493863e78c959e5cb2"
    }
}
