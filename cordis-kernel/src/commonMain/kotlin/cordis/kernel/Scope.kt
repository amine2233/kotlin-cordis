package cordis.kernel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Teardown action executed when a [Scope] or [Disposable] is disposed.
 */
typealias Disposer = suspend () -> Unit

/**
 * Idempotent teardown handle. Invoking it multiple times is a no-op.
 */
class Disposable(
    private var action: Disposer?,
    private val failureHandler: suspend (Throwable) -> Unit = {},
) {
    val isDisposed: Boolean
        get() = action == null

    suspend operator fun invoke() {
        val currentAction = action ?: return
        action = null
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
    private val handles = mutableMapOf<Int, Disposable>()
    private var counter = 0

    var isDisposed: Boolean = false
        private set

    /**
     * Registers a teardown. If the scope is already dead, the teardown runs immediately.
     */
    fun collect(teardown: Disposer): Disposable {
        counter++
        val id = counter
        val handle =
            Disposable(
                action = {
                    handles.remove(id)
                    teardown()
                },
                failureHandler = failureHandler,
            )

        if (isDisposed) {
            val scope = coroutineScope ?: CoroutineScope(Dispatchers.Default)
            scope.launch { handle() }
        } else {
            handles[id] = handle
        }
        return handle
    }

    suspend fun dispose() {
        if (isDisposed) return
        isDisposed = true

        val ordered =
            handles.entries
                .sortedByDescending { it.key }
                .map { it.value }
        handles.clear()

        for (handle in ordered) {
            handle()
        }
    }
}
