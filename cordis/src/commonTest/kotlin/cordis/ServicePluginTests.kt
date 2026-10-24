package cordis

import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.ServiceKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

object GreetingKey : ServiceKey<String>

object GreetingPlugin : ServicePlugin<GreetingKey, String, String> {
    override val key = GreetingKey

    override suspend fun make(
        ctx: Context,
        config: String,
    ): String = "hello $config"
}

class BrokenException : Exception("Broken")

object BrokenGreetingPlugin : UnitServicePlugin<GreetingKey, String> {
    override val key = GreetingKey

    override suspend fun make(ctx: Context): String = throw BrokenException()
}

class ServicePluginTests {
    @Test
    fun providesMadeValue() =
        runTest {
            val app = Context.root()
            val handle = app.plugin(GreetingPlugin, "world")
            assertEquals(Fork.Status.Active, handle.status)
            assertEquals("hello world", app[GreetingKey])
        }

    @Test
    fun updateReprovidesNewValue() =
        runTest {
            val app = Context.root()
            val handle = app.plugin(GreetingPlugin, "a")
            handle.update("b")
            assertEquals("hello b", app[GreetingKey])
        }

    @Test
    fun disposeWithdrawsService() =
        runTest {
            val app = Context.root()
            val handle = app.plugin(GreetingPlugin, "a")
            handle.dispose()
            assertNull(app[GreetingKey])
        }

    @Test
    fun failingMakeLeavesNothingProvided() =
        runTest {
            val app = Context.root()
            val handle = app.plugin(BrokenGreetingPlugin)
            assertTrue(handle.status is Fork.Status.Failed)
            assertNull(app[GreetingKey])
        }

    @Test
    fun cordisReExportsKernel() {
        val app = Context.root()
        assertEquals("root", app.name)
    }
}
