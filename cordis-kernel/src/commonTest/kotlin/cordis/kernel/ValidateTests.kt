package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
            val app = Context.root()
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

    @Test
    fun acceptedConfigApplies() =
        runTest {
            val app = Context.root()
            val probe = Probe()
            val provider = app.plugin(ValidatedProvider, Pair(probe, "A"))

            val outcome = provider.update(Pair(probe, "B"))

            assertTrue(outcome is UpdateOutcome.Applied)
            assertEquals("B", app[ValueKey])
        }

    @Test
    fun defaultValidateAccepts() =
        runTest {
            val app = Context.root()
            val probe = Probe()
            val provider = app.plugin(Provider, ProviderConfig(probe, "A"))

            val outcome = provider.update(ProviderConfig(probe, "B"))

            assertTrue(outcome is UpdateOutcome.Applied)
        }

    @Test
    fun applyFailureAfterValidateStillFails() =
        runTest {
            val app = Context.root()
            val handle = app.plugin(ApplyFailsAfterValidate, false)

            val outcome = handle.update(true)

            assertTrue(outcome is UpdateOutcome.Applied)
            assertTrue(handle.status is Fork.Status.Failed)
        }
}
