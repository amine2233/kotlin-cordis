package cordis.kernel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
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
            withRoot(StandardTestDispatcher(testScheduler)) { app ->
                val received = CompletableDeferred<List<FlowPing>>()
                app.launch { received.complete(app.events<FlowPing>().take(2).toList()) }
                advanceUntilIdle()
                assertEquals(1, app.registry.listenerCount(FlowPing::class))

                app.emit(FlowPing(1))
                app.emit(FlowPing(2))

                assertEquals(listOf(FlowPing(1), FlowPing(2)), received.await())
                advanceUntilIdle()
                assertEquals(0, app.registry.listenerCount(FlowPing::class))
            }
        }

    @Test
    fun serviceFlowEmitsCurrentThenChanges() =
        runTest {
            withRoot(StandardTestDispatcher(testScheduler)) { app ->
                val seen = CompletableDeferred<List<String?>>()
                app.launch { seen.complete(app.serviceFlow(ValueKey).take(3).toList()) }
                advanceUntilIdle()
                assertEquals(1, app.registry.listenerCount(ServiceChanged::class))

                val provider = app.plugin("provider") { it.provide(ValueKey, "a") }
                provider.awaitActive()
                provider.dispose()

                assertEquals(listOf(null, "a", null), seen.await())
            }
        }

    @Test
    fun launchedJobIsCancelledWhenPluginDisposes() =
        runTest {
            withRoot { app ->
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

                assertTrue(job.isCancelled)
            }
        }

    @Test
    fun lambdaPluginHonoursInject() =
        runTest {
            withRoot { app ->
                var ran = false
                val handle = app.plugin("lambda", Inject.of(required = listOf(ValueKey))) { ran = true }
                assertEquals(Fork.Status.Pending, handle.status)

                app.plugin("provider") { it.provide(ValueKey, "x") }

                assertEquals(Fork.Status.Active, handle.awaitActive())
                assertTrue(ran)
            }
        }

    @Test
    fun awaitActiveResumesWhenDependencyArrives() =
        runTest {
            withRoot { app ->
                val handle = app.plugin(NeedsValue)
                val result = CompletableDeferred<Fork.Status>()
                app.launch { result.complete(handle.awaitActive()) }

                app.plugin("provider") { it.provide(ValueKey, "x") }

                assertEquals(Fork.Status.Active, result.await())
            }
        }

    @Test
    fun awaitActiveReturnsFailed() =
        runTest {
            withRoot { app ->
                val handle = app.plugin("boom") { error("nope") }
                assertTrue(handle.awaitActive() is Fork.Status.Failed)
            }
        }

    @Test
    fun launchOnDisposedContextThrows() =
        runTest {
            withRoot { app ->
                var captured: Context? = null
                val handle = app.plugin("short") { captured = it }
                handle.dispose()

                assertFailsWith<CordisException.Disposed> { captured!!.launch { } }
            }
        }

    @Test
    fun jobCanDisposeItsOwnPluginWithoutDeadlock() =
        runTest {
            withRoot { app ->
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
            }
        }

    @Test
    fun awaitActiveReturnsDisposed() =
        runTest {
            withRoot { app ->
                val handle = app.plugin(NeedsValue)
                handle.dispose()
                assertEquals(Fork.Status.Disposed, handle.awaitActive())
            }
        }

    @Test
    fun serviceFlowInsideZoneStillHearsChanges() =
        runTest {
            withRoot(StandardTestDispatcher(testScheduler)) { app ->
                val zoned = app.zone("ui")
                val seen = CompletableDeferred<List<String?>>()
                app.launch { seen.complete(zoned.serviceFlow(ValueKey).take(2).toList()) }
                advanceUntilIdle()
                assertEquals(1, app.registry.listenerCount(ServiceChanged::class))

                app.plugin("provider") { it.provide(ValueKey, "z") }

                assertEquals(listOf(null, "z"), seen.await())
            }
        }

    @Test
    fun pluginHandleUpdateRunsOnRegistryDispatcher() =
        runTest {
            withRoot { app ->
                val interceptors = mutableListOf<ContinuationInterceptor?>()
                val plugin =
                    object : Plugin<Int> {
                        override suspend fun apply(
                            ctx: Context,
                            config: Int,
                        ) {
                            interceptors += currentCoroutineContext()[ContinuationInterceptor]
                        }
                    }
                val handle = app.plugin(plugin, 1)
                interceptors.clear()

                assertEquals(UpdateOutcome.Applied, handle.update(2))

                assertEquals(listOf<ContinuationInterceptor?>(app.registry.dispatcher), interceptors)
            }
        }

    @Test
    fun pluginHandleDisposeRunsTeardownOnRegistryDispatcher() =
        runTest {
            withRoot { app ->
                var interceptor: ContinuationInterceptor? = null
                val handle =
                    app.plugin("teardown") { ctx ->
                        ctx.onDispose { interceptor = currentCoroutineContext()[ContinuationInterceptor] }
                    }

                handle.dispose()

                assertEquals<ContinuationInterceptor?>(app.registry.dispatcher, interceptor)
            }
        }

    @Test
    fun flowsCompleteWhenContextIsDisposed() =
        runTest {
            withRoot(StandardTestDispatcher(testScheduler)) { app ->
                val services = async { app.serviceFlow(ValueKey).toList() }
                val events = async { app.events<FlowPing>().toList() }
                advanceUntilIdle()
                assertEquals(1, app.registry.listenerCount(ServiceChanged::class))
                assertEquals(1, app.registry.listenerCount(FlowPing::class))

                app.dispose()

                assertEquals(listOf(null), services.await())
                assertEquals(emptyList(), events.await())
            }
        }

    @Test
    fun flowsOnDisposedContextComplete() =
        runTest {
            withRoot { app ->
                app.dispose()

                app.serviceFlow(ValueKey).toList()
                assertEquals(emptyList(), app.events<FlowPing>().toList())
            }
        }
}
