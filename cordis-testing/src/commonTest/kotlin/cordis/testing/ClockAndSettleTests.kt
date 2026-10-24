package cordis.testing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClockAndSettleTests {
    @Test
    fun advanceResumesDueSleepersOnly() =
        runTest {
            val clock = ManualClock()
            var woke = 0
            val job =
                launch {
                    clock.delay(5.seconds)
                    woke += 1
                }
            settle { clock.sleeperCount == 1 }
            assertEquals(1, clock.sleeperCount)
            clock.advance(4.seconds)
            assertEquals(1, clock.sleeperCount)
            clock.advance(1.seconds)
            assertEquals(0, clock.sleeperCount)
            job.join()
            assertEquals(1, woke)
        }

    @Test
    fun concurrentAdvanceIsSafe() =
        runTest {
            val clock = ManualClock()
            coroutineScope {
                val deferreds =
                    (0 until 100).map {
                        async {
                            clock.advance(1.milliseconds)
                        }
                    }
                deferreds.awaitAll()
            }
            assertEquals(100.milliseconds, clock.now)
        }

    @Test
    fun settleReturnsFalseWhenConditionNeverHolds() =
        runTest {
            assertFalse(settle { false })
        }

    @Test
    fun settleReturnsTrueWhenConditionHolds() =
        runTest {
            var ticks = 0
            val held =
                settle {
                    ticks += 1
                    ticks > 3
                }
            assertTrue(held)
        }
}
