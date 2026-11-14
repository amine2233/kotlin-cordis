package cordis.kernel

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Clock abstraction used by timer effects ([Context.after], [Context.every]).
 */
public interface CordisClock {
    public val now: Duration

    public suspend fun delay(duration: Duration)
}

/**
 * Default system clock backed by [TimeSource.Monotonic] and [kotlinx.coroutines.delay].
 */
public object DefaultClock : CordisClock {
    private val startMark = TimeSource.Monotonic.markNow()

    override val now: Duration
        get() = startMark.elapsedNow()

    override suspend fun delay(duration: Duration) {
        kotlinx.coroutines.delay(duration)
    }
}
