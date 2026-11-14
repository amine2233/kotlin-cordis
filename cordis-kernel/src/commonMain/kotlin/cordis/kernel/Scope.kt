package cordis.kernel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Teardown action executed when a [Scope] or [Disposable] is disposed.
 */
public typealias Disposer = suspend () -> Unit

private val disposableLock = Lock()

/**
 * Idempotent teardown handle. Invoking it multiple times, even concurrently, runs the action once.
 */
public class Disposable(
    action: Disposer?,
    private val failureHandler: suspend (Throwable) -> Unit = {},
) {
    @Volatile
    private var action: Disposer? = action

    public val isDisposed: Boolean
        get() = action == null

    public suspend operator fun invoke() {
        val currentAction = disposableLock.withLock { action.also { action = null } } ?: return
        try {
            currentAction()
        } catch (t: Throwable) {
            failureHandler(t)
        }
    }
}

/**
 * The "time" half of spatiotemporal composability:
 * every side effect registered in a scope is undone when the scope dies,
 * in reverse order of registration (LIFO, like `defer`).
 */
public class Scope(
    private val failureHandler: suspend (Throwable) -> Unit = {},
    private val coroutineScope: CoroutineScope,
) {
    private val lock = Lock()
    private val handles = mutableMapOf<Int, Disposable>()
    private var counter = 0

    @Volatile
    public var isDisposed: Boolean = false
        private set

    @InternalCordisApi
    public val size: Int
        get() = lock.withLock { handles.size }

    /**
     * Registers a teardown. If the scope is already dead, the teardown is launched at once on [coroutineScope].
     */
    public fun collect(teardown: Disposer): Disposable {
        val (handle, recorded) =
            lock.withLock {
                counter++
                val id = counter
                val handle =
                    Disposable(
                        action = {
                            lock.withLock { handles.remove(id) }
                            teardown()
                        },
                        failureHandler = failureHandler,
                    )
                val recorded = !isDisposed
                if (recorded) handles[id] = handle
                handle to recorded
            }

        if (!recorded) {
            coroutineScope.launch { handle() }
        }
        return handle
    }

    public suspend fun dispose() {
        val ordered =
            lock.withLock {
                if (isDisposed) return@withLock null
                isDisposed = true
                handles.entries
                    .sortedByDescending { it.key }
                    .map { it.value }
                    .also { handles.clear() }
            } ?: return

        for (handle in ordered) {
            handle()
        }
    }
}
