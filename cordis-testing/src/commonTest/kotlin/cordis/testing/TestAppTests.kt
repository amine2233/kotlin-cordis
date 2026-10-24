package cordis.testing

import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.Inject
import cordis.kernel.Plugin
import cordis.kernel.PluginFailed
import cordis.kernel.PluginStatusChanged
import cordis.kernel.ServiceChanged
import cordis.kernel.ServiceKey
import cordis.kernel.TeardownFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TestBoomException : Exception("Boom")

object FailingPlugin : Plugin<Unit> {
    override suspend fun apply(ctx: Context, config: Unit) {
        throw TestBoomException()
    }
}

object WidgetKey : ServiceKey<Int>

object ConsumerPlugin : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(WidgetKey))
    override suspend fun apply(ctx: Context, config: Unit) {}
}

class TestAppTests {
    @Test
    fun throwingBodyStillDisposes() = runTest {
        var disposed = false
        assertFailsWith<TestBoomException> {
            withTestApp { ctx, _ ->
                ctx.onDispose { disposed = true }
                throw TestBoomException()
            }
        }
        assertTrue(disposed)
    }

    @Test
    fun normalBodyDisposes() = runTest {
        var disposed = false
        withTestApp { ctx, _ ->
            ctx.onDispose { disposed = true }
        }
        assertTrue(disposed)
    }

    @Test
    fun recordsLifecycleEventsInOrder() = runTest {
        val events = withTestApp { ctx, recorded ->
            val handle = ctx.plugin(ConsumerPlugin)
            assertEquals(Fork.Status.Pending, handle.status)
            ctx.provide(WidgetKey, 1)
            assertEquals(Fork.Status.Active, handle.status)
            assertTrue(recorded.all.isNotEmpty())
        }
        val statuses = events.ofType<PluginStatusChanged>().map { it.status }
        assertEquals(listOf(Fork.Status.Loading, Fork.Status.Active), statuses.take(2))

        val firstChange = events.all.indexOfFirst { it is ServiceChanged }
        val firstActive = events.all.indexOfFirst {
            (it as? PluginStatusChanged)?.status == Fork.Status.Active
        }
        assertTrue(firstChange != -1)
        assertTrue(firstChange < firstActive)
    }

    @Test
    fun failingPluginAppearsInFailures() = runTest {
        val events = withTestApp { ctx, _ ->
            ctx.plugin(FailingPlugin)
        }
        assertEquals(1, events.failures.size)
        val failure = events.ofType<PluginFailed>().firstOrNull()?.error
        assertTrue(failure is TestBoomException)
    }

    @Test
    fun teardownFailureRaisedByBodyIsVisibleAfterDispose() = runTest {
        val events = withTestApp { ctx, _ ->
            ctx.onDispose { throw TestBoomException() }
        }
        val failures = events.ofType<TeardownFailed>()
        assertEquals(1, failures.size)
        assertEquals("root", failures.first().plugin)
        assertTrue(failures.first().error is TestBoomException)
        assertEquals(1, events.failures.size)
    }
}
