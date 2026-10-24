package cordis.kernel

/**
 * Opaque identity of a plugin application within one snapshot-able graph.
 */
data class PluginId(
    val raw: Any,
) {
    override fun toString(): String = "PluginId($raw)"
}

/**
 * Provisional record shape: may change before it is declared stable.
 */
data class PluginInfo(
    val id: PluginId,
    val name: String,
    val status: Fork.Status,
    val parent: PluginId?,
)

/**
 * Provisional record shape: may change before it is declared stable.
 */
data class SlotInfo(
    val service: String,
    val realm: String,
    val provider: PluginId?,
    val generation: Int,
)

/**
 * Read-only, immutable copy of the graph.
 * Plugins are in registration order; slots are in provision order (ascending generation).
 */
data class RuntimeSnapshot(
    val plugins: List<PluginInfo>,
    val slots: List<SlotInfo>,
)
