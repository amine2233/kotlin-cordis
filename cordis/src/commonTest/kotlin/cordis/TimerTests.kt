package cordis

import cordis.kernel.Context
import cordis.kernel.Plugin
import cordis.testing.ManualClock
import cordis.testing.settle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class TimerCounter {
    var value = 0
}

data class TickerConfig(val clock: ManualClock, val counter: TimerCounter)

object TickerPlugin : Plugin<TickerConfig> {
    override suspend fun apply(ctx: Context, config: TickerConfig) {
        val counter = config.counter
        ctx.every(1.seconds, clock = config.clock) {
            counter.value += 1
        }
    }
}

suspend fun waitForSleepers(clock: ManualClock, count: Int) {
    settle { clock.sleeperCount == count }
}

class TimerTests {
    @Test
    fun afterFiresOnce() = runTest {
        val app = Context.root()
        val clock = ManualClock()
        val counter = TimerCounter()
        app.after(5.seconds, clock = clock) { counter.value += 1 }
        waitForSleepers(clock, 1)
        clock.advance(4.seconds)
        yield()
        assertEquals(0, counter.value)
        clock.advance(1.seconds)
        settle { counter.value == 1 }
        clock.advance(60.seconds)
        yield()
        assertEquals(1, counter.value)
    }

    @Test
    fun everyFiresRepeatedly() = runTest {
        val app = Context.root()
        val clock = ManualClock()
        val counter = TimerCounter()
        app.every(1.seconds, clock = clock) { counter.value += 1 }
        for (expected in 1..3) {
            waitForSleepers(clock, 1)
            clock.advance(1.seconds)
            settle { counter.value == expected }
            assertEquals(expected, counter.value)
        }
    }

    @Test
    fun nothingFiresAfterDispose() = runTest {
        val app = Context.root()
        val clock = ManualClock()
        val counter = TimerCounter()
        val timer = app.every(1.seconds, clock = clock) { counter.value += 1 }
        waitForSleepers(clock, 1)
        timer()
        assertTrue(timer.isDisposed)
        assertEquals(0, clock.sleeperCount)
        clock.advance(10.seconds)
        yield()
        assertEquals(0, counter.value)
    }

    @Test
    fun scopeDisposeCancelsTimer() = runTest {
        val app = Context.root()
        val clock = ManualClock()
        val counter = TimerCounter()
        app.every(1.seconds, clock = clock) { counter.value += 1 }
        waitForSleepers(clock, 1)
        app.dispose()
        clock.advance(10.seconds)
        yield()
        assertEquals(0, counter.value)
        assertEquals(0, clock.sleeperCount)
    }

    @Test
    fun pluginRestartCancelsOldTimer() = runTest {
        val app = Context.root()
        val clock = ManualClock()
        val counter = TimerCounter()
        val handle = app.plugin(TickerPlugin, TickerConfig(clock, counter))
        waitForSleepers(clock, 1)
        handle.update(TickerConfig(clock, counter))
        waitForSleepers(clock, 1)
        assertEquals(1, clock.sleeperCount)
        clock.advance(1.seconds)
        settle { counter.value >= 1 }
        yield()
        assertEquals(1, counter.value)
    }
}
