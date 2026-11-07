package io.horizontalsystems.ethereumkit.fixture

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Cancels the caller of [acquire] after its first, second, ... dispatch until it completes, and asserts that
 * every cancelled run leaves [held] empty. At least one cancellation must hit while something was held,
 * otherwise the test never reached the window it guards.
 */
internal fun assertCancelledAtAnyStepReleases(held: () -> List<String>, acquire: suspend () -> Unit) {
    var cancelledWhileHeld = 0

    for (steps in 1..MAX_STEPS) {
        val caller = QueueDispatcher()
        val job = CoroutineScope(caller).launch { acquire() }
        repeat(steps) { if (!job.isCompleted) caller.runNext() }
        if (job.isCompleted) break
        // Cancel only once the suspended work has finished and handed its result back to the caller.
        val resume = caller.next()
        if (held().isNotEmpty()) cancelledWhileHeld++

        job.cancel()
        resume.run()
        while (!job.isCompleted) caller.runNext()

        assertTrue("step $steps", job.isCancelled)
        assertEquals("step $steps", emptyList<String>(), held())
    }

    assertTrue("no cancellation hit while a resource was held", cancelledWhileHeld > 0)
}

/** SQLite deletes a database's -wal file when its last connection closes. */
internal fun openDatabases(directory: File, namePrefix: String): List<String> =
    directory.list().orEmpty().filter { it.startsWith(namePrefix) && it.endsWith("-wal") }

/** Runs the caller's continuations only when the test says so. */
internal class QueueDispatcher : CoroutineDispatcher() {
    private val tasks = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) = tasks.put(block)

    fun next(): Runnable = checkNotNull(tasks.poll(30, TimeUnit.SECONDS)) { "No continuation was dispatched" }

    fun runNext() = next().run()
}

private const val MAX_STEPS = 100
