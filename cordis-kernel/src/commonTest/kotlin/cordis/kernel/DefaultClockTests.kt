package cordis.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DefaultClockTests {
    @Test
    fun delayReturnsAfterRequestedDuration() =
        runTest {
            val requested = 100.milliseconds
            val timerSlack = 5.milliseconds
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    val start = DefaultClock.now
                    DefaultClock.delay(requested)
                    val elapsed = DefaultClock.now - start
                    assertTrue(
                        elapsed >= requested - timerSlack,
                        "elapsed $elapsed < requested $requested - slack $timerSlack",
                    )
                }
            }
        }
}
