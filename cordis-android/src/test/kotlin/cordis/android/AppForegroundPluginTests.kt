package cordis.android

import androidx.lifecycle.Lifecycle
import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.Inject
import cordis.kernel.PluginHandle
import cordis.testing.withTestApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AppForegroundPluginTests {
    private suspend fun Context.dependent(): PluginHandle<Unit> =
        plugin("Dependent", inject = Inject.of(required = listOf(AppForegroundKey))) {
        }

    @Test
    fun dependentFollowsForegroundBackgroundForeground() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val owner = LifecycleOwnerMock(Lifecycle.State.RESUMED)
                ctx.installAppForeground(owner)
                val dependent = ctx.dependent()
                advanceUntilIdle()
                assertEquals(Fork.Status.Active, dependent.status)
                assertNotNull(ctx[AppForegroundKey])

                owner.moveTo(Lifecycle.State.CREATED)
                advanceUntilIdle()
                assertEquals(Fork.Status.Pending, dependent.status)
                assertNull(ctx[AppForegroundKey])

                owner.moveTo(Lifecycle.State.STARTED)
                advanceUntilIdle()
                assertEquals(Fork.Status.Active, dependent.status)
                assertNotNull(ctx[AppForegroundKey])
            }
        }

    @Test
    fun ownerAlreadyStartedProvidesTheServiceWithoutAnEvent() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                ctx.installAppForeground(LifecycleOwnerMock(Lifecycle.State.STARTED))
                advanceUntilIdle()
                assertEquals(AppForeground, ctx[AppForegroundKey])
            }
        }

    @Test
    fun ownerOnlyCreatedKeepsTheDependentPending() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                ctx.installAppForeground(LifecycleOwnerMock(Lifecycle.State.CREATED))
                val dependent = ctx.dependent()
                advanceUntilIdle()
                assertEquals(Fork.Status.Pending, dependent.status)
                assertNull(ctx[AppForegroundKey])
            }
        }

    @Test
    fun disposingTheHandleRemovesTheServiceAndIgnoresLaterTransitions() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val owner = LifecycleOwnerMock(Lifecycle.State.STARTED)
                val handle = ctx.installAppForeground(owner)
                val dependent = ctx.dependent()
                advanceUntilIdle()
                assertEquals(Fork.Status.Active, dependent.status)

                handle.dispose()
                advanceUntilIdle()
                assertNull(ctx[AppForegroundKey])
                assertEquals(Fork.Status.Pending, dependent.status)

                owner.moveTo(Lifecycle.State.CREATED)
                owner.moveTo(Lifecycle.State.RESUMED)
                advanceUntilIdle()
                assertNull(ctx[AppForegroundKey])
                assertEquals(Fork.Status.Pending, dependent.status)
            }
        }
}
