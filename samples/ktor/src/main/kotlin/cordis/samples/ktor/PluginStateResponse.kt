package cordis.samples.ktor

import cordis.kernel.Fork
import cordis.runtime.PluginMetadata
import cordis.runtime.PluginState
import kotlinx.serialization.Serializable

// Mirrors the internal DTOs of cordis-ktor's CordisAdmin.kt, so an event matches the `GET /admin/plugins` body.
@Serializable
internal data class PluginMetadataResponse(
    val title: String,
    val description: String,
    val locked: Boolean,
    val enabledByDefault: Boolean,
)

@Serializable
internal data class PluginStateResponse(
    val key: String,
    val metadata: PluginMetadataResponse,
    val enabled: Boolean,
    val status: String?,
    val error: String?,
    val missing: List<String>,
)

internal fun PluginState.toResponse(): PluginStateResponse =
    PluginStateResponse(
        key = key.value,
        metadata = metadata.toResponse(),
        enabled = enabled,
        status = status?.let { it::class.simpleName },
        error = (status as? Fork.Status.Failed)?.reason,
        missing = missing.map { it.description },
    )

private fun PluginMetadata.toResponse(): PluginMetadataResponse =
    PluginMetadataResponse(
        title = title,
        description = description,
        locked = locked,
        enabledByDefault = enabledByDefault,
    )
