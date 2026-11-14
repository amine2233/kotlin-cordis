package cordis.samples.shared

import cordis.kernel.Fork
import cordis.runtime.InMemoryEnabledPluginsStore
import cordis.runtime.PluginRuntimeDefault
import cordis.runtime.PluginRuntimeException
import cordis.testing.ManualClock
import cordis.testing.withTestApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SampleCatalogTests {
    private val allKeys =
        listOf(SampleKeys.logger, SampleKeys.database, SampleKeys.notes, SampleKeys.weather, SampleKeys.analytics)

    @Test
    fun catalogListsTheFiveFeaturesInOrder() {
        val entries = sampleCatalog().entries
        assertEquals(allKeys, entries.map { it.key })
        assertEquals(listOf("logger", "database", "notes", "weather", "analytics"), entries.map { it.key.value })
        assertTrue(entries.all { it.metadata.title.isNotBlank() && it.metadata.description.isNotBlank() })
        assertTrue(entries.all { it.metadata.enabledByDefault })
    }

    @Test
    fun onlyTheLoggerIsLocked() {
        val locked = sampleCatalog().entries.filter { it.metadata.locked }.map { it.key }
        assertEquals(listOf(SampleKeys.logger), locked)
    }

    @Test
    fun runtimeStartsEveryFeatureAndRefusesToDisableTheLogger() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val clock = ManualClock()
                val runtime = PluginRuntimeDefault(ctx, sampleCatalog(clock), InMemoryEnabledPluginsStore())
                runtime.start()
                advanceUntilIdle()
                assertTrue(runtime.state.value.all { it.status == Fork.Status.Active })
                val expectedIds = allKeys.map { it.value }
                assertEquals(expectedIds, ctx.contributions(NavEntries).value.map { it.id })

                assertFailsWith<PluginRuntimeException.LockedPlugin> {
                    runtime.setEnabled(SampleKeys.logger, false)
                }
                advanceUntilIdle()
                assertEquals(expectedIds, ctx.contributions(NavEntries).value.map { it.id })
                assertEquals(1, clock.sleeperCount)
                runtime.stop()
            }
        }
}
