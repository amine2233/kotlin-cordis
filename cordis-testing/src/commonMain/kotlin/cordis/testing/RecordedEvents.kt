package cordis.testing

import cordis.kernel.Context
import cordis.kernel.CordisEvent
import cordis.kernel.PluginFailed
import cordis.kernel.PluginStatusChanged
import cordis.kernel.ServiceChanged
import cordis.kernel.TeardownFailed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.concurrent.Volatile
import kotlin.reflect.KClass

/**
 * Lifecycle and failure events captured during a [withTestApp] run, in emission order.
 */
public class RecordedEvents {
    private val lock = Lock()

    @Volatile
    private var snapshot: List<CordisEvent> = emptyList()

    public val all: List<CordisEvent>
        get() = snapshot

    public inline fun <reified E : CordisEvent> ofType(): List<E> = all.filterIsInstance<E>()

    public fun <E : CordisEvent> ofType(type: KClass<E>): List<E> =
        all.filter { type.isInstance(it) }.map {
            @Suppress("UNCHECKED_CAST")
            it as E
        }

    public val failures: List<CordisEvent>
        get() = all.filter { it is PluginFailed || it is TeardownFailed }

    public fun record(event: CordisEvent) {
        lock.withLock { snapshot = snapshot + event }
    }
}

/**
 * Runs [body] against a fresh root context, always disposes it, and returns the recorded events.
 */
public suspend fun withTestApp(body: suspend (Context, RecordedEvents) -> Unit): RecordedEvents =
    withTestApp(Dispatchers.Default.limitedParallelism(1), body)

/**
 * Runs [body] against a fresh root context whose graph runs on [dispatcher], always disposes it,
 * and returns the recorded events. Pass `StandardTestDispatcher(testScheduler)` to settle the graph
 * deterministically with `advanceUntilIdle()`.
 */
public suspend fun withTestApp(
    dispatcher: CoroutineDispatcher,
    body: suspend (Context, RecordedEvents) -> Unit,
): RecordedEvents {
    val ctx = Context.root(dispatcher)
    val events = RecordedEvents()
    ctx.on<PluginFailed> { events.record(it) }
    ctx.on<PluginStatusChanged> { events.record(it) }
    ctx.on<ServiceChanged> { events.record(it) }
    ctx.on<TeardownFailed> { events.record(it) }
    try {
        body(ctx, events)
    } finally {
        ctx.dispose()
    }
    return events
}
