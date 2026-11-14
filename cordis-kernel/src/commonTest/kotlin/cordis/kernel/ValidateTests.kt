package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeException : Exception("empty")

object ValidatedProvider : Plugin<Pair<Probe, String>> {
    override fun validate(config: Pair<Probe, String>) {
        if (config.second.isEmpty()) throw ShapeException()
    }

    override suspend fun apply(
        ctx: Context,
        config: Pair<Probe, String>,
    ) {
        val (probe, value) = config
        ctx.provide(ValueKey, value)
        probe.log.add("provide:$value")
        ctx.onDispose { probe.log.add("withdraw:$value") }
    }
}

object ValidatedConsumer : Plugin<Pair<Probe, String>> {
    override val inject = Inject.of(required = listOf(ValueKey))

    override fun validate(config: Pair<Probe, String>) {
        if (config.second.isEmpty()) throw ShapeException()
    }

    override suspend fun apply(
        ctx: Context,
        config: Pair<Probe, String>,
    ) {
        val (probe, label) = config
        probe.log.add("start:$label:${ctx.require(ValueKey)}")
    }
}

object ApplyFailsAfterValidate : Plugin<Boolean> {
    class Boom : Exception("Boom")

    override fun validate(config: Boolean) {}

    override suspend fun apply(
        ctx: Context,
        config: Boolean,
    ) {
        if (config) throw Boom()
    }
}

class ValidateTests {
    @Test
    fun rejectedConfigChangesNothing() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val provider = app.plugin(ValidatedProvider, Pair(probe, "A"))
                app.plugin(Consumer, probe)
                probe.log.clear()

                val outcome = provider.update(Pair(probe, ""))

                assertTrue(outcome is UpdateOutcome.Rejected)
                assertTrue(outcome.error is ShapeException)
                assertTrue(probe.log.isEmpty())
                assertEquals(Fork.Status.Active, provider.status)
                assertEquals("A", app[ValueKey])
            }
        }

    @Test
    fun acceptedConfigApplies() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val provider = app.plugin(ValidatedProvider, Pair(probe, "A"))

                val outcome = provider.update(Pair(probe, "B"))

                assertTrue(outcome is UpdateOutcome.Applied)
                assertEquals("B", app[ValueKey])
            }
        }

    @Test
    fun defaultValidateAccepts() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val provider = app.plugin(Provider, ProviderConfig(probe, "A"))

                val outcome = provider.update(ProviderConfig(probe, "B"))

                assertTrue(outcome is UpdateOutcome.Applied)
            }
        }

    @Test
    fun applyFailureAfterValidateStillFails() =
        runTest {
            withRoot { app ->
                val handle = app.plugin(ApplyFailsAfterValidate, false)

                val outcome = handle.update(true)

                assertTrue(outcome is UpdateOutcome.Applied)
                assertTrue(handle.status is Fork.Status.Failed)
            }
        }

    @Test
    fun invalidInitialConfigFailsWithoutApply() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val failures = mutableListOf<PluginFailed>()
                app.on<PluginFailed> { failures.add(it) }

                val handle = app.plugin(ValidatedProvider, Pair(probe, ""))

                assertEquals(Fork.Status.Failed("empty"), handle.status)
                assertEquals(1, failures.size)
                assertEquals(ValidatedProvider.name, failures.single().plugin)
                assertTrue(failures.single().error is ShapeException)
                assertTrue(probe.log.isEmpty())
                assertNull(app[ValueKey])
            }
        }

    @Test
    fun invalidInitialConfigFailsEvenWithMissingDependency() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val failures = mutableListOf<PluginFailed>()
                app.on<PluginFailed> { failures.add(it) }

                val handle = app.plugin(ValidatedConsumer, Pair(probe, ""))

                assertEquals(Fork.Status.Failed("empty"), handle.status)
                assertEquals(1, failures.size)
                assertEquals(ValidatedConsumer.name, failures.single().plugin)
                assertTrue(probe.log.isEmpty())
            }
        }

    @Test
    fun rejectedForkStaysFailedWhenDependencyAppears() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val failures = mutableListOf<PluginFailed>()
                app.on<PluginFailed> { failures.add(it) }
                val handle = app.plugin(ValidatedConsumer, Pair(probe, ""))

                val provider = app.plugin(Provider, ProviderConfig(Probe(), "A"))
                provider.update(ProviderConfig(Probe(), "B"))
                provider.dispose()

                assertEquals(Fork.Status.Failed("empty"), handle.status)
                assertEquals(1, failures.size)
                assertTrue(probe.log.isEmpty())
            }
        }

    @Test
    fun validUpdateRecoversRejectedFork() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                app.plugin(Provider, ProviderConfig(Probe(), "A"))
                val handle = app.plugin(ValidatedConsumer, Pair(probe, ""))

                val outcome = handle.update(Pair(probe, "ok"))

                assertEquals(UpdateOutcome.Applied, outcome)
                assertEquals(Fork.Status.Active, handle.status)
                assertEquals(listOf("start:ok:A"), probe.log)
            }
        }

    @Test
    fun validUpdateWithoutDependencyGoesPending() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val handle = app.plugin(ValidatedConsumer, Pair(probe, ""))

                val outcome = handle.update(Pair(probe, "ok"))

                assertEquals(UpdateOutcome.Applied, outcome)
                assertEquals(Fork.Status.Pending, handle.status)
                assertTrue(probe.log.isEmpty())
            }
        }

    @Test
    fun invalidUpdateKeepsRejectedForkFailed() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val handle = app.plugin(ValidatedProvider, Pair(probe, ""))

                val outcome = handle.update(Pair(probe, ""))

                assertTrue(outcome is UpdateOutcome.Rejected)
                assertEquals(Fork.Status.Failed("empty"), handle.status)
                assertTrue(probe.log.isEmpty())
            }
        }

    @Test
    fun disposeRejectedFork() =
        runTest {
            withRoot { app ->
                val handle = app.plugin(ValidatedProvider, Pair(Probe(), ""))

                handle.dispose()

                assertEquals(Fork.Status.Disposed, handle.status)
                assertTrue(app.snapshot().plugins.isEmpty())
            }
        }

    @Test
    fun disposingParentDisposesRejectedFork() =
        runTest {
            withRoot { app ->
                val handle = app.plugin(ValidatedProvider, Pair(Probe(), ""))

                app.dispose()

                assertEquals(Fork.Status.Disposed, handle.status)
            }
        }
}
