package cordis.kernel

/**
 * Renders the walked plugins as a box-drawing tree, one `<name> [<status>]` line per plugin, with
 * ` missing: <services>` appended when a plugin has missing required services.
 *
 * It accumulates across [RuntimeSnapshot.accept] calls: use a fresh instance per snapshot.
 */
public class TextTreeVisitor : SnapshotVisitor {
    private val entries = mutableListOf<Pair<PluginInfo, Int>>()

    override fun enterPlugin(
        plugin: PluginInfo,
        depth: Int,
    ) {
        entries += plugin to depth
    }

    /** The tree of everything visited so far, lines joined by `\n`, `""` when nothing was visited. */
    public fun render(): String {
        val hasLaterSibling = BooleanArray(entries.size)
        val laterAtDepth = mutableListOf<Boolean>()
        for (index in entries.indices.reversed()) {
            val depth = entries[index].second
            while (laterAtDepth.size > depth + 1) laterAtDepth.removeAt(laterAtDepth.lastIndex)
            while (laterAtDepth.size <= depth) laterAtDepth += false
            hasLaterSibling[index] = laterAtDepth[depth]
            laterAtDepth[depth] = true
        }

        val ancestors = mutableListOf<Boolean>()
        return entries.indices.joinToString("\n") { index ->
            val (plugin, depth) = entries[index]
            while (ancestors.size > depth) ancestors.removeAt(ancestors.lastIndex)
            val prefix = ancestors.joinToString("") { if (it) "│  " else "   " }
            val connector = if (hasLaterSibling[index]) "├─ " else "└─ "
            ancestors += hasLaterSibling[index]
            val missing =
                if (plugin.missing.isEmpty()) "" else " missing: " + plugin.missing.joinToString(", ") { it.description }
            "$prefix$connector${plugin.name} [${plugin.status.label()}]$missing"
        }
    }
}

/**
 * Renders the walked plugins as a Mermaid `flowchart TD`: one `p<n>` node per plugin in visit
 * order, a solid edge from each parent to its child, and a dotted edge labelled with the service
 * from each provider of a slot to every plugin that injects that service.
 *
 * It accumulates across [RuntimeSnapshot.accept] calls: use a fresh instance per snapshot.
 */
public class MermaidVisitor : SnapshotVisitor {
    private val nodes = mutableListOf<PluginInfo>()
    private val nodeIndex = mutableMapOf<PluginId, Int>()
    private val parentEdges = mutableListOf<Pair<Int, Int>>()
    private val providedSlots = mutableListOf<Pair<SlotInfo, Int>>()

    override fun enterPlugin(
        plugin: PluginInfo,
        depth: Int,
    ) {
        val index = nodes.size
        nodeIndex[plugin.parent]?.let { parentEdges += it to index }
        nodes += plugin
        nodeIndex[plugin.id] = index
    }

    override fun visitProvidedSlot(
        slot: SlotInfo,
        provider: PluginInfo,
    ) {
        nodeIndex[provider.id]?.let { providedSlots += slot to it }
    }

    /** The flowchart of everything visited so far, lines joined by `\n`, just `flowchart TD` when empty. */
    public fun render(): String {
        val lines = mutableListOf("flowchart TD")
        nodes.forEachIndexed { index, plugin ->
            lines += "  p$index[\"${escape("${plugin.name} [${plugin.status.label()}]")}\"]"
        }
        parentEdges.forEach { (parent, child) -> lines += "  p$parent --> p$child" }
        nodes.forEachIndexed { consumer, plugin ->
            plugin.inject.all.forEach { service ->
                providedSlots
                    .filter { (slot, _) -> slot.service == service.description }
                    .forEach { (slot, provider) ->
                        val label = if (slot.realm == Slot.SHARED_REALM) slot.service else "${slot.service}@${slot.realm}"
                        lines += "  p$provider -.->|\"${escape(label)}\"| p$consumer"
                    }
            }
        }
        return lines.joinToString("\n")
    }

    private fun escape(text: String): String =
        text
            .replace("#", "#35;")
            .replace("\"", "#quot;")
            .replace("<", "#lt;")
            .replace(">", "#gt;")
            .replace("\r", " ")
            .replace("\n", " ")
}

/**
 * Collects the walked plugins that need attention into a [HealthReport].
 *
 * It accumulates across [RuntimeSnapshot.accept] calls: use a fresh instance per snapshot.
 */
public class HealthVisitor : SnapshotVisitor {
    private val failed = mutableListOf<PluginInfo>()
    private val missingServices = mutableListOf<PluginInfo>()
    private val pending = mutableListOf<PluginInfo>()

    override fun enterPlugin(
        plugin: PluginInfo,
        depth: Int,
    ) {
        if (plugin.status is Fork.Status.Failed) failed += plugin
        if (plugin.missing.isNotEmpty()) missingServices += plugin
        if (plugin.status == Fork.Status.Pending) pending += plugin
    }

    /** The report for everything visited so far. */
    public fun report(): HealthReport =
        HealthReport(failed = failed.toList(), missingServices = missingServices.toList(), pending = pending.toList())
}

/**
 * Plugins that need attention, each list in visit order; a plugin can appear in more than one.
 * - `failed`: status is [Fork.Status.Failed], the reason is in `status`.
 * - `missingServices`: `missing` is not empty.
 * - `pending`: status is [Fork.Status.Pending].
 */
public data class HealthReport(
    val failed: List<PluginInfo>,
    val missingServices: List<PluginInfo>,
    val pending: List<PluginInfo>,
) {
    /** True when all three lists are empty. */
    public val healthy: Boolean get() = failed.isEmpty() && missingServices.isEmpty() && pending.isEmpty()
}

private fun Fork.Status.label(): String =
    when (this) {
        Fork.Status.Pending -> "Pending"
        Fork.Status.Loading -> "Loading"
        Fork.Status.Active -> "Active"
        is Fork.Status.Failed -> "Failed: $reason"
        Fork.Status.Disposed -> "Disposed"
    }
