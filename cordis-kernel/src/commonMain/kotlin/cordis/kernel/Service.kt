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
interface ServiceKey<T : Any> {
    val serviceId: ServiceId get() = ServiceId.of(this::class)
}

/**
 * Type-erased identity of a service key.
 */
data class ServiceId(
    val raw: Any,
    val description: String = (raw as? KClass<*>)?.simpleName ?: raw.toString()
) {
    override fun toString(): String = description

    companion object {
        fun of(kClass: KClass<*>): ServiceId =
            ServiceId(kClass, kClass.simpleName ?: "AnonymousKey")

        fun of(key: ServiceKey<*>): ServiceId =
            key.serviceId
    }
}

/**
 * Declares what a plugin needs.
 * - `required`: the plugin stays pending until all of them exist, and stops if one disappears.
 * - `optional`: the plugin starts without them, but restarts when they appear / change / vanish.
 */
data class Inject(
    val required: List<ServiceId> = emptyList(),
    val optional: List<ServiceId> = emptyList()
) {
    val all: List<ServiceId> get() = required + optional

    companion object {
        val NONE = Inject()

        fun of(
            required: List<ServiceKey<*>> = emptyList(),
            optional: List<ServiceKey<*>> = emptyList()
        ): Inject = Inject(
            required = required.map { it.serviceId },
            optional = optional.map { it.serviceId }
        )
    }
}

/**
 * Address of a service instance: (which service, in which isolation realm).
 * Realms are the "space" half of spatiotemporal composability.
 */
data class Slot(
    val service: ServiceId,
    val realm: String = SHARED_REALM
) {
    override fun toString(): String =
        if (realm == SHARED_REALM) service.toString() else "$service@$realm"

    companion object {
        const val SHARED_REALM = "shared"
    }
}

/**
 * Errors thrown by Cordis operations.
 */
sealed class CordisException(message: String) : Exception(message) {
    class MissingService(val service: ServiceId) :
        CordisException("Service $service is not available in this context")

    class ServiceConflict(val slot: Slot, val existingOwner: String) :
        CordisException("Service $slot is already provided by $existingOwner")

    class Disposed :
        CordisException("Context has been disposed")

    class NotPublished(val slot: Slot) :
        CordisException("Service $slot is not published by this context")
}

// MARK: - Events

/**
 * Marker interface for all Cordis events.
 */
interface CordisEvent

/**
 * Emitted whenever a slot gets a new instance or loses it.
 */
data class ServiceChanged(
    val slot: Slot,
    val isAvailable: Boolean
) : CordisEvent

/**
 * Emitted when a plugin's `apply` throws. The plugin's scope is rolled back.
 */
data class PluginFailed(
    val plugin: String,
    val error: Throwable
) : CordisEvent

/**
 * Emitted when a teardown throws. Remaining teardowns of the same scope still run.
 * `plugin` is the name of the owning context (`root` for the graph root).
 */
data class TeardownFailed(
    val plugin: String,
    val error: Throwable
) : CordisEvent

/**
 * Emitted on every plugin status transition.
 */
data class PluginStatusChanged(
    val plugin: String,
    val status: Fork.Status
) : CordisEvent
