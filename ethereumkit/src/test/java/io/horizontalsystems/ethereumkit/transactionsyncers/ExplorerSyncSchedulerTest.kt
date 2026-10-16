package io.horizontalsystems.ethereumkit.transactionsyncers

import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.transactionsyncers.ExplorerSyncScheduler.Decision
import io.horizontalsystems.ethereumkit.transactionsyncers.ExplorerSyncScheduler.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Shared by the kit tests: lets a test move the scheduler's wall clock instead of waiting. */
internal class MutableTestClock(private var now: Instant = Instant.EPOCH) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    fun rewind(duration: Duration) {
        now = now.minus(duration)
    }
}

class ExplorerSyncSchedulerTest {

    private val clock = MutableTestClock()
    private val scheduler = ExplorerSyncScheduler(Chain.Ethereum, clock)

    private fun runSync() {
        scheduler.markSyncStarted()
        scheduler.markSyncFinished()
    }

    @Test
    fun request_manualWithinFloor_isSkipped() {
        runSync()

        clock.advance(Duration.ofSeconds(9))

        assertEquals(Decision.Skipped, scheduler.request(Reason.Manual))
        assertNull(scheduler.consumePending())
    }

    @Test
    fun request_manualAfterFloor_runsNow() {
        runSync()

        clock.advance(Duration.ofSeconds(10))

        assertEquals(Decision.RunNow, scheduler.request(Reason.Manual))
    }

    @Test
    fun request_balanceChangedWithinFloor_isDeferredUntilFloorElapsed() {
        runSync()
        clock.advance(Duration.ofSeconds(5))

        assertEquals(Decision.Deferred, scheduler.request(Reason.BalanceChanged))
        assertNull(scheduler.consumePending())

        clock.advance(Duration.ofSeconds(25))

        assertEquals(Reason.BalanceChanged, scheduler.consumePending())
        assertNull(scheduler.consumePending())
    }

    @Test
    fun request_balanceChangedOnEveryTickForFiveMinutes_runsTenSyncs() {
        var syncs = 0

        repeat(300) {
            val runnable = scheduler.request(Reason.BalanceChanged) == Decision.RunNow ||
                scheduler.consumePending() != null
            if (runnable) {
                runSync()
                syncs++
            }
            clock.advance(Duration.ofSeconds(1))
        }

        assertEquals(10, syncs)
    }

    @Test
    fun request_periodicBeforeInterval_isSkipped() {
        runSync()

        clock.advance(Duration.ofSeconds(119))

        assertEquals(Decision.Skipped, scheduler.request(Reason.Periodic))
    }

    @Test
    fun request_periodicAfterInterval_runsNow() {
        runSync()

        clock.advance(Duration.ofMinutes(2))

        assertEquals(Decision.RunNow, scheduler.request(Reason.Periodic))
    }

    @Test
    fun request_duringSyncInFlight_isDeferredAndRunsOnceWhenFinished() {
        scheduler.markSyncStarted()
        clock.advance(Duration.ofSeconds(31))

        assertEquals(Decision.Deferred, scheduler.request(Reason.BalanceChanged))
        assertNull(scheduler.consumePending())

        scheduler.markSyncFinished()

        assertEquals(Reason.BalanceChanged, scheduler.consumePending())
        assertNull(scheduler.consumePending())
    }

    @Test
    fun consumePending_severalDeferredReasons_keepsTheShortestFloor() {
        scheduler.markSyncStarted()
        clock.advance(Duration.ofSeconds(11))
        scheduler.request(Reason.BalanceChanged)
        scheduler.request(Reason.Manual)

        scheduler.markSyncFinished()

        assertEquals(Reason.Manual, scheduler.consumePending())
    }

    @Test
    fun request_clockMovedBackwards_runsNow() {
        runSync()

        clock.rewind(Duration.ofMinutes(5))

        assertEquals(Decision.RunNow, scheduler.request(Reason.Manual))
    }

    @Test
    fun reset_afterSync_clearsFloorsAndPending() {
        scheduler.markSyncStarted()
        scheduler.request(Reason.BalanceChanged)

        scheduler.reset()

        assertNull(scheduler.consumePending())
        assertEquals(Decision.RunNow, scheduler.request(Reason.Periodic))
    }

    @Test
    fun markSyncStarted_atStart_suppressesTheFirstPeriodicSync() {
        runSync()

        assertEquals(Decision.Skipped, scheduler.request(Reason.Periodic))
    }

    @Test
    fun shouldPollAccountState_headPushesEverySecond_pollsOncePerSyncInterval() {
        var polls = 0

        repeat(60) {
            if (scheduler.shouldPollAccountState()) polls++
            clock.advance(Duration.ofSeconds(1))
        }

        assertEquals(4, polls)
    }
}
