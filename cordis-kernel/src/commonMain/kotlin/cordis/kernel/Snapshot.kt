package cordis.kernel

import kotlin.jvm.JvmInline

/**
 * Opaque identity of a plugin application within one snapshot-able graph.
 */
public data class PluginId(
    val raw: Any,
) {
    override fun toString(): String = "PluginId($raw)"
}

/**
 * Stable, obfuscation-proof identifier of a plugin declaration, declared by the plugin itself.
 * Not required to be unique: every application of the same plugin carries the same key.
 */
@JvmInline
public value class PluginKey(
    public val value: String,
)

/**
 * Provisional record shape: may change before it is declared stable.
 * `missing` lists the required services whose slot, in the fork's realms, has no provider right now.
 */
public data class PluginInfo(
    val id: PluginId,
    val name: String,
    val status: Fork.Status,
    val parent: PluginId?,
    val key: PluginKey?,
    val inject: Inject,
    val missing: List<ServiceId>,
)

/**
 * Provisional record shape: may change before it is declared stable.
 */
public data class SlotInfo(
    val service: String,
    val realm: String,
    val provider: PluginId?,
    val generation: Int,
    val providerKey: PluginKey?,
)

/**
 * Read-only, immutable copy of the graph.
 * Plugins are in registration order; slots are in provision order (ascending generation).
 */
public data class RuntimeSnapshot(
    val plugins: List<PluginInfo>,
    val slots: List<SlotInfo>,
)
