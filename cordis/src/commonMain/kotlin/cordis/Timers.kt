package cordis

import cordis.kernel.Context
import cordis.kernel.CordisClock
import cordis.kernel.DefaultClock
import cordis.kernel.Disposable
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs [body] once after [delay]; cancelled when this context is disposed.
 */
suspend fun Context.after(
    delay: Duration,
    clock: CordisClock = DefaultClock,
    body: suspend () -> Unit
): Disposable {
    return ownTask {
        try {
            clock.delay(delay)
        } catch (_: Throwable) {
            return@ownTask
        }
        if (coroutineContext.isActive) {
            body()
        }
    }
}

/**
 * Runs [body] every [interval]; cancelled when this context is disposed.
 */
suspend fun Context.every(
    interval: Duration,
    clock: CordisClock = DefaultClock,
    body: suspend () -> Unit
): Disposable {
    return ownTask {
        while (coroutineContext.isActive) {
            try {
                clock.delay(interval)
            } catch (_: Throwable) {
                return@ownTask
            }
            if (!coroutineContext.isActive) return@ownTask
            body()
        }
    }
}

private suspend fun Context.ownTask(
    run: suspend CoroutineScope.() -> Unit
): Disposable {
    return effect {
        val job = registry.coroutineScope.launch {
            run()
        }
        return@effect {
            job.cancel()
            job.join()
        }
    }
}
