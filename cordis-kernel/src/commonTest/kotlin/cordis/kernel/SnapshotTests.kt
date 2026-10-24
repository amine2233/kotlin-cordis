package cordis.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

object SnapshotKey : ServiceKey<Int>
object SnapshotOtherKey : ServiceKey<Int>

object SnapshotProvider : Plugin<Unit> {
    override suspend fun apply(ctx: Context, config: Unit) {
        ctx.provide(SnapshotKey, 1)
    }
}

object SnapshotWaiting : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(SnapshotKey))
    override suspend fun apply(ctx: Context, config: Unit) {}
}

object SnapshotFailing : Plugin<Unit> {
    class Boom : Exception("SnapshotBoom")
    override suspend fun apply(ctx: Context, config: Unit) {
        throw Boom()
    }
}

class SnapshotTests {
    @Test
    fun emptyRootHasEmptySnapshot() {
        val snapshot = Context.root().snapshot()
        assertTrue(snapshot.plugins.isEmpty())
        assertTrue(snapshot.slots.isEmpty())
    }

    @Test
    fun reportsPendingActiveAndFailedStatuses() = runTest {
        val app = Context.root()
        app.plugin(SnapshotWaiting)
        app.plugin(SnapshotFailing)

        val snap1 = app.snapshot()
        assertEquals(Fork.Status.Pending, snap1.plugins[0].status)
        assertTrue(snap1.plugins[1].status is Fork.Status.Failed)

        app.plugin(SnapshotProvider)
        val snap2 = app.snapshot()
        assertEquals(Fork.Status.Active, snap2.plugins[0].status)
        assertTrue(snap2.plugins[1].status is Fork.Status.Failed)
        assertEquals(Fork.Status.Active, snap2.plugins[2].status)
    }

    @Test
    fun disposedPluginsAreNotListed() = runTest {
        val app = Context.root()
        val handle = app.plugin(SnapshotProvider)
        handle.dispose()
        assertEquals(Fork.Status.Disposed, handle.status)
        assertTrue(app.snapshot().plugins.isEmpty())
    }

    @Test
    fun slotAppearsAfterProvideAndDisappearsAfterDispose() = runTest {
        val app = Context.root()
        val handle = app.plugin(SnapshotProvider)
        val snapshot = app.snapshot()
        assertEquals(1, snapshot.slots.size)
        assertEquals(SnapshotKey.serviceId.description, snapshot.slots[0].service)
        assertEquals(Slot.SHARED_REALM, snapshot.slots[0].realm)
        assertEquals(snapshot.plugins[0].id, snapshot.slots[0].provider)

        handle.dispose()
        assertTrue(app.snapshot().slots.isEmpty())
    }

    @Test
    fun rootProvidedSlotHasNoProvider() = runTest {
        val app = Context.root()
        app.provide(SnapshotKey, 1)
        assertNull(app.snapshot().slots[0].provider)
    }

    @Test
    fun isolatedContextsShowTheirRealms() = runTest {
        val app = Context.root()
        app.provide(SnapshotKey, 1)
        app.isolate(SnapshotKey, label = "tenant").provide(SnapshotKey, 2)
        assertEquals(listOf(Slot.SHARED_REALM, "tenant"), app.snapshot().slots.map { it.realm })
    }
}
