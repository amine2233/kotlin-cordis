package cordis.samples.shared

import cordis.testing.ManualClock
import cordis.testing.withTestApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherTimerTests {
    @Test
    fun readingChangesOnlyOnceTheIntervalHasPassed() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val clock = ManualClock()
                ctx.plugin(WeatherPlugin, WeatherConfig(interval = 5.minutes, clock = clock))
                advanceUntilIdle()
                val weather = assertNotNull(ctx[WeatherKey])
                val initial = weather.reading.value
                assertEquals(1, clock.sleeperCount)

                clock.advance(5.minutes - 1.seconds)
                advanceUntilIdle()
                assertEquals(initial, weather.reading.value)

                clock.advance(1.seconds)
                advanceUntilIdle()
                val next = weather.reading.value
                assertNotEquals(initial, next)
                assertEquals(1, next.update)

                clock.advance(5.minutes)
                advanceUntilIdle()
                assertEquals(2, weather.reading.value.update)
            }
        }
}
