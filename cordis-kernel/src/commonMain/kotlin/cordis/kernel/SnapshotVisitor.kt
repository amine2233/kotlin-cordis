package cordis.kernel

/**
 * Callbacks for a depth-first walk of a [RuntimeSnapshot], driven by [RuntimeSnapshot.accept].
 *
 * For each plugin, in this order: [enterPlugin], then [visitProvidedSlot] for each slot it provides
 * (in slot order), then [visitMissing] for each of its `missing` services (in list order), then its
 * children recursively (in plugin order), then [exitPlugin]. Every method defaults to doing nothing.
 */
public interface SnapshotVisitor {
    public fun enterPlugin(
        plugin: PluginInfo,
        depth: Int,
    ) {}

    public fun exitPlugin(
        plugin: PluginInfo,
        depth: Int,
    ) {}

    public fun visitProvidedSlot(
        slot: SlotInfo,
        provider: PluginInfo,
    ) {}

    public fun visitMissing(
        service: ServiceId,
        plugin: PluginInfo,
    ) {}
}

/**
 * Walks the plugin tree depth-first, calling [visitor] as described on [SnapshotVisitor].
 *
 * Roots are the plugins whose `parent` is `null` or not in [RuntimeSnapshot.plugins], walked in
 * plugin order at depth 0; a child's depth is its parent's depth + 1. Slots whose provider is `null`
 * or not in the snapshot produce no callback. Every plugin is entered and exited exactly once: after
 * the roots, any plugin left unvisited (a member of a parent cycle) is walked as an extra root at
 * depth 0, in plugin order. This is a pure read of the snapshot.
 */
public fun RuntimeSnapshot.accept(visitor: SnapshotVisitor) {
    val ids = plugins.mapTo(HashSet()) { it.id }
    val children = plugins.filter { it.parent in ids }.groupBy { it.parent }
    val providedSlots = slots.filter { it.provider in ids }.groupBy { it.provider }
    val visited = HashSet<PluginId>()

    fun walk(
        plugin: PluginInfo,
        depth: Int,
    ) {
        if (!visited.add(plugin.id)) return
        visitor.enterPlugin(plugin, depth)
        providedSlots[plugin.id].orEmpty().forEach { visitor.visitProvidedSlot(it, plugin) }
        plugin.missing.forEach { visitor.visitMissing(it, plugin) }
        children[plugin.id].orEmpty().forEach { walk(it, depth + 1) }
        visitor.exitPlugin(plugin, depth)
    }

    plugins.filter { it.parent !in ids }.forEach { walk(it, 0) }
    plugins.forEach { walk(it, 0) }
}
