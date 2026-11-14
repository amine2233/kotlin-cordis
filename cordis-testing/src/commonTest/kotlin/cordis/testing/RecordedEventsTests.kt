package cordis.testing

import cordis.kernel.CordisEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class RecordedPing(
    val n: Int,
) : CordisEvent

private const val WORKERS = 100

class RecordedEventsTests {
    @Test
    fun readListDoesNotChangeWhenMoreEventsAreRecorded() =
        runTest {
            withTestApp { _, events ->
                events.record(RecordedPing(1))
                val read = events.all
                events.record(RecordedPing(2))

                assertEquals(listOf(RecordedPing(1)), read.filterIsInstance<RecordedPing>())
                assertEquals(listOf(RecordedPing(1), RecordedPing(2)), events.ofType<RecordedPing>())
            }
        }

    @Test
    fun concurrentRecordsAllEndUpInAll() =
        runTest {
            withTestApp { _, events ->
                withContext(Dispatchers.Default) {
                    val writers = List(WORKERS) { i -> async { events.record(RecordedPing(i)) } }
                    val readers = List(WORKERS) { async { events.ofType<RecordedPing>().size } }
                    writers.awaitAll()
                    readers.awaitAll().forEach { assertTrue(it in 0..WORKERS) }
                }

                assertEquals((0 until WORKERS).toSet(), events.ofType<RecordedPing>().map { it.n }.toSet())
                assertEquals(WORKERS, events.ofType<RecordedPing>().size)
            }
        }
}
