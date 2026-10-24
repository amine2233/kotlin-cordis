package cordis.kernel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

object TextKey : ServiceKey<String>

class CopyOnWriteTests {
    @Test
    fun replaceValueSwapsValueKeepingOccurrenceAndDependents() =
        runTest {
            val app = Context.root()
            val probe = Probe()
            app.provide(TextKey, "old")
            val occurrence = app.registry.entry(app.slot(TextKey.serviceId))!!.occurrence
            val dependent =
                app.plugin(
                    object : Plugin<Unit> {
                        override val inject = Inject.of(required = listOf(TextKey))

                        override suspend fun apply(
                            ctx: Context,
                            config: Unit,
                        ) {
                            probe.log.add("start")
                        }
                    },
                )

            app.replaceValue(TextKey) { it + "!" }

            assertEquals("old!", app[TextKey])
            assertEquals(occurrence, app.registry.entry(app.slot(TextKey.serviceId))!!.occurrence)
            assertEquals(listOf("start"), probe.log)
            assertEquals(Fork.Status.Active, dependent.status)
            app.dispose()
        }

    @Test
    fun replaceValueThrowsWhenNotPublished() =
        runTest {
            val app = Context.root()
            assertFailsWith<CordisException.NotPublished> { app.replaceValue(TextKey) { it } }
            app.dispose()
        }

    @Test
    fun concurrentReadsDuringMutationNeverThrow() =
        runTest {
            val app = Context.root()
            val reads =
                withContext(Dispatchers.Default) {
                    val readers =
                        List(4) {
                            async { repeat(20_000) { app[TextKey]?.let { v -> assertNotNull(v) } } }
                        }
                    val writer =
                        async {
                            repeat(300) {
                                val h = app.plugin("p$it") { ctx -> ctx.provide(TextKey, "v$it") }
                                h.dispose()
                            }
                        }
                    (readers + writer).awaitAll()
                }
            assertEquals(5, reads.size)
            app.dispose()
        }
}
