package cordis.kernel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

object DrawerItems : ContributionKey<String>

object OtherItems : ContributionKey<String>

class ContributionTests {
    @Test
    fun initialValueIsEmpty() =
        runTest {
            val app = Context.root()
            assertEquals(emptyList(), app.contributions(DrawerItems).value)
            app.dispose()
        }

    @Test
    fun pluginContributionFollowsEnableAndDispose() =
        runTest {
            val app = Context.root()
            val handle = app.plugin("drawer") { ctx -> ctx.contribute(DrawerItems, "a") }
            handle.awaitActive()
            assertEquals(listOf("a"), app.contributions(DrawerItems).value)

            handle.dispose()
            assertEquals(emptyList(), app.contributions(DrawerItems).value)
            app.dispose()
        }

    @Test
    fun handleRemovesOnlyOnce() =
        runTest {
            val app = Context.root()
            val d = app.contribute(DrawerItems, "a")
            assertEquals(listOf("a"), app.contributions(DrawerItems).value)

            d()
            assertEquals(emptyList(), app.contributions(DrawerItems).value)
            assertTrue(d.isDisposed)

            app.contribute(DrawerItems, "b")
            d()
            assertEquals(listOf("b"), app.contributions(DrawerItems).value)
            app.dispose()
        }

    @Test
    fun parentUnloadRemovesChildContribution() =
        runTest {
            val app = Context.root()
            app.contribute(DrawerItems, "root")
            val parent =
                app.plugin("parent") { ctx ->
                    ctx.plugin("child") { child -> child.contribute(DrawerItems, "child") }
                }
            parent.awaitActive()
            assertEquals(listOf("root", "child"), app.contributions(DrawerItems).value)

            parent.dispose()
            assertEquals(listOf("root"), app.contributions(DrawerItems).value)
            app.dispose()
        }

    @Test
    fun defaultOrderIsRegistrationOrder() =
        runTest {
            val app = Context.root()
            app.contribute(DrawerItems, "a")
            app.contribute(DrawerItems, "b")
            app.contribute(DrawerItems, "c")
            assertEquals(listOf("a", "b", "c"), app.contributions(DrawerItems).value)
            app.dispose()
        }

    @Test
    fun explicitOrderBreaksTiesByRegistrationAndStaysStable() =
        runTest {
            val app = Context.root()
            app.contribute(DrawerItems, "x", order = 10)
            app.contribute(DrawerItems, "y", order = -1)
            app.contribute(DrawerItems, "z", order = 10)
            val w = app.contribute(DrawerItems, "w", order = 0)
            assertEquals(listOf("y", "w", "x", "z"), app.contributions(DrawerItems).value)

            w()
            assertEquals(listOf("y", "x", "z"), app.contributions(DrawerItems).value)
            app.dispose()
        }

    @Test
    fun sameItemTwiceIsTwoEntries() =
        runTest {
            val app = Context.root()
            val first = app.contribute(DrawerItems, "a")
            app.contribute(DrawerItems, "a")
            assertEquals(listOf("a", "a"), app.contributions(DrawerItems).value)

            first()
            assertEquals(listOf("a"), app.contributions(DrawerItems).value)
            app.dispose()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun flowEmitsOnEachChange() =
        runTest {
            val app = Context.root()
            val seen = mutableListOf<List<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                app.contributions(DrawerItems).toList(seen)
            }

            val a = app.contribute(DrawerItems, "a")
            app.contribute(DrawerItems, "b")
            a()

            assertEquals(listOf(emptyList(), listOf("a"), listOf("a", "b"), listOf("b")), seen)
            app.dispose()
        }

    @Test
    fun contributeOnDisposedContextThrows() =
        runTest {
            val app = Context.root()
            var pluginContext: Context? = null
            val handle = app.plugin("p") { ctx -> pluginContext = ctx }
            handle.dispose()

            val ctx = checkNotNull(pluginContext)
            assertFailsWith<CordisException.Disposed> { ctx.contribute(DrawerItems, "a") }
            assertEquals(emptyList(), app.contributions(DrawerItems).value)
            app.dispose()
        }

    @Test
    fun everyContextSharesOneFlowPerKey() =
        runTest {
            val app = Context.root()
            var pluginContext: Context? = null
            app.plugin("p") { ctx -> pluginContext = ctx }
            val ctx = checkNotNull(pluginContext)

            val flow = app.contributions(DrawerItems)
            assertSame(flow, ctx.contributions(DrawerItems))
            assertSame(flow, app.zone("z").contributions(DrawerItems))
            assertSame(flow, app.isolate(FlowServiceKey, "t").contributions(DrawerItems))
            assertSame(flow, app.intercept(FlowServiceKey) { it }.contributions(DrawerItems))

            ctx.contribute(DrawerItems, "from-plugin")
            app.contribute(DrawerItems, "from-root")
            assertEquals(listOf("from-plugin", "from-root"), app.contributions(DrawerItems).value)
            assertEquals(listOf("from-plugin", "from-root"), ctx.contributions(DrawerItems).value)
            assertEquals(emptyList(), ctx.contributions(OtherItems).value)
            app.dispose()
        }

    @Test
    fun contributionsCauseNoEventsOrSnapshotChanges() =
        runTest {
            val app = Context.root()
            app.plugin("p") { }
            var statusChanges = 0
            var serviceChanges = 0
            app.on<PluginStatusChanged> { statusChanges += 1 }
            app.on<ServiceChanged> { serviceChanges += 1 }
            val before = app.snapshotFlow().value

            val handle = app.contribute(DrawerItems, "a")
            handle()

            assertEquals(0, statusChanges)
            assertEquals(0, serviceChanges)
            assertSame(before, app.snapshotFlow().value)
            app.dispose()
        }
}
