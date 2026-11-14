package cordis.samples.shared

import cordis.testing.ManualClock
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
class SamplePluginTests {
    @Test
    fun loggerRecordsLines() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                ctx.plugin(LoggerPlugin)
                advanceUntilIdle()
                val logger = assertNotNull(ctx[LoggerKey])
                logger.log("first")
                logger.log("second")
                assertEquals(listOf("first", "second"), logger.lines)
            }
        }

    @Test
    fun databaseStoresValuesByKey() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                ctx.plugin(DatabasePlugin)
                advanceUntilIdle()
                val database = assertNotNull(ctx[DatabaseKey])
                assertNull(database["a"])
                database.put("a", "1")
                database.put("a", "2")
                assertEquals("2", database["a"])
                database.remove("a")
                assertNull(database["a"])
            }
        }

    @Test
    fun notesAreStoredThroughTheDatabase() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                ctx.plugin(DatabasePlugin)
                ctx.plugin(NotesPlugin)
                advanceUntilIdle()
                val notes = assertNotNull(ctx[NotesKey])
                notes.add("buy milk")
                notes.add("call home")
                assertEquals(listOf("buy milk", "call home"), notes.list())
                val database = assertNotNull(ctx[DatabaseKey])
                assertEquals("buy milk", database["notes.0"])
                assertEquals(listOf("buy milk", "call home"), InMemoryNotes(database).list())
            }
        }

    @Test
    fun weatherProvidesAReading() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                ctx.plugin(WeatherPlugin, WeatherConfig(clock = ManualClock()))
                advanceUntilIdle()
                val weather = assertNotNull(ctx[WeatherKey])
                assertEquals(WeatherReading(update = 0, temperatureCelsius = 18), weather.reading.value)
            }
        }

    @Test
    fun analyticsTracksEventsThroughTheLogger() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                ctx.plugin(LoggerPlugin)
                ctx.plugin(AnalyticsPlugin)
                advanceUntilIdle()
                val analytics = assertNotNull(ctx[AnalyticsKey])
                analytics.track("opened")
                assertEquals(listOf("opened"), analytics.events)
                assertEquals(listOf("analytics: opened"), assertNotNull(ctx[LoggerKey]).lines)
            }
        }
}
