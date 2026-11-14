package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BadConfigException : Exception("bad")

class EqualPlugin(
    private val log: MutableList<String>,
) : Plugin<String> {
    override suspend fun apply(
        ctx: Context,
        config: String,
    ) {
        ctx.onDispose { log.add("stop:$config") }
        log.add("start:$config")
    }
}

class FailsOnBadPlugin(
    private val log: MutableList<String>,
) : Plugin<String> {
    override suspend fun apply(
        ctx: Context,
        config: String,
    ) {
        log.add("apply:$config")
        if (config == "bad") throw BadConfigException()
    }
}

class OpaqueConfig

class OpaquePlugin(
    private val log: MutableList<String>,
) : Plugin<OpaqueConfig> {
    override suspend fun apply(
        ctx: Context,
        config: OpaqueConfig,
    ) {
        log.add("apply")
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

class UpdateSemanticsTests {
    @Test
    fun equalConfigOnActivePluginIsNoOp() =
        runTest {
            val log = mutableListOf<String>()
            val app = Context.root()
            val handle = app.plugin(EqualPlugin(log), "a")
            log.clear()

            val outcome = handle.update("a")
            assertEquals(UpdateOutcome.Unchanged, outcome)
            assertTrue(log.isEmpty())
            assertEquals(Fork.Status.Active, handle.status)
            app.dispose()
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
            app.dispose()
        }

    @Test
    fun differentConfigStillRestarts() =
        runTest {
            val log = mutableListOf<String>()
            val app = Context.root()
            val handle = app.plugin(EqualPlugin(log), "a")
            log.clear()

            val outcome = handle.update("b")
            assertEquals(UpdateOutcome.Applied, outcome)
            assertEquals(listOf("stop:a", "start:b"), log)
            app.dispose()
        }

    @Test
    fun retryingSameConfigAfterFailedApplyReRunsApply() =
        runTest {
            val log = mutableListOf<String>()
            val app = Context.root()
            val handle = app.plugin(FailsOnBadPlugin(log), "ok")
            handle.update("bad")
            log.clear()

            handle.update("bad")
            assertEquals(listOf("apply:bad"), log)
            app.dispose()
        }

    @Test
    fun nonEquatableConfigStillRestarts() =
        runTest {
            val log = mutableListOf<String>()
            val app = Context.root()
            val handle = app.plugin(OpaquePlugin(log), OpaqueConfig())
            log.clear()

            val outcome = handle.update(OpaqueConfig())
            assertEquals(UpdateOutcome.Applied, outcome)
            assertEquals(listOf("apply"), log)
            app.dispose()
        }
}
