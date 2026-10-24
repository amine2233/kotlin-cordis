package cordis.testing

import cordis.kernel.Context
import cordis.kernel.CordisEvent
import cordis.kernel.PluginFailed
import cordis.kernel.PluginStatusChanged
import cordis.kernel.ServiceChanged
import cordis.kernel.TeardownFailed
import kotlin.reflect.KClass

/**
 * Lifecycle and failure events captured during a [withTestApp] run, in emission order.
 */
class RecordedEvents {
    val all = mutableListOf<CordisEvent>()

    inline fun <reified E : CordisEvent> ofType(): List<E> {
        return all.filterIsInstance<E>()
    }

    fun <E : CordisEvent> ofType(type: KClass<E>): List<E> {
        return all.filter { type.isInstance(it) }.map {
            @Suppress("UNCHECKED_CAST")
            it as E
        }
    }

    val failures: List<CordisEvent>
        get() = all.filter { it is PluginFailed || it is TeardownFailed }

    fun record(event: CordisEvent) {
        all.add(event)
    }
}

/**
 * Runs [body] against a fresh root context, always disposes it, and returns the recorded events.
 */
suspend fun withTestApp(
    body: suspend (Context, RecordedEvents) -> Unit
): RecordedEvents {
    val ctx = Context.root()
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
