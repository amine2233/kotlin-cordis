package cordis.kernel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

data class FlowPing(
    val n: Int,
) : CordisEvent

object NeedsValue : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(ValueKey))

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

class ReactiveApiTests {
    @Test
    fun eventsFlowDeliversTypedEventsAndUnsubscribes() =
        runTest {
            val app = Context.root()
            val received = CompletableDeferred<List<FlowPing>>()
            app.launch { received.complete(app.events<FlowPing>().take(2).toList()) }
            while (app.registry.listenerCount(FlowPing::class) == 0) kotlinx.coroutines.yield()

            app.emit(FlowPing(1))
            app.emit(FlowPing(2))

            assertEquals(listOf(FlowPing(1), FlowPing(2)), received.await())
            while (app.registry.listenerCount(FlowPing::class) != 0) kotlinx.coroutines.yield()
            app.dispose()
        }

    @Test
    fun serviceFlowEmitsCurrentThenChanges() =
        runTest {
            val app = Context.root()
            val seen = CompletableDeferred<List<String?>>()
            app.launch { seen.complete(app.serviceFlow(ValueKey).take(3).toList()) }
            while (app.registry.listenerCount(ServiceChanged::class) == 0) kotlinx.coroutines.yield()

            val provider = app.plugin("provider") { it.provide(ValueKey, "a") }
            provider.awaitActive()
            provider.dispose()

            assertEquals(listOf(null, "a", null), seen.await())
            app.dispose()
        }

    @Test
    fun launchedJobIsCancelledWhenPluginDisposes() =
        runTest {
            val app = Context.root()
            val started = CompletableDeferred<Unit>()
            var job: kotlinx.coroutines.Job? = null
            val handle =
                app.plugin("worker") { ctx ->
                    job =
                        ctx.launch {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                }
            started.await()
            assertTrue(job!!.isActive)

            handle.dispose()

            assertTrue(job!!.isCancelled)
            app.dispose()
        }

    @Test
    fun lambdaPluginHonoursInject() =
        runTest {
            val app = Context.root()
            var ran = false
            val handle = app.plugin("lambda", Inject.of(required = listOf(ValueKey))) { ran = true }
            assertEquals(Fork.Status.Pending, handle.status)

            app.plugin("provider") { it.provide(ValueKey, "x") }

            assertEquals(Fork.Status.Active, handle.awaitActive())
            assertTrue(ran)
            app.dispose()
        }

    @Test
    fun awaitActiveResumesWhenDependencyArrives() =
        runTest {
            val app = Context.root()
            val handle = app.plugin(NeedsValue)
            val result = CompletableDeferred<Fork.Status>()
            app.launch { result.complete(handle.awaitActive()) }

            app.plugin("provider") { it.provide(ValueKey, "x") }

            assertEquals(Fork.Status.Active, result.await())
            app.dispose()
        }

    @Test
    fun awaitActiveReturnsFailed() =
        runTest {
            val app = Context.root()
            val handle = app.plugin("boom") { error("nope") }
            assertTrue(handle.awaitActive() is Fork.Status.Failed)
            app.dispose()
        }

    @Test
    fun launchOnDisposedContextThrows() =
        runTest {
            val app = Context.root()
            var captured: Context? = null
            val handle = app.plugin("short") { captured = it }
            handle.dispose()

            assertFailsWith<CordisException.Disposed> { captured!!.launch { } }
            app.dispose()
        }

    @Test
    fun jobCanDisposeItsOwnPluginWithoutDeadlock() =
        runTest {
            val app = Context.root()
            val handleReady = CompletableDeferred<PluginHandle<Unit>>()
            val finished = CompletableDeferred<Unit>()
            val handle =
                app.plugin("selfish") { ctx ->
                    ctx.launch {
                        handleReady.await().dispose()
                        finished.complete(Unit)
                    }
                }
            handleReady.complete(handle)

            finished.await()
            assertEquals(Fork.Status.Disposed, handle.status)
            app.dispose()
        }

    @Test
    fun awaitActiveReturnsDisposed() =
        runTest {
            val app = Context.root()
            val handle = app.plugin(NeedsValue)
            handle.dispose()
            assertEquals(Fork.Status.Disposed, handle.awaitActive())
            app.dispose()
        }

    @Test
    fun serviceFlowInsideZoneStillHearsChanges() =
        runTest {
            val app = Context.root()
            val zoned = app.zone("ui")
            val seen = CompletableDeferred<List<String?>>()
            app.launch { seen.complete(zoned.serviceFlow(ValueKey).take(2).toList()) }
            while (app.registry.listenerCount(ServiceChanged::class) == 0) kotlinx.coroutines.yield()

            app.plugin("provider") { it.provide(ValueKey, "z") }

            assertEquals(listOf(null, "z"), seen.await())
            app.dispose()
        }
}
