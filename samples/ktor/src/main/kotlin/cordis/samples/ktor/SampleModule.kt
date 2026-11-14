package cordis.samples.ktor

import cordis.ktor.Cordis
import cordis.ktor.cordisAdmin
import cordis.ktor.cordisRuntime
import cordis.ktor.requireService
import cordis.runtime.PluginRuntime
import cordis.runtime.PluginRuntimeDefault
import cordis.samples.shared.NotesKey
import cordis.samples.shared.WeatherKey
import cordis.samples.shared.sampleCatalog
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

@Serializable
private data class NoteRequest(
    val text: String,
)

@Serializable
private data class WeatherResponse(
    val update: Int,
    val temperatureCelsius: Int,
)

/**
 * Runs the sample catalog with toggles persisted in [storeFile], and mounts the feature, admin and event routes.
 */
fun Application.sampleModule(storeFile: Path) {
    install(Cordis) {
        runtime = { context -> PluginRuntimeDefault(context, sampleCatalog(), JsonFileEnabledPluginsStore(storeFile)) }
    }
    install(SSE)
    val runtime = checkNotNull(cordisRuntime)
    val adminPage = checkNotNull(Application::class.java.getResource("/admin.html")).readText()

    routing {
        notesRoutes()
        weatherRoutes()
        route("/admin") {
            get { call.respondText(adminPage, ContentType.Text.Html) }
            cordisAdmin(runtime)
            pluginEvents(runtime)
        }
    }
}

private fun Route.notesRoutes() {
    route("/notes", HttpMethod.Get) {
        requireService(NotesKey) { notes ->
            call.respondText(Json.encodeToString(notes.list()), ContentType.Application.Json)
        }
    }
    route("/notes", HttpMethod.Post) {
        requireService(NotesKey) { notes ->
            val request =
                try {
                    Json.decodeFromString<NoteRequest>(call.receiveText())
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (request == null || request.text.isBlank()) {
                call.respondText("Expected a JSON body {\"text\": <non-blank string>}", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@requireService
            }
            notes.add(request.text)
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.Created)
        }
    }
}

private fun Route.weatherRoutes() {
    route("/weather", HttpMethod.Get) {
        requireService(WeatherKey) { weather ->
            val reading = weather.reading.value
            call.respondText(
                Json.encodeToString(WeatherResponse(reading.update, reading.temperatureCelsius)),
                ContentType.Application.Json,
            )
        }
    }
}

private fun Route.pluginEvents(runtime: PluginRuntime) {
    sse("plugins/events") {
        runtime.state.collect { states ->
            send(ServerSentEvent(data = Json.encodeToString(states.map { it.toResponse() })))
        }
    }
}
