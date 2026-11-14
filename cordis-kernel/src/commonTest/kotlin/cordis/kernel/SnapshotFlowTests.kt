package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

object FlowServiceKey : ServiceKey<Int>

object FlowListKey : ServiceKey<MutableList<Int>>

object FlowWaiting : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(FlowServiceKey))

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

class SnapshotFlowTests {
    @Test
    fun valueMatchesSnapshotAfterEachStep() =
        runTest {
            val app = Context.root()
            var previous = app.snapshotFlow().value
            assertEquals(app.snapshot(), previous)

            fun step() {
                val current = app.snapshotFlow().value
                assertEquals(app.snapshot(), current)
                assertNotEquals(previous, current)
                previous = current
            }

            val waiting = app.plugin(FlowWaiting)
            step()
            assertEquals(Fork.Status.Pending, previous.plugins.single().status)

            val provider = app.plugin("provider") { ctx -> ctx.provide(FlowServiceKey, 1) }
            step()
            assertEquals(Fork.Status.Active, previous.plugins.first { it.id == waiting.fork.id }.status)

            provider.dispose()
            step()
            assertEquals(Fork.Status.Pending, previous.plugins.first { it.id == waiting.fork.id }.status)

            waiting.dispose()
            step()
            assertTrue(previous.plugins.none { it.id == waiting.fork.id })

            val failing = app.plugin("failing") { throw BoomException() }
            step()
            assertTrue(previous.plugins.first { it.id == failing.fork.id }.status is Fork.Status.Failed)
        }

    @Test
    fun valueShowsNewStatusInsideStatusListener() =
        runTest {
            val app = Context.root()
            val seen = mutableListOf<Pair<Fork.Status, Fork.Status?>>()
            app.on<PluginStatusChanged> { event ->
                val info =
                    app
                        .snapshotFlow()
                        .value.plugins
                        .firstOrNull { it.name == event.plugin }
                seen.add(event.status to info?.status)
            }

            app.plugin(FlowWaiting)
            app.provide(FlowServiceKey, 1)

            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { (status, published) -> status == published })
        }

    @Test
    fun inPlaceMutationsDoNotRepublish() =
        runTest {
            val app = Context.root()
            app.provide(FlowServiceKey, 1)
            app.provide(FlowListKey, mutableListOf())

            val beforeReplace = app.snapshotFlow().value
            app.replaceValue(FlowServiceKey) { it + 1 }
            assertEquals(beforeReplace, app.snapshotFlow().value)

            val beforeUpdate = app.snapshotFlow().value
            app.update(FlowListKey) { it.add(1) }
            assertEquals(beforeUpdate, app.snapshotFlow().value)
        }

    @Test
    fun everyContextSharesOneFlowAcrossZones() =
        runTest {
            val app = Context.root()
            var pluginFlow: Any? = null
            app.plugin("inner") { ctx -> pluginFlow = ctx.snapshotFlow() }

            val flow = app.snapshotFlow()
            assertSame(flow, app.zone("z").snapshotFlow())
            assertSame(flow, app.isolate(FlowServiceKey, "t").snapshotFlow())
            assertSame(flow, pluginFlow)

            val zoned = app.zone("z").plugin("zoned") { }
            assertTrue(flow.value.plugins.any { it.id == zoned.fork.id })
        }
}
