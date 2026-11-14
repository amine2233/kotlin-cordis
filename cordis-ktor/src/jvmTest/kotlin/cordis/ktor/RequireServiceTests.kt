package cordis.ktor

import cordis.runtime.PluginRuntime
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class RequireServiceTests {
    @Test
    fun gatesTheRouteOnTheServiceSlotBothWays() =
        testApplication {
            lateinit var runtime: PluginRuntime
            application {
                install(Cordis) { this.runtime = ::testRuntime }
                runtime = assertNotNull(cordisRuntime)
                routing {
                    route("/value", HttpMethod.Get) {
                        requireService(ProvidedKey) { v -> call.respondText("$v") }
                    }
                }
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/value").status)

            runtime.setEnabled(Provider, true)
            val provided = client.get("/value")
            assertEquals(HttpStatusCode.OK, provided.status)
            assertEquals("$PROVIDED_VALUE", provided.bodyAsText())

            runtime.setEnabled(Provider, false)
            val withdrawn = client.get("/value")
            assertEquals(HttpStatusCode.ServiceUnavailable, withdrawn.status)
            assertEquals("", withdrawn.bodyAsText())
        }

    @Test
    fun answersTheConfiguredStatusWhenTheSlotIsEmpty() =
        testApplication {
            var handled = false
            application {
                install(Cordis)
                routing {
                    route("/value", HttpMethod.Get) {
                        requireService(ProvidedKey, whenMissing = HttpStatusCode.NotFound) {
                            handled = true
                            call.respondText("handled")
                        }
                    }
                }
            }

            assertEquals(HttpStatusCode.NotFound, client.get("/value").status)
            assertFalse(handled)
        }
}
