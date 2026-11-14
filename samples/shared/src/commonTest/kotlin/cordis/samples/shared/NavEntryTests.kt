package cordis.samples.shared

import cordis.kernel.Context
import cordis.kernel.PluginHandle
import cordis.kernel.PluginKey
import cordis.testing.ManualClock
import cordis.testing.withTestApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NavEntryTests {
    @Test
    fun loggerContributesItsEntryWhileActive() =
        runTest {
            assertEntryWhileActive(SampleKeys.logger, "Logs") { it.plugin(LoggerPlugin) }
        }

    @Test
    fun databaseContributesItsEntryWhileActive() =
        runTest {
            assertEntryWhileActive(SampleKeys.database, "Database") { it.plugin(DatabasePlugin) }
        }

    @Test
    fun notesContributesItsEntryWhileActive() =
        runTest {
            assertEntryWhileActive(SampleKeys.notes, "Notes", dependency = SampleKeys.database) {
                it.plugin(DatabasePlugin)
                it.plugin(NotesPlugin)
            }
        }

    @Test
    fun weatherContributesItsEntryWhileActive() =
        runTest {
            assertEntryWhileActive(SampleKeys.weather, "Weather") {
                it.plugin(WeatherPlugin, WeatherConfig(clock = ManualClock()))
            }
        }

    @Test
    fun analyticsContributesItsEntryWhileActive() =
        runTest {
            assertEntryWhileActive(SampleKeys.analytics, "Analytics", dependency = SampleKeys.logger) {
                it.plugin(LoggerPlugin)
                it.plugin(AnalyticsPlugin)
            }
        }

    /** Loads through [load], which returns the handle of the plugin under test after loading its [dependency]. */
    private suspend fun TestScope.assertEntryWhileActive(
        key: PluginKey,
        title: String,
        dependency: PluginKey? = null,
        load: suspend (Context) -> PluginHandle<*>,
    ) {
        withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
            val handle = load(ctx)
            advanceUntilIdle()
            val entry = NavEntry(id = key.value, title = title)
            assertEquals(entry, ctx.contributions(NavEntries).value.single { it.id == key.value })

            handle.dispose()
            advanceUntilIdle()
            assertEquals(listOfNotNull(dependency?.value), ctx.contributions(NavEntries).value.map { it.id })
        }
    }
}
