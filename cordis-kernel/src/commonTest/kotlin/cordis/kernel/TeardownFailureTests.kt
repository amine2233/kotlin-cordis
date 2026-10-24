package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class TeardownBoom(
    val id: Int,
) : Exception("Boom $id")

object FailingTeardownPlugin : Plugin<Probe> {
    override suspend fun apply(
        ctx: Context,
        config: Probe,
    ) {
        ctx.onDispose { config.log.add("ok") }
        ctx.onDispose { throw TeardownBoom(1) }
    }
}

class TeardownFailureTests {
    @Test
    fun failingTeardownDoesNotStopLaterOnes() =
        runTest {
            val probe = Probe()
            val reported = mutableListOf<TeardownBoom>()
            val scope =
                Scope(failureHandler = { error ->
                    if (error is TeardownBoom) reported.add(error)
                })
            scope.collect { probe.log.add("first") }
            scope.collect { throw TeardownBoom(1) }
            scope.collect { probe.log.add("last") }
            scope.collect { throw TeardownBoom(2) }

            scope.dispose()

            assertEquals(listOf("last", "first"), probe.log)
            assertEquals(listOf(TeardownBoom(2), TeardownBoom(1)), reported)
        }

    @Test
    fun rootScopeEmitsTeardownFailedWithRootName() =
        runTest {
            val app = Context.root()
            val probe = Probe()
            app.on<TeardownFailed> { event ->
                val id = (event.error as? TeardownBoom)?.id ?: -1
                probe.log.add("${event.plugin}:$id")
            }
            app.onDispose { throw TeardownBoom(7) }

            app.dispose()

            assertEquals(listOf("root:7"), probe.log)
        }

    @Test
    fun forkScopeEmitsTeardownFailedWithPluginName() =
        runTest {
            val app = Context.root()
            val probe = Probe()
            app.on<TeardownFailed> { event ->
                probe.log.add(event.plugin)
            }
            app.plugin(FailingTeardownPlugin, probe)
            app.dispose()

            assertEquals(listOf(FailingTeardownPlugin.name, "ok"), probe.log)
        }

    @Test
    fun disposableCallReportsOnceAndStaysNonThrowing() =
        runTest {
            var count = 0
            val scope = Scope(failureHandler = { count++ })
            val handle = scope.collect { throw TeardownBoom(1) }
            handle()
            handle()

            assertEquals(1, count)
            assertTrue(handle.isDisposed)
        }
}
