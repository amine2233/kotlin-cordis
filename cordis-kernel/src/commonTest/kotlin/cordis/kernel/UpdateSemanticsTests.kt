package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BadConfigException : Exception("bad")

object EqualPlugin : Plugin<String> {
    override suspend fun apply(
        ctx: Context,
        config: String,
    ) {
        ctx.onDispose { Recorder.log.add("stop:$config") }
        Recorder.log.add("start:$config")
    }
}

object FailsOnBadPlugin : Plugin<String> {
    override suspend fun apply(
        ctx: Context,
        config: String,
    ) {
        Recorder.log.add("apply:$config")
        if (config == "bad") throw BadConfigException()
    }
}

class OpaqueConfig

object OpaquePlugin : Plugin<OpaqueConfig> {
    override suspend fun apply(
        ctx: Context,
        config: OpaqueConfig,
    ) {
        Recorder.log.add("apply")
    }
}

object DependentPlugin : Plugin<Probe> {
    override val inject = Inject.of(required = listOf(ValueKey))

    override suspend fun apply(
        ctx: Context,
        config: Probe,
    ) {
        config.log.add("dependent")
    }
}

object ValueProviderPlugin : Plugin<String> {
    override suspend fun apply(
        ctx: Context,
        config: String,
    ) {
        ctx.provide(ValueKey, config)
    }
}

object Recorder {
    val log = mutableListOf<String>()
}

class UpdateSemanticsTests {
    @Test
    fun equalConfigOnActivePluginIsNoOp() =
        runTest {
            Recorder.log.clear()
            val app = Context.root()
            val handle = app.plugin(EqualPlugin, "a")
            Recorder.log.clear()

            val outcome = handle.update("a")
            assertEquals(UpdateOutcome.Unchanged, outcome)
            assertTrue(Recorder.log.isEmpty())
            assertEquals(Fork.Status.Active, handle.status)
        }

    @Test
    fun equalConfigDoesNotRestartDependents() =
        runTest {
            val app = Context.root()
            val probe = Probe()
            val provider = app.plugin(ValueProviderPlugin, "A")
            app.plugin(DependentPlugin, probe)
            probe.log.clear()

            val outcome = provider.update("A")
            assertEquals(UpdateOutcome.Unchanged, outcome)
            assertTrue(probe.log.isEmpty())

            provider.update("B")
            assertEquals(listOf("dependent"), probe.log)
        }

    @Test
    fun differentConfigStillRestarts() =
        runTest {
            Recorder.log.clear()
            val app = Context.root()
            val handle = app.plugin(EqualPlugin, "a")
            Recorder.log.clear()

            val outcome = handle.update("b")
            assertEquals(UpdateOutcome.Applied, outcome)
            assertEquals(listOf("stop:a", "start:b"), Recorder.log)
        }

    @Test
    fun retryingSameConfigAfterFailedApplyReRunsApply() =
        runTest {
            Recorder.log.clear()
            val app = Context.root()
            val handle = app.plugin(FailsOnBadPlugin, "ok")
            handle.update("bad")
            Recorder.log.clear()

            handle.update("bad")
            assertEquals(listOf("apply:bad"), Recorder.log)
        }

    @Test
    fun nonEquatableConfigStillRestarts() =
        runTest {
            Recorder.log.clear()
            val app = Context.root()
            val handle = app.plugin(OpaquePlugin, OpaqueConfig())
            Recorder.log.clear()

            val outcome = handle.update(OpaqueConfig())
            assertEquals(UpdateOutcome.Applied, outcome)
            assertEquals(listOf("apply"), Recorder.log)
        }
}
