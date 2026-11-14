package cordis

import cordis.kernel.Context
import cordis.kernel.CordisClock
import cordis.kernel.DefaultClock
import cordis.kernel.Disposable
import kotlinx.coroutines.isActive
import kotlin.time.Duration

/**
 * Runs [body] once after [delay]; cancelled when this context is disposed.
 */
public suspend fun Context.after(
    delay: Duration,
    clock: CordisClock = DefaultClock,
    body: suspend () -> Unit,
): Disposable {
    val job =
        launch {
            clock.delay(delay)
            if (isActive) body()
        }
    return Disposable(
        action = {
            job.cancel()
            job.join()
        },
    )
}

/**
 * Runs [body] every [interval]; cancelled when this context is disposed.
 */
public suspend fun Context.every(
    interval: Duration,
    clock: CordisClock = DefaultClock,
    body: suspend () -> Unit,
): Disposable {
    val job =
        launch {
            while (isActive) {
                clock.delay(interval)
                if (!isActive) return@launch
                body()
            }
        }
    return Disposable(
        action = {
            job.cancel()
            job.join()
        },
    )
}
