package cordis.testing

import cordis.kernel.CordisClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * A deterministic clock that only moves when [advance] is called.
 */
class ManualClock(
    initialOffset: Duration = Duration.ZERO,
) : CordisClock {
    private val lock = Lock()
    private var current = initialOffset

    private class Sleeper(
        val deadline: Duration,
        val resume: () -> Unit,
    )

    private val sleepers = mutableMapOf<Int, Sleeper>()
    private var sleeperCounter = 0

    override val now: Duration
        get() = lock.withLock { current }

    val sleeperCount: Int
        get() = lock.withLock { sleepers.size }

    override suspend fun delay(duration: Duration) {
        val (deadline, isElapsed) =
            lock.withLock {
                val dl = current + duration
                Pair(dl, dl <= current)
            }
        if (isElapsed) return

        suspendCancellableCoroutine<Unit> { continuation ->
            var registeredId: Int? = null
            val isDueImmediately =
                lock.withLock {
                    if (deadline <= current) {
                        true
                    } else {
                        val newId = ++sleeperCounter
                        registeredId = newId
                        sleepers[newId] =
                            Sleeper(
                                deadline = deadline,
                                resume = { continuation.resume(Unit) },
                            )
                        false
                    }
                }

            if (isDueImmediately) {
                continuation.resume(Unit)
            } else {
                continuation.invokeOnCancellation {
                    registeredId?.let { id ->
                        lock.withLock {
                            sleepers.remove(id)
                        }
                    }
                }
            }
        }
    }

    /**
     * Moves the clock forward and resumes every sleeper whose deadline has passed.
     */
    fun advance(by: Duration) {
        val ready =
            lock.withLock {
                current += by
                val due = sleepers.filter { it.value.deadline <= current }
                for (key in due.keys) {
                    sleepers.remove(key)
                }
                due.values.sortedBy { it.deadline }
            }

        for (sleeper in ready) {
            sleeper.resume()
        }
    }
}
