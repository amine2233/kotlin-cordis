package cordis.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

data class StressPing(
    val n: Int,
) : CordisEvent

object StressPlugin : Plugin<Int> {
    override suspend fun apply(
        ctx: Context,
        config: Int,
    ) {
        ctx.on<StressPing> {}
    }
}

private const val WORKERS = 100

class ConcurrencyStressTests {
    @Test
    fun concurrentUpdateAndDisposeOnHandles() =
        runTest {
            withRoot { app ->
                val handles = List(10) { app.plugin(StressPlugin, it) }

                withContext(Dispatchers.Default) {
                    List(WORKERS) { i ->
                        async {
                            val handle = handles[i % 10]
                            if (i % 10 < 5 && i >= WORKERS / 2) handle.dispose() else handle.update(i)
                        }
                    }.awaitAll()
                }

                val disposed = handles.take(5)
                val alive = handles.drop(5)
                disposed.forEach { assertEquals(Fork.Status.Disposed, it.status) }
                alive.forEach { assertEquals(Fork.Status.Active, it.status) }
                assertEquals(alive.map { it.fork.id }, app.snapshot().plugins.map { it.id })
            }
        }

    @Test
    fun concurrentOnAndDisposeLeavesNoListener() =
        runTest {
            withRoot { app ->
                val baseline = app.registry.listenerCount(StressPing::class)

                withContext(Dispatchers.Default) {
                    List(WORKERS) { async { app.on<StressPing> {}() } }.awaitAll()
                }

                assertEquals(baseline, app.registry.listenerCount(StressPing::class))
            }
        }

    @Test
    fun concurrentOnLosesNoListener() =
        runTest {
            withRoot { app ->
                val baseline = app.registry.listenerCount(StressPing::class)

                withContext(Dispatchers.Default) {
                    List(WORKERS) { async { app.on<StressPing> {} } }.awaitAll()
                }

                assertEquals(baseline + WORKERS, app.registry.listenerCount(StressPing::class))
            }
        }

    @Test
    fun concurrentFlowCollectionsLeaveNoListener() =
        runTest {
            withRoot { app ->
                val eventBaseline = app.registry.listenerCount(StressPing::class)
                val serviceBaseline = app.registry.listenerCount(ServiceChanged::class)

                withContext(Dispatchers.Default) {
                    List(WORKERS) { i ->
                        async {
                            if (i % 2 == 0) {
                                app.serviceFlow(ValueKey).first()
                            } else {
                                val collector = launch { app.events<StressPing>().collect() }
                                yield()
                                collector.cancelAndJoin()
                            }
                        }
                    }.awaitAll()

                    awaitListenerCount(app, StressPing::class, eventBaseline)
                    awaitListenerCount(app, ServiceChanged::class, serviceBaseline)
                }
            }
        }

    @Test
    fun concurrentInvokeRunsActionOnce() =
        runTest {
            val lock = Lock()
            var runs = 0
            val disposable = Disposable({ lock.withLock { runs++ } })

            withContext(Dispatchers.Default) {
                List(WORKERS) { async { disposable() } }.awaitAll()
            }

            assertEquals(1, runs)
        }

    @Test
    fun concurrentCollectLosesNoTeardown() =
        runTest {
            val lock = Lock()
            var runs = 0
            val scope = Scope(coroutineScope = this)

            withContext(Dispatchers.Default) {
                List(WORKERS) { async { scope.collect { lock.withLock { runs++ } } } }.awaitAll()
            }
            scope.dispose()

            assertEquals(WORKERS, runs)
        }

    private suspend fun awaitListenerCount(
        app: Context,
        type: KClass<out CordisEvent>,
        expected: Int,
    ) {
        withTimeout(5_000) {
            while (app.registry.listenerCount(type) != expected) delay(1)
        }
    }
}
