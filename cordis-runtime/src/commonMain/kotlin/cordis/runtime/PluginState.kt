package cordis.runtime

import cordis.kernel.Fork
import cordis.kernel.PluginKey
import cordis.kernel.ServiceId

/**
 * What the runtime reports for one catalog entry.
 *
 * @property key The catalog key of the entry.
 * @property metadata The entry's catalog metadata.
 * @property enabled The effective value: the stored override, else the default; always `true` when locked.
 * @property status The status of the entry's fork in the latest snapshot, or `null` when the runtime holds no live handle for it.
 * @property missing The required services the fork is waiting for; empty when [status] is `null`.
 */
public data class PluginState(
    val key: PluginKey,
    val metadata: PluginMetadata,
    val enabled: Boolean,
    val status: Fork.Status?,
    val missing: List<ServiceId>,
)
