package cordis

import cordis.kernel.Context
import cordis.kernel.CordisClock
import cordis.kernel.CordisException
import cordis.kernel.Fork
import cordis.kernel.TaskFailed
import cordis.testing.ManualClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class TimerBoom(
    val id: Int,
) : Exception("Boom $id")

class FailingClock(
    private val id: Int,
) : CordisClock {
    override val now: Duration = Duration.ZERO

    override suspend fun delay(duration: Duration): Unit = throw TimerBoom(id)
}

class TimerFailureTests {
    @Test
    fun throwingAfterBodyReportsTaskFailed() =
        runTest {
            val app = Context.root()
            val clock = ManualClock()
            val failed = CompletableDeferred<TaskFailed>()
            app.on<TaskFailed> { failed.complete(it) }
            val handle =
                app.plugin("delayed") { ctx ->
                    ctx.after(1.seconds, clock = clock) { throw TimerBoom(1) }
                }
            waitForSleepers(clock, 1)
            clock.advance(1.seconds)

            assertEquals(TaskFailed(plugin = "delayed", error = TimerBoom(1)), failed.await())
            assertEquals(Fork.Status.Active, handle.status)
            app.dispose()
        }

    @Test
    fun throwingEveryBodyReportsTaskFailedOnFirstTick() =
        runTest {
            val app = Context.root()
            val clock = ManualClock()
            val failed = CompletableDeferred<TaskFailed>()
            app.on<TaskFailed> { failed.complete(it) }
            val handle =
                app.plugin("ticker") { ctx ->
                    ctx.every(1.seconds, clock = clock) { throw TimerBoom(2) }
                }
            waitForSleepers(clock, 1)
            clock.advance(1.seconds)

            assertEquals(TaskFailed(plugin = "ticker", error = TimerBoom(2)), failed.await())
            assertEquals(Fork.Status.Active, handle.status)
            app.dispose()
        }

    @Test
    fun failingClockInAfterReportsTaskFailed() =
        runTest {
            val app = Context.root()
            val failed = CompletableDeferred<TaskFailed>()
            app.on<TaskFailed> { failed.complete(it) }
            var ran = false
            val handle =
                app.plugin("delayed") { ctx ->
                    ctx.after(1.seconds, clock = FailingClock(3)) { ran = true }
                }

            assertEquals(TaskFailed(plugin = "delayed", error = TimerBoom(3)), failed.await())
            assertEquals(false, ran)
            assertEquals(Fork.Status.Active, handle.status)
            app.dispose()
        }

    @Test
    fun failingClockInEveryReportsTaskFailed() =
        runTest {
            val app = Context.root()
            val failed = CompletableDeferred<TaskFailed>()
            app.on<TaskFailed> { failed.complete(it) }
            var ran = false
            val handle =
                app.plugin("ticker") { ctx ->
                    ctx.every(1.seconds, clock = FailingClock(4)) { ran = true }
                }

            assertEquals(TaskFailed(plugin = "ticker", error = TimerBoom(4)), failed.await())
            assertEquals(false, ran)
            assertEquals(Fork.Status.Active, handle.status)
            app.dispose()
        }

    @Test
    fun afterOnDisposedContextThrows() =
        runTest {
            val app = Context.root()
            app.dispose()

            assertFailsWith<CordisException.Disposed> {
                app.after(1.seconds, clock = ManualClock()) {}
            }
        }
}
