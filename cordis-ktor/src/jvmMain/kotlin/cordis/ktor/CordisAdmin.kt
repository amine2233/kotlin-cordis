package cordis.ktor

import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.HealthVisitor
import cordis.kernel.MermaidVisitor
import cordis.kernel.PluginInfo
import cordis.kernel.PluginKey
import cordis.kernel.TextTreeVisitor
import cordis.kernel.accept
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

@Serializable
internal data class HealthItemResponse(
    val name: String,
    val key: String?,
    val status: String,
    val reason: String?,
    val missing: List<String>,
)

@Serializable
internal data class HealthResponse(
    val healthy: Boolean,
    val failed: List<HealthItemResponse>,
    val missingServices: List<HealthItemResponse>,
    val pending: List<HealthItemResponse>,
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

/**
 * Mounts read-only routes that render a fresh [Context.snapshot] of [context] relative to this route, walked with a
 * new visitor on every request:
 * - `GET snapshot/tree` answers the [TextTreeVisitor] tree as `text/plain`;
 * - `GET snapshot/mermaid` answers the [MermaidVisitor] `flowchart TD` source as `text/plain`;
 * - `GET health` answers the [HealthVisitor] report as JSON
 *   `{"healthy": <boolean>, "failed": [...], "missingServices": [...], "pending": [...]}`, each item
 *   `{"name", "key", "status", "reason", "missing"}`, with status 200 when healthy and 503 otherwise.
 *
 * These routes have no authentication or authorization: the host must mount them behind its own auth. They expose
 * plugin names, keys and failure reasons.
 */
public fun Route.cordisSnapshot(context: Context) {
    get("snapshot/tree") {
        val visitor = TextTreeVisitor()
        context.snapshot().accept(visitor)
        call.respondText(visitor.render(), ContentType.Text.Plain)
    }
    get("snapshot/mermaid") {
        val visitor = MermaidVisitor()
        context.snapshot().accept(visitor)
        call.respondText(visitor.render(), ContentType.Text.Plain)
    }
    get("health") {
        val visitor = HealthVisitor()
        context.snapshot().accept(visitor)
        val report = visitor.report()
        val response =
            HealthResponse(
                healthy = report.healthy,
                failed = report.failed.map { it.toHealthItem() },
                missingServices = report.missingServices.map { it.toHealthItem() },
                pending = report.pending.map { it.toHealthItem() },
            )
        call.respondText(
            Json.encodeToString(response),
            ContentType.Application.Json,
            if (report.healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
        )
    }
}

private fun PluginInfo.toHealthItem(): HealthItemResponse =
    HealthItemResponse(
        name = name,
        key = key?.value,
        status = status::class.simpleName.orEmpty(),
        reason = (status as? Fork.Status.Failed)?.reason,
        missing = missing.map { it.description },
    )

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
