package cordis

import cordis.testing.withTestApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val interval = 10.milliseconds
private val quietWindow = 100.milliseconds
private val waitLimit = 5.seconds

class DefaultClockTimerTests {
    @Test
    fun afterFiresExactlyOnce() =
        runTest {
            withTestApp { app, _ ->
                val fires = Channel<Unit>(Channel.UNLIMITED)
                app.after(interval) { fires.trySend(Unit) }
                withContext(Dispatchers.Default) {
                    withTimeout(waitLimit) {
                        fires.receive()
                        delay(quietWindow)
                    }
                }
                assertTrue(fires.tryReceive().isFailure, "after fired more than once")
            }
        }

    @Test
    fun everyFiresRepeatedly() =
        runTest {
            withTestApp { app, _ ->
                val fires = Channel<Unit>(Channel.UNLIMITED)
                app.every(interval) { fires.trySend(Unit) }
                withContext(Dispatchers.Default) {
                    withTimeout(waitLimit) {
                        fires.receive()
                        fires.receive()
                    }
                }
            }
        }

    @Test
    fun everyStopsAfterDispose() =
        runTest {
            withTestApp { app, _ ->
                val fires = Channel<Unit>(Channel.UNLIMITED)
                val timer = app.every(interval) { fires.trySend(Unit) }
                withContext(Dispatchers.Default) {
                    withTimeout(waitLimit) {
                        fires.receive()
                        timer()
                        while (fires.tryReceive().isSuccess) {
                            continue
                        }
                        delay(quietWindow)
                    }
                }
                assertTrue(fires.tryReceive().isFailure, "every fired after dispose")
            }
        }
}
