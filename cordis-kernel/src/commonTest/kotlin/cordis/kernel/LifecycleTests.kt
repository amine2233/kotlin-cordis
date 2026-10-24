package cordis.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

object MidKey : ServiceKey<Int>
object OtherKey : ServiceKey<String>

object Mid : Plugin<Probe> {
    override val inject = Inject.of(required = listOf(ValueKey))

    override suspend fun apply(ctx: Context, config: Probe) {
        val value = ctx.require(ValueKey)
        config.log.add("mid:$value")
        ctx.provide(MidKey, value.length)
    }
}

object Leaf : Plugin<Probe> {
    override val inject = Inject.of(required = listOf(MidKey))

    override suspend fun apply(ctx: Context, config: Probe) {
        val count = ctx.require(MidKey)
        config.log.add("leaf:$count")
        ctx.onDispose { config.log.add("leaf-stop") }
    }
}

object OptionalConsumer : Plugin<Probe> {
    override val inject = Inject.of(optional = listOf(ValueKey))

    override suspend fun apply(ctx: Context, config: Probe) {
        val value = ctx[ValueKey] ?: "none"
        config.log.add("start:$value")
    }
}

object Parent : Plugin<Probe> {
    override suspend fun apply(ctx: Context, config: Probe) {
        ctx.plugin(Consumer, config)
        ctx.onDispose { config.log.add("parent-stop") }
    }
}

object Effecting : Plugin<Probe> {
    override suspend fun apply(ctx: Context, config: Probe) {
        ctx.effect {
            config.log.add("setup")
            return@effect { config.log.add("teardown") }
        }
    }
}

class LifecycleTests {
    @Test
    fun optionalDependencyRestartsWhenItAppearsAndVanishes() = runTest {
        val app = Context.root()
        val probe = Probe()
        val consumer = app.plugin(OptionalConsumer, probe)
        assertEquals(Fork.Status.Active, consumer.status)

        val provider = app.plugin(Provider, ProviderConfig(probe, "A"))
        provider.dispose()

        assertEquals(
            listOf("start:none", "start:A", "start:none"),
            probe.log.filter { it.startsWith("start") }
        )
    }

    @Test
    fun cascadeGoesPendingAndComesBack() = runTest {
        val app = Context.root()
        val probe = Probe()
        val leaf = app.plugin(Leaf, probe)
        val mid = app.plugin(Mid, probe)
        val provider = app.plugin(Provider, ProviderConfig(probe, "AB"))

        assertEquals(Fork.Status.Active, mid.status)
        assertEquals(Fork.Status.Active, leaf.status)

        provider.dispose()
        assertEquals(Fork.Status.Pending, mid.status)
        assertEquals(Fork.Status.Pending, leaf.status)

        app.plugin(Provider, ProviderConfig(probe, "ABC"))
        assertEquals(Fork.Status.Active, leaf.status)
        assertTrue(probe.log.contains("leaf:3"))
    }

    @Test
    fun disposingParentDisposesSubtree() = runTest {
        val app = Context.root()
        val probe = Probe()
        app.plugin(Provider, ProviderConfig(probe, "A"))
        val parent = app.plugin(Parent, probe)
        probe.log.clear()

        parent.dispose()
        assertTrue(probe.log.contains("parent-stop"))
        assertEquals(0, app.registry.listenerCount(Ping::class))
        assertEquals(Fork.Status.Disposed, parent.status)
    }

    @Test
    fun updatingPendingPluginDoesNotStartIt() = runTest {
        val app = Context.root()
        val probe = Probe()
        val consumer = app.plugin(Consumer, probe)
        consumer.update(probe)
        assertEquals(Fork.Status.Pending, consumer.status)
        assertTrue(probe.log.isEmpty())
    }

    @Test
    fun unrelatedSlotChangeDoesNotRestart() = runTest {
        val app = Context.root()
        val probe = Probe()
        app.plugin(Provider, ProviderConfig(probe, "A"))
        app.plugin(Consumer, probe)
        probe.log.clear()

        app.provide(OtherKey, "x")
        assertTrue(probe.log.isEmpty())
    }

    @Test
    fun effectTeardownRunsExactlyOnce() = runTest {
        val app = Context.root()
        val probe = Probe()
        val handle = app.plugin(Effecting, probe)
        handle.dispose()
        handle.dispose()
        app.dispose()
        assertEquals(listOf("setup", "teardown"), probe.log)
    }

    @Test
    fun listenerAddedDuringDispatchMissesCurrentEvent() = runTest {
        val app = Context.root()
        val counter = Probe()
        app.on<Ping> {
            app.on<Ping> { counter.log.add("late") }
        }
        app.emit(Ping())
        assertTrue(counter.log.isEmpty())
        app.emit(Ping())
        assertEquals(listOf("late"), counter.log)
    }

    @Test
    fun providingAfterDisposeThrows() = runTest {
        val app = Context.root()
        app.dispose()
        assertFailsWith<CordisException.Disposed> {
            app.provide(OtherKey, "x")
        }
    }

    @Test
    fun requireThrowsWhenMissing() {
        val app = Context.root()
        assertFailsWith<CordisException.MissingService> {
            app.require(OtherKey)
        }
    }
}
