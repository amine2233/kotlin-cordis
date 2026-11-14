package cordis.kernel

import kotlin.reflect.KClass

/**
 * Typed service key, similar to SwiftUI's `EnvironmentKey` or React Context.
 *
 * Example:
 * ```kotlin
 * object DatabaseKey : ServiceKey<Database>
 * ```
 */
public interface ServiceKey<T : Any> {
    public val serviceId: ServiceId get() = ServiceId.of(this::class)
}

/**
 * Typed contribution point: many plugins add items, everyone reads one shared, ordered list.
 * Its identity is the key's class.
 *
 * Example:
 * ```kotlin
 * object DrawerItems : ContributionKey<DrawerItem>
 * ```
 */
public interface ContributionKey<T : Any>

/**
 * Type-erased identity of a service key.
 */
public data class ServiceId(
    val raw: Any,
    val description: String = (raw as? KClass<*>)?.simpleName ?: raw.toString(),
) {
    override fun toString(): String = description

    public companion object {
        public fun of(kClass: KClass<*>): ServiceId = ServiceId(kClass, kClass.simpleName ?: "AnonymousKey")

        public fun of(key: ServiceKey<*>): ServiceId = key.serviceId
    }
}

/**
 * Declares what a plugin needs.
 * - `required`: the plugin stays pending until all of them exist, and stops if one disappears.
 * - `optional`: the plugin starts without them, but restarts when they appear / change / vanish.
 */
public data class Inject(
    val required: List<ServiceId> = emptyList(),
    val optional: List<ServiceId> = emptyList(),
) {
    val all: List<ServiceId> get() = required + optional

    public companion object {
        public val NONE: Inject = Inject()

        public fun of(
            required: List<ServiceKey<*>> = emptyList(),
            optional: List<ServiceKey<*>> = emptyList(),
        ): Inject =
            Inject(
                required = required.map { it.serviceId },
                optional = optional.map { it.serviceId },
            )
    }
}

/**
 * Address of a service instance: (which service, in which isolation realm).
 * Realms are the "space" half of spatiotemporal composability.
 */
public data class Slot(
    val service: ServiceId,
    val realm: String = SHARED_REALM,
) {
    override fun toString(): String = if (realm == SHARED_REALM) service.toString() else "$service@$realm"

    public companion object {
        public const val SHARED_REALM: String = "shared"
    }
}

/**
 * Errors thrown by Cordis operations.
 */
public sealed class CordisException(
    message: String,
) : Exception(message) {
    public class MissingService(
        public val service: ServiceId,
    ) : CordisException("Service $service is not available in this context")

    public class ServiceConflict(
        public val slot: Slot,
        public val existingOwner: String,
    ) : CordisException("Service $slot is already provided by $existingOwner")

    public class Disposed : CordisException("Context has been disposed")

    public class NotPublished(
        public val slot: Slot,
    ) : CordisException("Service $slot is not published by this context")
}

// MARK: - Events

/**
 * Marker interface for all Cordis events.
 */
public interface CordisEvent

/**
 * Emitted whenever a slot gets a new instance or loses it.
 */
public data class ServiceChanged(
    val slot: Slot,
    val isAvailable: Boolean,
) : CordisEvent

/**
 * Emitted when a plugin's `apply` throws. The plugin's scope is rolled back.
 */
public data class PluginFailed(
    val plugin: String,
    val error: Throwable,
    val key: PluginKey? = null,
) : CordisEvent

/**
 * Emitted when a teardown throws. Remaining teardowns of the same scope still run.
 * `plugin` is the name of the owning context (`root` for the graph root).
 */
public data class TeardownFailed(
    val plugin: String,
    val error: Throwable,
    val key: PluginKey? = null,
) : CordisEvent

/**
 * Emitted when a listener throws while [event] is being delivered. The remaining listeners still
 * run and the emitter never sees the error. A listener that throws while handling a `ListenerFailed`
 * is not reported again.
 * Plugin status is unchanged: the plugin stays `Active`; only a throwing `apply` yields `Failed`
 * and [PluginFailed].
 */
public data class ListenerFailed(
    val event: CordisEvent,
    val error: Throwable,
) : CordisEvent

/**
 * Emitted when a coroutine in the graph's scope fails: a `ctx.launch` body, a timer body or an
 * internal launch. `plugin` is the name of the launching context (`root` when there is no owner).
 * Plugin status is unchanged: the plugin stays `Active`; only a throwing `apply` yields `Failed`
 * and [PluginFailed].
 */
public data class TaskFailed(
    val plugin: String,
    val error: Throwable,
) : CordisEvent

/**
 * Emitted on every plugin status transition.
 */
public data class PluginStatusChanged(
    val plugin: String,
    val status: Fork.Status,
    val key: PluginKey? = null,
) : CordisEvent
