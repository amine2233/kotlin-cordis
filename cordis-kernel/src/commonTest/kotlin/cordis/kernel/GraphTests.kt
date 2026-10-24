package cordis.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

object ValueKey : ServiceKey<String>

data class Ping(val message: String = "") : CordisEvent

data class ProviderConfig(val probe: Probe, val value: String)

object Provider : Plugin<ProviderConfig> {
    override suspend fun apply(ctx: Context, config: ProviderConfig) {
        val (probe, value) = config
        ctx.provide(ValueKey, value)
        probe.log.add("provide:$value")
        ctx.onDispose { probe.log.add("withdraw:$value") }
    }
}

object Consumer : Plugin<Probe> {
    override val inject = Inject.of(required = listOf(ValueKey))

    override suspend fun apply(ctx: Context, config: Probe) {
        val value = ctx.require(ValueKey)
        config.log.add("start:$value")
        ctx.on<Ping> { config.log.add("ping:$value") }
        ctx.onDispose { config.log.add("stop:$value") }
    }
}

class BoomException : Exception("Boom")

object Failing : Plugin<Probe> {
    override suspend fun apply(ctx: Context, config: Probe) {
        ctx.onDispose { config.log.add("rollback") }
        throw BoomException()
    }
}

class GraphTests {
    @Test
    fun consumerWaitsForRequiredService() = runTest {
        val app = Context.root()
        val probe = Probe()
        val consumer = app.plugin(Consumer, probe)
        assertEquals(Fork.Status.Pending, consumer.status)

        app.plugin(Provider, ProviderConfig(probe, "A"))
        assertEquals(Fork.Status.Active, consumer.status)
        assertEquals(listOf("start:A", "provide:A"), probe.log)
    }

    @Test
    fun hotSwapRestartsDependents() = runTest {
        val app = Context.root()
        val probe = Probe()
        val provider = app.plugin(Provider, ProviderConfig(probe, "A"))
        app.plugin(Consumer, probe)
        probe.log.clear()

        provider.update(ProviderConfig(probe, "B"))
        assertEquals(listOf("withdraw:A", "stop:A", "start:B", "provide:B"), probe.log)
    }

    @Test
    fun unloadRollsBackListeners() = runTest {
        val app = Context.root()
        val probe = Probe()
        val provider = app.plugin(Provider, ProviderConfig(probe, "A"))
        val consumer = app.plugin(Consumer, probe)

        app.emit(Ping())
        provider.dispose()
        app.emit(Ping())

        assertEquals(Fork.Status.Pending, consumer.status)
        assertEquals(listOf("ping:A"), probe.log.filter { it.startsWith("ping") })
    }

    @Test
    fun isolationGivesEachRealmItsOwnInstance() = runTest {
        val app = Context.root()
        val probe = Probe()
        val tenantA = app.isolate(ValueKey, label = "a")
        val tenantB = app.isolate(ValueKey, label = "b")

        tenantA.plugin(Provider, ProviderConfig(probe, "A"))
        val consumerB = tenantB.plugin(Consumer, probe)
        assertEquals(Fork.Status.Pending, consumerB.status) // A's value is invisible to B

        tenantB.plugin(Provider, ProviderConfig(probe, "B"))
        assertEquals(Fork.Status.Active, consumerB.status)
        assertEquals("A", tenantA[ValueKey])
        assertEquals("B", tenantB[ValueKey])
        assertNull(app[ValueKey])
    }

    @Test
    fun conflictingProvidersAreRejected() = runTest {
        val app = Context.root()
        val probe = Probe()
        app.plugin(Provider, ProviderConfig(probe, "A"))
        val second = app.plugin(Provider, ProviderConfig(probe, "B"))

        assertTrue(second.status is Fork.Status.Failed)
        assertEquals("A", app[ValueKey])
    }

    @Test
    fun failingPluginIsRolledBack() = runTest {
        val app = Context.root()
        val probe = Probe()
        val reported = Probe()
        app.on<PluginFailed> { reported.log.add(it.plugin) }

        val handle = app.plugin(Failing, probe)
        assertTrue(handle.status is Fork.Status.Failed)

        assertEquals(listOf("rollback"), probe.log)
        assertEquals(listOf(Failing.name), reported.log)
    }

    @Test
    fun disposingRootTearsDownEverythingInReverse() = runTest {
        val app = Context.root()
        val probe = Probe()
        app.plugin(Provider, ProviderConfig(probe, "A"))
        app.plugin(Consumer, probe)
        probe.log.clear()

        app.dispose()
        assertEquals(listOf("stop:A", "withdraw:A"), probe.log)
    }
}
