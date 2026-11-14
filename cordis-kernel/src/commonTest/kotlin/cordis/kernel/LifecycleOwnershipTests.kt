package cordis.kernel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

object ReprovidedKey : ServiceKey<Int>

private class EventLog(
    app: Context,
) {
    private val lock = Lock()
    private val events = mutableListOf<CordisEvent>()

    init {
        app.on<CordisEvent> { event -> lock.withLock { events.add(event) } }
    }

    val all: List<CordisEvent>
        get() = lock.withLock { events.toList() }

    fun statuses(plugin: String): List<Fork.Status> =
        all
            .filterIsInstance<PluginStatusChanged>()
            .filter {
                it.plugin == plugin
            }.map { it.status }

    fun failures(): List<PluginFailed> = all.filterIsInstance<PluginFailed>()
}

private class HangingPlugin(
    val log: Probe,
    val forkReady: CompletableDeferred<Fork>,
) : Plugin<Unit> {
    override val name = "hanging"

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        ctx.onDispose { log.log.add("teardown") }
        forkReady.complete(checkNotNull(ctx.fork))
        awaitCancellation()
    }
}

class LifecycleOwnershipTests {
    @Test
    fun cancellingTheCallerMidApplyLetsThePluginBecomeActive() =
        runTest {
            val app = Context.root()
            val events = EventLog(app)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()

            val caller =
                launch {
                    app.plugin("slow") {
                        entered.complete(Unit)
                        gate.await()
                    }
                }
            entered.await()
            caller.cancelAndJoin()
            gate.complete(Unit)

            eventually { Fork.Status.Active in events.statuses("slow") }
            assertEquals(
                Fork.Status.Active,
                app
                    .snapshot()
                    .plugins
                    .single { it.name == "slow" }
                    .status,
            )
            assertTrue(events.failures().isEmpty())
            assertTrue(events.statuses("slow").none { it is Fork.Status.Failed })
            app.dispose()
        }

    @Test
    fun disposingMidApplyEndsDisposedAndRollsBack() =
        runTest {
            val app = Context.root()
            val events = EventLog(app)
            val probe = Probe()
            val forkReady = CompletableDeferred<Fork>()
            val plugin = HangingPlugin(probe, forkReady)

            val loader = async { app.plugin(plugin) }
            val handle = PluginHandle(forkReady.await(), plugin)
            launch { handle.dispose() }.join()
            val loaded = loader.await()

            assertEquals(Fork.Status.Disposed, handle.status)
            assertEquals(Fork.Status.Disposed, loaded.status)
            assertEquals(listOf("teardown"), probe.log)
            assertTrue(events.failures().isEmpty())
            assertEquals(Fork.Status.Disposed, events.statuses("hanging").last())
            assertEquals(1, events.statuses("hanging").count { it == Fork.Status.Disposed })
            assertTrue(app.snapshot().plugins.none { it.name == "hanging" })
            app.dispose()
        }

    @OptIn(InternalCordisApi::class)
    @Test
    fun loadDisposeTogglesLeaveNoScopeEntries() =
        runTest {
            val app = Context.root()
            val before = app.scope.size
            repeat(1000) { app.plugin("p") {}.dispose() }
            assertEquals(before, app.scope.size)
            assertTrue(app.snapshot().plugins.isEmpty())
            app.dispose()
        }

    @OptIn(InternalCordisApi::class)
    @Test
    fun reprovidingKeepsOneWithdrawEntry() =
        runTest {
            val app = Context.root()
            val before = app.scope.size
            repeat(1000) { app.provide(ReprovidedKey, it) }
            assertTrue(app.scope.size <= before + 1)
            assertEquals(999, app[ReprovidedKey])
            app.dispose()
            assertNull(app[ReprovidedKey])
        }

    @Test
    fun reprovidedKeyIsStillWithdrawnOnDispose() =
        runTest {
            val app = Context.root()
            val handle = app.plugin("provider") { ctx -> repeat(1000) { ctx.provide(ReprovidedKey, it) } }
            assertEquals(999, app[ReprovidedKey])
            handle.dispose()
            assertNull(app[ReprovidedKey])
            app.dispose()
        }

    @Test
    fun rootDisposeCancelsTheGraphScope() =
        runTest {
            val app = Context.root()
            val job = app.launch { awaitCancellation() }
            app.dispose()
            assertFalse(app.registry.coroutineScope.isActive)
            assertTrue(job.isCancelled)
            app.dispose()
        }

    @Test
    fun lateRootTeardownRunsOnTheGraphDispatcher() =
        runTest {
            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            val app = Context.root(dispatcher = dispatcher)
            app.dispose()

            val ranOn = CompletableDeferred<CoroutineContext.Element?>()
            app.onDispose { ranOn.complete(currentCoroutineContext()[ContinuationInterceptor]) }

            assertEquals(dispatcher, awaitReal(ranOn))
        }

    @Test
    fun lateForkTeardownRunsOnTheGraphDispatcher() =
        runTest {
            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            val app = Context.root(dispatcher = dispatcher)
            var pluginContext: Context? = null
            val handle = app.plugin("p") { ctx -> pluginContext = ctx }
            handle.dispose()

            val ranOn = CompletableDeferred<CoroutineContext.Element?>()
            checkNotNull(pluginContext).onDispose { ranOn.complete(currentCoroutineContext()[ContinuationInterceptor]) }

            assertEquals(dispatcher, awaitReal(ranOn))
            app.dispose()
        }

    @Test
    fun cancellationFromApplyIsNotAFailure() =
        runTest {
            val app = Context.root()
            val events = EventLog(app)
            val probe = Probe()

            assertFailsWith<CancellationException> {
                app.plugin("cancelling") { ctx ->
                    ctx.onDispose { probe.log.add("rolled-back") }
                    throw CancellationException("stop")
                }
            }

            assertEquals(
                Fork.Status.Pending,
                app
                    .snapshot()
                    .plugins
                    .single { it.name == "cancelling" }
                    .status,
            )
            assertTrue(events.failures().isEmpty())
            assertTrue(events.statuses("cancelling").none { it is Fork.Status.Failed })
            assertEquals(listOf("rolled-back"), probe.log)
            app.dispose()
        }

    private suspend fun eventually(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!condition()) delay(1)
            }
        }
    }

    private suspend fun <T> awaitReal(deferred: CompletableDeferred<T>): T =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { deferred.await() }
        }
}
