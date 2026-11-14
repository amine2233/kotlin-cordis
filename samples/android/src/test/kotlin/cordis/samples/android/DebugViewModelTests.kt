package cordis.samples.android

import cordis.runtime.InMemoryEnabledPluginsStore
import cordis.runtime.PluginRuntimeDefault
import cordis.samples.shared.SampleKeys
import cordis.samples.shared.sampleCatalog
import cordis.testing.ManualClock
import cordis.testing.withTestApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModelTests {
    @Test
    fun followsTheSnapshotWhenDatabaseIsToggled() =
        runViewModelTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, sampleCatalog(ManualClock()), InMemoryEnabledPluginsStore())
                runtime.start()
                val viewModel = DebugViewModel(ctx.snapshotFlow())
                advanceUntilIdle()

                assertTrue(viewModel.state.value.healthy)
                assertTrue("NotesPlugin" in viewModel.state.value.tree)

                runtime.setEnabled(SampleKeys.database, false)
                advanceUntilIdle()

                val waiting = viewModel.state.value
                assertFalse(waiting.healthy)
                assertTrue("NotesPlugin: DatabaseKey" in waiting.missingServices)
                assertTrue("NotesPlugin" in waiting.pending)

                runtime.setEnabled(SampleKeys.database, true)
                advanceUntilIdle()

                assertTrue(viewModel.state.value.healthy)
            }
        }
}
