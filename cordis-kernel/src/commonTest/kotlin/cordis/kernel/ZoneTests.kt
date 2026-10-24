package cordis.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

data class Order(val id: String = "") : CordisEvent

object ZoneListener : Plugin<Pair<Probe, String>> {
    override suspend fun apply(ctx: Context, config: Pair<Probe, String>) {
        val (probe, name) = config
        ctx.on<Order> { probe.log.add(name) }
    }
}

class ZoneTests {
    private fun listen(ctx: Context, probe: Probe, name: String) {
        ctx.on<Order> { probe.log.add(name) }
    }

    @Test
    fun tenantsAreIsolated() = runTest {
        val root = Context.root()
        val probe = Probe()
        val a = root.zone("a")
        val b = root.zone("b")
        listen(a, probe, "a")
        listen(b, probe, "b")
        a.emit(Order())
        assertEquals(listOf("a"), probe.log)
    }

    @Test
    fun ancestorReceivesDescendantEvents() = runTest {
        val root = Context.root()
        val probe = Probe()
        val a = root.zone("a")
        val child = a.zone("x")
        listen(a, probe, "a")
        listen(child, probe, "x")
        child.emit(Order())
        a.emit(Order())
        assertEquals(listOf("a", "x", "a"), probe.log)
    }

    @Test
    fun sameLabelUnderSameParentIsSameZone() = runTest {
        val root = Context.root()
        val probe = Probe()
        listen(root.zone("a"), probe, "first")
        root.zone("a").emit(Order())
        assertEquals(listOf("first"), probe.log)
    }

    @Test
    fun sameLabelUnderDifferentParentsDiffers() = runTest {
        val root = Context.root()
        val probe = Probe()
        listen(root.zone("a").zone("x"), probe, "ax")
        root.zone("b").zone("x").emit(Order())
        assertTrue(probe.log.isEmpty())
    }

    @Test
    fun globalListenerSeesEverything() = runTest {
        val root = Context.root()
        val probe = Probe()
        listen(root, probe, "global")
        root.zone("a").emit(Order())
        root.emit(Order())
        assertEquals(listOf("global", "global"), probe.log)
    }

    @Test
    fun globalEmitReachesOnlyGlobalListeners() = runTest {
        val root = Context.root()
        val probe = Probe()
        listen(root, probe, "global")
        listen(root.zone("a"), probe, "a")
        root.emit(Order())
        assertEquals(listOf("global"), probe.log)
    }

    @Test
    fun zoneIsInheritedThroughIsolate() = runTest {
        val root = Context.root()
        val probe = Probe()
        val a = root.zone("a")
        val isolated = a.isolate(ValueKey, label = "t")
        listen(isolated, probe, "iso")
        a.emit(Order())
        root.zone("b").emit(Order())
        assertEquals(listOf("iso"), probe.log)
    }

    @Test
    fun zoneIsInheritedByPlugins() = runTest {
        val root = Context.root()
        val probe = Probe()
        val a = root.zone("a")
        a.plugin(ZoneListener, Pair(probe, "plugin"))
        a.emit(Order())
        root.zone("b").emit(Order())
        assertEquals(listOf("plugin"), probe.log)
    }

    @Test
    fun disposeRemovesZonedListener() = runTest {
        val root = Context.root()
        val probe = Probe()
        val a = root.zone("a")
        listen(a, probe, "a")
        a.scope.dispose()
        a.emit(Order())
        assertTrue(probe.log.isEmpty())
        assertEquals(0, root.registry.listenerCount(Order::class))
    }
}
