package cordis.kernel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

data class IsolationBoom(
    val id: Int,
) : Exception("Boom $id")

data class IsolationPing(
    val n: Int,
) : CordisEvent

class FailureIsolationTests {
    @Test
    fun throwingListenerDoesNotStopOthersOrReachEmitter() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val failures = mutableListOf<ListenerFailed>()
                app.on<ListenerFailed> { failures.add(it) }
                app.on<IsolationPing> { throw IsolationBoom(it.n) }
                app.on<IsolationPing> { probe.log.add("ping:${it.n}") }
                app.on<ServiceChanged> { throw IsolationBoom(0) }
                app.on<ServiceChanged> { probe.log.add("changed:${it.isAvailable}") }

                app.emit(IsolationPing(1))
                app.provide(ValueKey, "x")

                assertEquals(listOf("ping:1", "changed:true"), probe.log)
                val changed = ServiceChanged(slot = app.slot(ValueKey.serviceId), isAvailable = true)
                assertEquals(
                    listOf(
                        ListenerFailed(event = IsolationPing(1), error = IsolationBoom(1)),
                        ListenerFailed(event = changed, error = IsolationBoom(0)),
                    ),
                    failures,
                )
            }
        }

    @Test
    fun throwingStatusListenerDoesNotWedgeReconciliation() =
        runTest {
            withRoot { app ->
                val statuses = mutableListOf<Fork.Status>()
                app.on<PluginStatusChanged> { throw IsolationBoom(1) }
                app.on<PluginStatusChanged> { if (it.plugin == NeedsValue.name) statuses.add(it.status) }

                val dependent = app.plugin(NeedsValue)
                val provider = app.plugin("provider") { it.provide(ValueKey, "x") }
                assertEquals(Fork.Status.Active, dependent.awaitActive())

                provider.dispose()
                assertEquals(Fork.Status.Pending, dependent.status)

                app.provide(ValueKey, "y")
                assertEquals(Fork.Status.Active, dependent.awaitActive())
                assertEquals(
                    listOf(Fork.Status.Loading, Fork.Status.Active, Fork.Status.Pending, Fork.Status.Loading, Fork.Status.Active),
                    statuses,
                )
            }
        }

    @Test
    fun throwingListenerFailedListenerIsNotReportedAgain() =
        runTest {
            withRoot { app ->
                var reported = 0
                app.on<IsolationPing> { throw IsolationBoom(it.n) }
                app.on<ListenerFailed> { throw IsolationBoom(-1) }
                app.on<ListenerFailed> { reported += 1 }

                app.emit(IsolationPing(1))

                assertEquals(1, reported)
            }
        }

    @Test
    fun throwingLaunchInPluginReportsTaskFailed() =
        runTest {
            withRoot { app ->
                val failed = CompletableDeferred<TaskFailed>()
                app.on<TaskFailed> { failed.complete(it) }

                val dependent = app.plugin(NeedsValue)
                val worker =
                    app.plugin("worker") { ctx ->
                        ctx.provide(ValueKey, "x")
                        ctx.launch { throw IsolationBoom(1) }
                    }

                assertEquals(TaskFailed(plugin = "worker", error = IsolationBoom(1)), failed.await())
                assertEquals(Fork.Status.Active, worker.status)
                assertEquals(Fork.Status.Active, dependent.awaitActive())

                worker.dispose()
                assertEquals(Fork.Status.Pending, dependent.status)
                app.provide(ValueKey, "y")
                assertEquals(Fork.Status.Active, dependent.awaitActive())
            }
        }

    @Test
    fun throwingLaunchOnRootReportsRoot() =
        runTest {
            withRoot { app ->
                val failed = CompletableDeferred<TaskFailed>()
                app.on<TaskFailed> { failed.complete(it) }

                app.launch { throw IsolationBoom(2) }

                assertEquals(TaskFailed(plugin = "root", error = IsolationBoom(2)), failed.await())
            }
        }
}
