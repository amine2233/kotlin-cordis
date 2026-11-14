package cordis.ktor

import cordis.kernel.Fork
import cordis.kernel.PluginKey
import cordis.runtime.PluginMetadata
import cordis.runtime.PluginRuntime
import cordis.runtime.PluginRuntimeException
import cordis.runtime.PluginState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class EnabledRequest(
    val enabled: Boolean,
)

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

/**
 * Mounts JSON admin routes for [runtime] relative to this route:
 * - `GET plugins` lists every [PluginState] in catalog order;
 * - `PUT plugins/{key}` with `{"enabled": <boolean>}` calls [PluginRuntime.setEnabled] and returns the entry's new state.
 *
 * `PUT` answers 400 for a malformed body, 404 for an unknown key, 409 for disabling a locked entry and 503 when the
 * runtime is not started, each with a `text/plain` message.
 *
 * These routes have no authentication or authorization: the host must mount them behind its own auth.
 */
public fun Route.cordisAdmin(runtime: PluginRuntime) {
    get("plugins") {
        call.respondText(Json.encodeToString(runtime.state.value.map { it.toResponse() }), ContentType.Application.Json)
    }
    put("plugins/{key}") {
        val key = PluginKey(call.parameters.getOrFail("key"))
        val request =
            try {
                Json.decodeFromString<EnabledRequest>(call.receiveText())
            } catch (_: SerializationException) {
                call.respondText("Expected a JSON body {\"enabled\": <boolean>}", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@put
            }
        try {
            runtime.setEnabled(key, request.enabled)
        } catch (error: PluginRuntimeException) {
            val status =
                when (error) {
                    is PluginRuntimeException.UnknownPlugin -> HttpStatusCode.NotFound
                    is PluginRuntimeException.LockedPlugin -> HttpStatusCode.Conflict
                    PluginRuntimeException.NotStarted -> HttpStatusCode.ServiceUnavailable
                }
            call.respondText(error.message, ContentType.Text.Plain, status)
            return@put
        }
        val state = runtime.state.value.first { it.key == key }
        call.respondText(Json.encodeToString(state.toResponse()), ContentType.Application.Json)
    }
}

private fun PluginState.toResponse(): PluginStateResponse =
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
