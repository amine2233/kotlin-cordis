package cordis.ktor

import cordis.runtime.InMemoryEnabledPluginsStore
import cordis.runtime.PluginRuntime
import cordis.runtime.PluginRuntimeDefault
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CordisAdminTests {
    private fun ApplicationTestBuilder.mountAdmin(): () -> PluginRuntime {
        lateinit var runtime: PluginRuntime
        application {
            install(Cordis) { this.runtime = ::testRuntime }
            runtime = assertNotNull(cordisRuntime)
            routing { cordisAdmin(runtime) }
        }
        return { runtime }
    }

    @Test
    fun listsEveryPluginInCatalogOrder() =
        testApplication {
            val runtime = mountAdmin()

            val response = client.get("/plugins")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val items = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
            val states = runtime().state.value
            assertEquals(testCatalog.entries.map { it.key.value }, items.map { it.string("key") })
            assertEquals(states.map { it.enabled }, items.map { it.getValue("enabled").jsonPrimitive.boolean })
            assertEquals(
                states.map { it.metadata.locked },
                items.map {
                    it
                        .getValue("metadata")
                        .jsonObject
                        .getValue("locked")
                        .jsonPrimitive.boolean
                },
            )
            assertEquals(states.map { it.status?.let { s -> s::class.simpleName } }, items.map { it.string("status") })
            assertEquals(listOf("Active", "Active", null), items.map { it.string("status") })
            assertEquals(JsonNull, items.first().getValue("error"))
        }

    @Test
    fun togglesAPluginOffAndOn() =
        testApplication {
            val runtime = mountAdmin()

            val off = client.put("/plugins/${Feature.value}") { setBody("""{"enabled":false}""") }
            assertEquals(HttpStatusCode.OK, off.status)
            val offItem = Json.parseToJsonElement(off.bodyAsText()).jsonObject
            assertEquals(Feature.value, offItem.string("key"))
            assertEquals(false, offItem.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(
                false,
                runtime()
                    .state.value
                    .of(Feature)
                    .enabled,
            )

            val on = client.put("/plugins/${Feature.value}") { setBody("""{"enabled":true}""") }
            assertEquals(HttpStatusCode.OK, on.status)
            assertEquals(
                true,
                Json
                    .parseToJsonElement(on.bodyAsText())
                    .jsonObject
                    .getValue("enabled")
                    .jsonPrimitive.boolean,
            )
            assertEquals(
                true,
                runtime()
                    .state.value
                    .of(Feature)
                    .enabled,
            )
        }

    @Test
    fun refusesToDisableALockedPlugin() =
        testApplication {
            mountAdmin()

            val response = client.put("/plugins/${Core.value}") { setBody("""{"enabled":false}""") }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
        }

    @Test
    fun answersNotFoundForAnUnknownKey() =
        testApplication {
            mountAdmin()

            assertEquals(HttpStatusCode.NotFound, client.put("/plugins/nope") { setBody("""{"enabled":true}""") }.status)
        }

    @Test
    fun answersBadRequestForAMalformedBody() =
        testApplication {
            mountAdmin()

            for (body in listOf("not json", "{}", """{"enabled":"yes"}""")) {
                val response = client.put("/plugins/${Feature.value}") { setBody(body) }
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
            }
        }

    @Test
    fun answersServiceUnavailableWhenTheRuntimeIsNotStarted() =
        testApplication {
            application {
                install(Cordis)
                routing { cordisAdmin(PluginRuntimeDefault(cordis, testCatalog, InMemoryEnabledPluginsStore())) }
            }

            val response = client.put("/plugins/${Feature.value}") { setBody("""{"enabled":false}""") }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    private fun JsonObject.string(name: String): String? = getValue(name).jsonPrimitive.contentOrNull
}
