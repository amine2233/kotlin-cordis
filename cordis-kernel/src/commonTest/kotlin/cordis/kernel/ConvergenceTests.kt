package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Counter {
    var limit = 0
}

object CounterKey : ServiceKey<Counter>

object CounterConsumer : Plugin<Probe> {
    override val inject = Inject.of(required = listOf(CounterKey))

    override suspend fun apply(
        ctx: Context,
        config: Probe,
    ) {
        val limit = ctx.require(CounterKey).limit
        config.log.add("start:$limit")
    }
}

class ConvergenceTests {
    @Test
    fun updateMutatesPublishedValueWithoutRestartingDependents() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val events = Probe()
                app.on<ServiceChanged> { events.log.add("changed") }
                app.provide(CounterKey, Counter())
                app.plugin(CounterConsumer, probe)
                events.log.clear()

                app.update(CounterKey) { value -> value.limit = 10 }

                assertEquals(10, app[CounterKey]?.limit)
                assertEquals(listOf("start:0"), probe.log)
                assertTrue(events.log.isEmpty())
            }
        }

    @Test
    fun updateKeepsOccurrenceAndGeneration() =
        runTest {
            withRoot { app ->
                app.provide(CounterKey, Counter())
                val before = app.snapshot().slots
                app.update(CounterKey) { value -> value.limit = 1 }
                assertEquals(before.map { it.generation }, app.snapshot().slots.map { it.generation })
            }
        }

    @Test
    fun updateThrowsWhenSlotIsNotProvided() =
        runTest {
            withRoot { app ->
                assertFailsWith<CordisException.NotPublished> {
                    app.update(CounterKey) { value -> value.limit = 1 }
                }
            }
        }

    @Test
    fun updateThrowsWhenAnotherContextPublishedTheSlot() =
        runTest {
            withRoot { app ->
                withRoot { other ->
                    other.provide(CounterKey, Counter())
                    assertFailsWith<CordisException.NotPublished> {
                        app.update(CounterKey) { value -> value.limit = 1 }
                    }
                }
            }
        }

    @Test
    fun republishingIsANewOccurrenceAndRestartsDependents() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                app.provide(CounterKey, Counter())
                app.plugin(CounterConsumer, probe)

                app.provide(CounterKey, Counter())

                assertEquals(listOf("start:0", "start:0"), probe.log)
            }
        }
}
