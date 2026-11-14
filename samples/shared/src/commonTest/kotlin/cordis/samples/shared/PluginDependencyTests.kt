package cordis.samples.shared

import cordis.kernel.Fork
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
class PluginDependencyTests {
    @Test
    fun notesWaitsForTheDatabase() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val notes = ctx.plugin(NotesPlugin)
                advanceUntilIdle()
                assertEquals(Fork.Status.Pending, notes.status)
                assertNull(ctx[NotesKey])

                val database = ctx.plugin(DatabasePlugin)
                advanceUntilIdle()
                assertEquals(Fork.Status.Active, notes.status)
                assertNotNull(ctx[NotesKey])

                database.dispose()
                advanceUntilIdle()
                assertEquals(Fork.Status.Pending, notes.status)
                assertNull(ctx[NotesKey])
            }
        }

    @Test
    fun analyticsWaitsForTheLogger() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val analytics = ctx.plugin(AnalyticsPlugin)
                advanceUntilIdle()
                assertEquals(Fork.Status.Pending, analytics.status)
                assertNull(ctx[AnalyticsKey])

                val logger = ctx.plugin(LoggerPlugin)
                advanceUntilIdle()
                assertEquals(Fork.Status.Active, analytics.status)
                assertNotNull(ctx[AnalyticsKey])

                logger.dispose()
                advanceUntilIdle()
                assertEquals(Fork.Status.Pending, analytics.status)
                assertNull(ctx[AnalyticsKey])
            }
        }
}
