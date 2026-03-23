package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.core.EthereumKit.ForwardSyncState
import io.horizontalsystems.ethereumkit.models.Chain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardSyncStateTest {

    @Test
    fun onUpdateLastBlockHeight_gapAboveThreshold_setsForwardSyncing() {
        val lastTip = 1000L
        val chainTip = lastTip + EthereumKit.FORWARD_GAP_THRESHOLD + 1

        val result = EthereumKit.computeForwardSyncState(
            Chain.BinanceSmartChain, lastTip, chainTip
        )

        assertTrue(result is ForwardSyncState.Syncing)
        val syncing = result as ForwardSyncState.Syncing
        assertEquals(lastTip, syncing.lastSyncedTip)
        assertEquals(chainTip, syncing.chainTipBlock)
        assertEquals(chainTip - lastTip, syncing.blocksRemaining)
    }

    @Test
    fun onUpdateLastBlockHeight_gapBelowThreshold_keepsIdle() {
        val lastTip = 1000L
        val chainTip = lastTip + EthereumKit.FORWARD_GAP_THRESHOLD

        val result = EthereumKit.computeForwardSyncState(
            Chain.BinanceSmartChain, lastTip, chainTip
        )

        assertEquals(ForwardSyncState.Idle, result)
    }

    @Test
    fun initialSyncTipZero_keepsIdle() {
        val result = EthereumKit.computeForwardSyncState(
            Chain.BinanceSmartChain, 0L, 5000L
        )

        assertEquals(ForwardSyncState.Idle, result)
    }

    @Test
    fun nonBscChain_keepsIdle() {
        val result = EthereumKit.computeForwardSyncState(
            Chain.Ethereum, 1000L, 5000L
        )

        assertEquals(ForwardSyncState.Idle, result)
    }

    @Test
    fun nonBscChain_polygon_keepsIdle() {
        val result = EthereumKit.computeForwardSyncState(
            Chain.Polygon, 1000L, 5000L
        )

        assertEquals(ForwardSyncState.Idle, result)
    }

    @Test
    fun historicalAndForwardAreIndependent() {
        val forward = ForwardSyncState.Syncing(lastSyncedTip = 1000, chainTipBlock = 1500)
        val historical = EthereumKit.HistoricalSyncState.Syncing(
            startBlock = 50000, currentBlock = 30000
        )

        assertEquals(500L, forward.blocksRemaining)
        assertEquals(30000L, historical.blocksRemaining)

        val forwardResult = EthereumKit.computeForwardSyncState(
            Chain.BinanceSmartChain, 1000L, 1500L
        )
        assertTrue(forwardResult is ForwardSyncState.Syncing)
    }

    @Test
    fun syncCompleted_updatesLastForwardSyncTip_andClearsForwardState() {
        val oldTip = 1000L
        val chainTip = 1200L

        val before = EthereumKit.computeForwardSyncState(
            Chain.BinanceSmartChain, oldTip, chainTip
        )
        assertTrue(before is ForwardSyncState.Syncing)
        assertEquals(200L, (before as ForwardSyncState.Syncing).blocksRemaining)

        val advancedTip = chainTip

        val after = EthereumKit.computeForwardSyncState(
            Chain.BinanceSmartChain, advancedTip, chainTip
        )
        assertEquals(ForwardSyncState.Idle, after)
    }

    @Test
    fun syncFailed_tipNotAdvanced_gapReappearsOnNextUpdate() {
        val oldTip = 1000L
        val chainTip = 1200L

        val before = EthereumKit.computeForwardSyncState(
            Chain.BinanceSmartChain, oldTip, chainTip
        )
        assertTrue(before is ForwardSyncState.Syncing)

        val nextBlock = chainTip + 1
        val afterRetry = EthereumKit.computeForwardSyncState(
            Chain.BinanceSmartChain, oldTip, nextBlock
        )
        assertTrue(afterRetry is ForwardSyncState.Syncing)
        assertEquals(nextBlock - oldTip, (afterRetry as ForwardSyncState.Syncing).blocksRemaining)
    }
}
