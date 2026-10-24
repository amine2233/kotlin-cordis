package cordis.kernel

import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Identity of one successful `provide`; stable across in-place updates.
 */
data class OccurrenceId(val rawValue: Int)

data class ZoneId(val rawValue: Int)

private data class ZoneKey(val parent: ZoneId?, val label: String)

/**
 * Shared state of one plugin graph: service slots, live forks, event listeners.
 * Internal — plugins only ever talk to [Context].
 */
class Registry(
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) {
    val coroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    class Entry(
        var value: Any,
        val owner: Any, // providing Context
        val ownerName: String,
        val fork: PluginId?, // providing Fork (null = root)
        val generation: Int,
        val occurrence: OccurrenceId
    )

    class Listener(
        val id: Int,
        val zone: ZoneId?,
        val handler: suspend (CordisEvent) -> Unit
    )

    private val slots = mutableMapOf<Slot, Entry>()
    private val forks = mutableMapOf<PluginId, Fork>()
    private val forkOrder = mutableMapOf<PluginId, Int>()
    private var forkCounter = 0
    private val listeners = mutableMapOf<KClass<out CordisEvent>, MutableList<Listener>>()
    private var generation = 0
    private var occurrenceCounter = 0
    private var listenerCounter = 0
    private val zoneParents = mutableMapOf<ZoneId, ZoneId?>()
    private val zoneChildren = mutableMapOf<ZoneKey, ZoneId>()
    private var realmCounter = 0

    // MARK: Services

    fun entry(slot: Slot): Entry? = slots[slot]

    suspend fun provide(slot: Slot, value: Any, by: Context) {
        val existing = slots[slot]
        if (existing != null && existing.owner != by) {
            throw CordisException.ServiceConflict(slot, existingOwner = existing.ownerName)
        }
        generation += 1
        occurrenceCounter += 1
        slots[slot] = Entry(
            value = value,
            owner = by,
            ownerName = by.name,
            fork = by.fork?.id,
            generation = generation,
            occurrence = OccurrenceId(occurrenceCounter)
        )
        changed(slot, isAvailable = true, fork = by.fork?.id)
    }

    fun <T : Any> update(slot: Slot, by: Context, mutate: (T) -> Unit) {
        val entry = slots[slot] ?: throw CordisException.NotPublished(slot)
        if (entry.owner != by) {
            throw CordisException.NotPublished(slot)
        }
        @Suppress("UNCHECKED_CAST")
        mutate(entry.value as T)
    }

    fun <T : Any> replaceValue(slot: Slot, by: Context, mutate: (T) -> T) {
        val entry = slots[slot] ?: throw CordisException.NotPublished(slot)
        if (entry.owner != by) {
            throw CordisException.NotPublished(slot)
        }
        @Suppress("UNCHECKED_CAST")
        entry.value = mutate(entry.value as T)
    }

    suspend fun withdraw(slot: Slot, owner: Any, fork: PluginId?) {
        val current = slots[slot] ?: return
        if (current.owner != owner) return

        slots.remove(slot)
        changed(slot, isAvailable = false, fork = fork)
    }

    fun anonymousRealm(): String {
        realmCounter += 1
        return "anon-$realmCounter"
    }

    /**
     * Propagates a slot change to every dependent fork. This is the whole "reactivity":
     * dependents start, stop or restart, which may cascade into further slot changes.
     */
    private suspend fun changed(slot: Slot, isAvailable: Boolean, fork: PluginId?) {
        emit(ServiceChanged(slot = slot, isAvailable = isAvailable))
        val dependents = forks.values.filter { it.id != fork && it.dependsOn(slot) }
        for (dep in dependents) {
            dep.refresh()
        }
    }

    // MARK: Forks

    fun register(fork: Fork) {
        forks[fork.id] = fork
        forkCounter += 1
        forkOrder[fork.id] = forkCounter
    }

    fun unregister(fork: Fork) {
        forks.remove(fork.id)
        forkOrder.remove(fork.id)
    }

    fun snapshot(): RuntimeSnapshot {
        val plugins = forks.values
            .sortedBy { forkOrder[it.id] ?: 0 }
            .map { fork ->
                PluginInfo(
                    id = fork.id,
                    name = fork.name,
                    status = fork.status,
                    parent = fork.parent.fork?.id
                )
            }
        val slotInfos = slots.entries
            .sortedBy { it.value.generation }
            .map { (slot, entry) ->
                SlotInfo(
                    service = slot.service.description,
                    realm = slot.realm,
                    provider = entry.fork,
                    generation = entry.generation
                )
            }
        return RuntimeSnapshot(plugins = plugins, slots = slotInfos)
    }

    // MARK: Events

    fun <E : CordisEvent> on(
        type: KClass<E>,
        zone: ZoneId?,
        handler: suspend (E) -> Unit
    ): Int {
        listenerCounter += 1
        val listener = Listener(id = listenerCounter, zone = zone) { any ->
            if (type.isInstance(any)) {
                @Suppress("UNCHECKED_CAST")
                handler(any as E)
            }
        }
        listeners.getOrPut(type) { mutableListOf() }.add(listener)
        return listenerCounter
    }

    fun off(type: KClass<out CordisEvent>, id: Int) {
        listeners[type]?.removeAll { it.id == id }
    }

    fun zone(label: String, under: ZoneId?): ZoneId {
        val key = ZoneKey(parent = under, label = label)
        zoneChildren[key]?.let { return it }

        val id = ZoneId(zoneParents.size)
        zoneParents[id] = under
        zoneChildren[key] = id
        return id
    }

    private fun isAncestorOrSelf(candidate: ZoneId, of: ZoneId?): Boolean {
        var current = of
        while (current != null) {
            if (current == candidate) return true
            current = zoneParents[current]
        }
        return false
    }

    suspend fun emit(event: CordisEvent, from: ZoneId? = null) {
        // Snapshot: listeners added/removed during dispatch don't affect this round.
        val snapshot = listeners.entries
            .filter { (type, _) -> type.isInstance(event) }
            .flatMap { it.value.toList() }

        for (listener in snapshot) {
            val listenerZone = listener.zone
            if (listenerZone != null && !isAncestorOrSelf(listenerZone, of = from)) {
                continue
            }
            listener.handler(event)
        }
    }

    fun listenerCount(type: KClass<out CordisEvent>): Int {
        return listeners[type]?.size ?: 0
    }
}
