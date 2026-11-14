package cordis.ktor

import cordis.kernel.Context
import cordis.runtime.PluginRuntime
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CordisPluginTests {
    @Test
    fun stopsTheRuntimeThenDisposesTheContextOnShutdown() {
        lateinit var context: Context
        lateinit var runtime: PluginRuntimeMock
        testApplication {
            application {
                install(Cordis) { this.runtime = { PluginRuntimeMock(it).also { mock -> runtime = mock } } }
                context = cordis
            }
            startApplication()
            assertEquals(1, runtime.startCount)
            assertEquals(0, runtime.stopCount)
        }
        assertEquals(1, runtime.stopCount)
        assertEquals(false, runtime.contextDisposedAtStop)
        assertTrue(context.scope.isDisposed)
    }

    @Test
    fun stopsARealRuntimeOnShutdown() {
        lateinit var context: Context
        lateinit var runtime: PluginRuntime
        testApplication {
            application {
                install(Cordis) { this.runtime = ::testRuntime }
                context = cordis
                runtime = assertNotNull(cordisRuntime)
            }
            startApplication()
            assertNotNull(
                runtime.state.value
                    .of(Feature)
                    .status,
            )
        }
        assertTrue(runtime.state.value.all { it.status == null })
        assertTrue(context.scope.isDisposed)
    }

    @Test
    fun ownsAHostSuppliedContext() {
        val myRoot = Context.root()
        lateinit var installed: Context
        testApplication {
            application {
                install(Cordis) { context = myRoot }
                installed = cordis
            }
            startApplication()
        }
        assertSame(myRoot, installed)
        assertTrue(myRoot.scope.isDisposed)
    }

    @Test
    fun cordisRuntimeIsNullWithoutARuntime() {
        var read = false
        var runtime: PluginRuntime? = null
        testApplication {
            application {
                install(Cordis)
                runtime = cordisRuntime
                read = true
            }
            startApplication()
        }
        assertTrue(read)
        assertNull(runtime)
    }

    @Test
    fun accessorsThrowWithoutThePlugin() =
        testApplication {
            var error: Throwable? = null
            routing {
                get("/context") {
                    error = runCatching { call.cordis }.exceptionOrNull()
                    call.respondText("done")
                }
            }
            val response = client.get("/context")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("done", response.bodyAsText())
            val thrown = assertIs<IllegalStateException>(error)
            assertTrue(thrown.message.orEmpty().contains("install(Cordis)"))
        }
}
