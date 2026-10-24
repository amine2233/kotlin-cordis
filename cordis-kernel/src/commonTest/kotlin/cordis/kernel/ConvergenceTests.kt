package cordis.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class Counter {
    var limit = 0
}

object CounterKey : ServiceKey<Counter>

object CounterConsumer : Plugin<Probe> {
    override val inject = Inject.of(required = listOf(CounterKey))

    override suspend fun apply(ctx: Context, config: Probe) {
        val limit = ctx.require(CounterKey).limit
        config.log.add("start:$limit")
    }
}

class ConvergenceTests {
    @Test
    fun updateMutatesPublishedValueWithoutRestartingDependents() = runTest {
        val app = Context.root()
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

    @Test
    fun updateKeepsOccurrenceAndGeneration() = runTest {
        val app = Context.root()
        app.provide(CounterKey, Counter())
        val before = app.snapshot().slots
        app.update(CounterKey) { value -> value.limit = 1 }
        assertEquals(before.map { it.generation }, app.snapshot().slots.map { it.generation })
    }

    @Test
    fun updateThrowsWhenSlotIsNotProvided() = runTest {
        val app = Context.root()
        assertFailsWith<CordisException.NotPublished> {
            app.update(CounterKey) { value -> value.limit = 1 }
        }
    }

    @Test
    fun updateThrowsWhenAnotherContextPublishedTheSlot() = runTest {
        val app = Context.root()
        val other = Context.root()
        other.provide(CounterKey, Counter())
        assertFailsWith<CordisException.NotPublished> {
            app.update(CounterKey) { value -> value.limit = 1 }
        }
    }

    @Test
    fun republishingIsANewOccurrenceAndRestartsDependents() = runTest {
        val app = Context.root()
        val probe = Probe()
        app.provide(CounterKey, Counter())
        app.plugin(CounterConsumer, probe)

        app.provide(CounterKey, Counter())

        assertEquals(listOf("start:0", "start:0"), probe.log)
    }
}
