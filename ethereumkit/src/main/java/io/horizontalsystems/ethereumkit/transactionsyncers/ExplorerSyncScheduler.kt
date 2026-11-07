package io.horizontalsystems.ethereumkit.transactionsyncers

import io.horizontalsystems.ethereumkit.models.Chain
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides when the explorer may be asked for transaction history. Explorer credits are the scarce
 * resource: every full sync costs several API calls, so each trigger carries its own floor and a
 * trigger that arrives too early either waits for its floor or is dropped.
 */
class ExplorerSyncScheduler(
    chain: Chain,
    private val clock: Clock = Clock.systemUTC()
) {

    enum class Reason(internal val floor: Duration, internal val deferrable: Boolean) {
        Manual(Duration.ofSeconds(10), false),
        BalanceChanged(Duration.ofSeconds(30), true),
        TokenBalanceChanged(Duration.ofSeconds(30), true),
        ForwardGap(Duration.ofSeconds(30), true),
        Periodic(Duration.ofMinutes(2), false)
    }

    enum class Decision { RunNow, Deferred, Skipped }

    private val accountStatePollFloorMillis = Duration.ofSeconds(chain.syncInterval).toMillis()
    private val state = AtomicReference(State())

    val lastFullSyncStartedAt: Instant?
        get() = state.get().lastSyncStartedAt?.let(Instant::ofEpochMilli)

    fun request(reason: Reason): Decision {
        val now = clock.millis()
        while (true) {
            val current = state.get()
            val decision = current.decide(reason, now)
            val next = when (decision) {
                Decision.RunNow -> current.copy(pendingReason = null)
                Decision.Deferred -> current.copy(pendingReason = soonest(current.pendingReason, reason))
                Decision.Skipped -> current
            }
            if (next === current || state.compareAndSet(current, next)) return decision
        }
    }

    /** Hands back a deferred reason once its floor has elapsed, so no trigger is silently lost. */
    fun consumePending(): Reason? {
        val now = clock.millis()
        while (true) {
            val current = state.get()
            val pending = current.pendingReason ?: return null
            if (current.syncInFlight || !current.floorElapsed(pending, now)) return null
            if (state.compareAndSet(current, current.copy(pendingReason = null))) return pending
        }
    }

    fun markSyncStarted() {
        val now = clock.millis()
        state.updateAndGet { it.copy(lastSyncStartedAt = now, pendingReason = null, syncInFlight = true) }
    }

    fun markSyncFinished() {
        state.updateAndGet { it.copy(syncInFlight = false) }
    }

    /** True at most once per chain sync interval, so a per-block head push cannot multiply RPC polls. */
    fun shouldPollAccountState(): Boolean {
        val now = clock.millis()
        while (true) {
            val current = state.get()
            val last = current.lastAccountStatePollAt
            if (last != null && now - last in 0 until accountStatePollFloorMillis) return false
            if (state.compareAndSet(current, current.copy(lastAccountStatePollAt = now))) return true
        }
    }

    fun reset() {
        state.set(State())
    }

    private fun soonest(pending: Reason?, reason: Reason) =
        if (pending == null || reason.floor < pending.floor) reason else pending

    private data class State(
        val lastSyncStartedAt: Long? = null,
        val lastAccountStatePollAt: Long? = null,
        val pendingReason: Reason? = null,
        val syncInFlight: Boolean = false
    ) {
        fun decide(reason: Reason, now: Long): Decision = when {
            !floorElapsed(reason, now) -> if (reason.deferrable) Decision.Deferred else Decision.Skipped
            syncInFlight -> Decision.Deferred
            else -> Decision.RunNow
        }

        fun floorElapsed(reason: Reason, now: Long): Boolean {
            val elapsed = now - (lastSyncStartedAt ?: return true)
            // A wall clock moved backwards counts as elapsed: a jump may cost one extra sync, never a stall.
            return elapsed < 0 || elapsed >= reason.floor.toMillis()
        }
    }
}
