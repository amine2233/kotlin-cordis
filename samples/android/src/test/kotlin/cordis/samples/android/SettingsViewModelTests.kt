package cordis.samples.android

import cordis.kernel.PluginKey
import cordis.runtime.InMemoryEnabledPluginsStore
import cordis.runtime.PluginRuntimeDefault
import cordis.samples.shared.DatabaseKey
import cordis.samples.shared.SampleKeys
import cordis.samples.shared.sampleCatalog
import cordis.testing.ManualClock
import cordis.testing.withTestApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTests {
    @Test
    fun rowsFollowTheRuntimeInCatalogOrder() =
        runViewModelTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, sampleCatalog(ManualClock()), InMemoryEnabledPluginsStore())
                runtime.start()
                val viewModel = SettingsViewModel(runtime)
                advanceUntilIdle()

                val rows = viewModel.state.value.rows
                assertEquals(
                    listOf(SampleKeys.logger, SampleKeys.database, SampleKeys.notes, SampleKeys.weather, SampleKeys.analytics),
                    rows.map { it.key },
                )
                assertTrue(rows.all { it.status == PluginStatus.Active && it.enabled })
                assertEquals(listOf(true, false, false, false, false), rows.map { it.locked })
            }
        }

    @Test
    fun togglingDatabaseOffLeavesNotesWaitingForIt() =
        runViewModelTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, sampleCatalog(ManualClock()), InMemoryEnabledPluginsStore())
                runtime.start()
                val viewModel = SettingsViewModel(runtime)
                advanceUntilIdle()

                viewModel.dispatch(SettingsAction.Toggle(SampleKeys.database))
                advanceUntilIdle()

                val database = viewModel.state.value.row(SampleKeys.database)
                assertEquals(false, database.enabled)
                assertEquals(PluginStatus.Disabled, database.status)
                val notes = viewModel.state.value.row(SampleKeys.notes)
                assertEquals(PluginStatus.Waiting(listOf(DatabaseKey.serviceId.description)), notes.status)
                assertEquals("Waiting for DatabaseKey", notes.status.label)
            }
        }

    @Test
    fun togglingTheLockedLoggerLeavesItEnabled() =
        runViewModelTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, sampleCatalog(ManualClock()), InMemoryEnabledPluginsStore())
                runtime.start()
                val viewModel = SettingsViewModel(runtime)
                advanceUntilIdle()

                viewModel.dispatch(SettingsAction.Toggle(SampleKeys.logger))
                advanceUntilIdle()

                val logger = viewModel.state.value.row(SampleKeys.logger)
                assertEquals(true, logger.enabled)
                assertEquals(PluginStatus.Active, logger.status)
            }
        }

    @Test
    fun aRejectedToggleIsSwallowed() =
        runViewModelTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, sampleCatalog(ManualClock()), InMemoryEnabledPluginsStore())
                val viewModel = SettingsViewModel(runtime)
                advanceUntilIdle()

                // The runtime is not started, so setEnabled throws PluginRuntimeException.NotStarted.
                viewModel.dispatch(SettingsAction.Toggle(SampleKeys.database))
                advanceUntilIdle()

                assertEquals(
                    true,
                    viewModel.state.value
                        .row(SampleKeys.database)
                        .enabled,
                )
            }
        }

    private fun SettingsState.row(key: PluginKey): SettingsRow = rows.single { it.key == key }
}
