package cordis.kernel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Teardown action executed when a [Scope] or [Disposable] is disposed.
 */
typealias Disposer = suspend () -> Unit

private val disposableLock = Lock()

/**
 * Idempotent teardown handle. Invoking it multiple times, even concurrently, runs the action once.
 */
class Disposable(
    action: Disposer?,
    private val failureHandler: suspend (Throwable) -> Unit = {},
) {
    @Volatile
    private var action: Disposer? = action

    val isDisposed: Boolean
        get() = action == null

    suspend operator fun invoke() {
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
class Scope(
    private val failureHandler: suspend (Throwable) -> Unit = {},
    private val coroutineScope: CoroutineScope? = null,
) {
    private val lock = Lock()
    private val handles = mutableMapOf<Int, Disposable>()
    private var counter = 0

    @Volatile
    var isDisposed: Boolean = false
        private set

    /**
     * Registers a teardown. If the scope is already dead, the teardown runs immediately.
     */
    fun collect(teardown: Disposer): Disposable {
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
            val scope = coroutineScope ?: CoroutineScope(Dispatchers.Default)
            scope.launch { handle() }
        }
        return handle
    }

    suspend fun dispose() {
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
