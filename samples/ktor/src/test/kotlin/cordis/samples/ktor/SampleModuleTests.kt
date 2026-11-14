package cordis.samples.ktor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SampleModuleTests {
    private val directory: Path = createTempDirectory("cordis-sample")
    private val storeFile: Path = directory.resolve("enabled-plugins.json")

    @AfterTest
    fun deleteDirectory() {
        directory.toFile().deleteRecursively()
    }

    private fun ApplicationTestBuilder.startSample() {
        application { sampleModule(storeFile) }
    }

    private suspend fun HttpClient.setEnabled(
        key: String,
        enabled: Boolean,
    ): HttpResponse = put("/admin/plugins/$key") { setBody("""{"enabled":$enabled}""") }

    private fun JsonElement.enabledOf(key: String): Boolean =
        jsonArray
            .map { it.jsonObject }
            .first { it.getValue("key").jsonPrimitive.content == key }
            .getValue("enabled")
            .jsonPrimitive.boolean

    @Test
    fun weatherAnswersWhileEnabledAnd503WhileDisabled() =
        testApplication {
            startSample()

            val enabled = client.get("/weather")
            assertEquals(HttpStatusCode.OK, enabled.status)
            assertEquals(ContentType.Application.Json, enabled.contentType()?.withoutParameters())
            val reading = Json.parseToJsonElement(enabled.bodyAsText()).jsonObject
            assertEquals(0, reading.getValue("update").jsonPrimitive.int)
            assertEquals(18, reading.getValue("temperatureCelsius").jsonPrimitive.int)

            assertEquals(HttpStatusCode.OK, client.setEnabled("weather", false).status)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/weather").status)

            assertEquals(HttpStatusCode.OK, client.setEnabled("weather", true).status)
            assertEquals(HttpStatusCode.OK, client.get("/weather").status)
        }

    @Test
    fun notesAnswer503WhileNotesIsDisabled() =
        testApplication {
            startSample()

            assertEquals(HttpStatusCode.OK, client.get("/notes").status)
            assertEquals(HttpStatusCode.OK, client.setEnabled("notes", false).status)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/notes").status)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/notes") { setBody("""{"text":"x"}""") }.status)
        }

    @Test
    fun notesAnswer503WhileTheDatabaseIsDisabled() =
        testApplication {
            startSample()

            assertEquals(HttpStatusCode.OK, client.setEnabled("database", false).status)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/notes").status)

            assertEquals(HttpStatusCode.OK, client.setEnabled("database", true).status)
            assertEquals(HttpStatusCode.OK, client.get("/notes").status)
        }

    @Test
    fun postedNotesAreListed() =
        testApplication {
            startSample()

            assertEquals(HttpStatusCode.Created, client.post("/notes") { setBody("""{"text":"first"}""") }.status)
            assertEquals(HttpStatusCode.Created, client.post("/notes") { setBody("""{"text":"second"}""") }.status)

            val listed = client.get("/notes")
            assertEquals(HttpStatusCode.OK, listed.status)
            assertEquals(ContentType.Application.Json, listed.contentType()?.withoutParameters())
            assertEquals(listOf("first", "second"), Json.decodeFromString<List<String>>(listed.bodyAsText()))
        }

    @Test
    fun malformedOrBlankNotesAreRejected() =
        testApplication {
            startSample()

            val malformed = client.post("/notes") { setBody("not json") }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertEquals(ContentType.Text.Plain, malformed.contentType()?.withoutParameters())
            assertEquals(HttpStatusCode.BadRequest, client.post("/notes") { setBody("""{"text":"  "}""") }.status)
            assertEquals("[]", client.get("/notes").bodyAsText())
        }

    @Test
    fun adminListsAndTogglesPlugins() =
        testApplication {
            startSample()

            val listed = client.get("/admin/plugins")
            assertEquals(HttpStatusCode.OK, listed.status)
            val plugins = Json.parseToJsonElement(listed.bodyAsText())
            assertEquals(
                listOf("logger", "database", "notes", "weather", "analytics"),
                plugins.jsonArray.map {
                    it.jsonObject
                        .getValue("key")
                        .jsonPrimitive.content
                },
            )
            assertTrue(plugins.enabledOf("weather"))

            val off = client.setEnabled("weather", false)
            assertEquals(HttpStatusCode.OK, off.status)
            assertFalse(
                Json
                    .parseToJsonElement(off.bodyAsText())
                    .jsonObject
                    .getValue("enabled")
                    .jsonPrimitive.boolean,
            )
            assertFalse(Json.parseToJsonElement(client.get("/admin/plugins").bodyAsText()).enabledOf("weather"))
        }

    @Test
    fun disablingTheLockedLoggerIsAConflict() =
        testApplication {
            startSample()

            assertEquals(HttpStatusCode.Conflict, client.setEnabled("logger", false).status)
        }

    @Test
    fun eventStreamSendsTheCurrentStateThenEveryChange() =
        testApplication {
            startSample()
            val sseClient = createClient { install(SSE) }
            val listed = Json.parseToJsonElement(client.get("/admin/plugins").bodyAsText())

            withTimeout(10.seconds) {
                sseClient.sse("/admin/plugins/events") {
                    val states = incoming.mapNotNull { event -> event.data?.let { Json.parseToJsonElement(it) } }.produceIn(this)
                    assertEquals(listed, states.receive())

                    assertEquals(HttpStatusCode.OK, client.setEnabled("weather", false).status)

                    while (states.receive().enabledOf("weather")) continue
                    states.cancel()
                }
            }
        }

    @Test
    fun togglesSurviveARestart() {
        testApplication {
            startSample()
            assertEquals(HttpStatusCode.OK, client.setEnabled("weather", false).status)
        }

        testApplication {
            startSample()
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/weather").status)
            assertFalse(Json.parseToJsonElement(client.get("/admin/plugins").bodyAsText()).enabledOf("weather"))
        }
    }

    @Test
    fun adminPageIsStaticHtmlOnTheEventStream() =
        testApplication {
            startSample()

            val page = client.get("/admin")
            assertEquals(HttpStatusCode.OK, page.status)
            assertEquals(ContentType.Text.Html, page.contentType()?.withoutParameters())
            val html = page.bodyAsText()
            assertTrue("EventSource" in html)
            listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write").forEach { sink ->
                assertFalse(sink in html, "admin page uses $sink")
            }
        }
}
