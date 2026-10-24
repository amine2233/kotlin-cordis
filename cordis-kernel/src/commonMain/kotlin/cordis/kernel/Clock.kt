package cordis.kernel

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Clock abstraction used by timer effects ([Context.after], [Context.every]).
 */
interface CordisClock {
    val now: Duration
    suspend fun delay(duration: Duration)
}

/**
 * Default system clock backed by [TimeSource.Monotonic] and [kotlinx.coroutines.delay].
 */
object DefaultClock : CordisClock {
    private val startMark = TimeSource.Monotonic.markNow()

    override val now: Duration
        get() = startMark.elapsedNow()

    override suspend fun delay(duration: Duration) {
        delay(duration)
    }
}
