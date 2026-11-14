package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

object WrappedKey : ServiceKey<String>

object WrappedProvider : Plugin<String> {
    override suspend fun apply(
        ctx: Context,
        config: String,
    ) {
        ctx.provide(WrappedKey, config)
    }
}

object WrappedConsumer : Plugin<Probe> {
    override val inject = Inject.of(required = listOf(WrappedKey))

    override suspend fun apply(
        ctx: Context,
        config: Probe,
    ) {
        config.log.add("saw:${ctx.require(WrappedKey)}")
    }
}

class InterceptTests {
    @Test
    fun singleLayerWrapsProvidedValue() =
        runTest {
            withRoot { app ->
                val traced = app.intercept(WrappedKey) { "traced($it)" }
                traced.provide(WrappedKey, "db")
                assertEquals("traced(db)", app[WrappedKey])
            }
        }

    @Test
    fun firstRegisteredLayerIsOutermost() =
        runTest {
            withRoot { app ->
                val layered =
                    app
                        .intercept(WrappedKey) { "outer($it)" }
                        .intercept(WrappedKey) { "inner($it)" }
                layered.provide(WrappedKey, "db")
                assertEquals("outer(inner(db))", app[WrappedKey])
            }
        }

    @Test
    fun layersAreInheritedByIsolatedContexts() =
        runTest {
            withRoot { app ->
                val isolated =
                    app
                        .intercept(WrappedKey) { "L($it)" }
                        .isolate(WrappedKey, label = "t")
                isolated.provide(WrappedKey, "db")
                assertEquals("L(db)", isolated[WrappedKey])
            }
        }

    @Test
    fun layersAreInheritedByLoadedPlugins() =
        runTest {
            withRoot { app ->
                val traced = app.intercept(WrappedKey) { "L($it)" }
                traced.plugin(WrappedProvider, "db")
                assertEquals("L(db)", app[WrappedKey])
            }
        }

    @Test
    fun siblingContextIsUnaffected() =
        runTest {
            withRoot { app ->
                app.intercept(WrappedKey) { "L($it)" }
                app.provide(WrappedKey, "db")
                assertEquals("db", app[WrappedKey])
            }
        }

    @Test
    fun layerIsNotAppliedRetroactively() =
        runTest {
            withRoot { app ->
                app.provide(WrappedKey, "db")
                val traced = app.intercept(WrappedKey) { "L($it)" }
                assertEquals("db", app[WrappedKey])
                assertEquals("db", traced[WrappedKey])
            }
        }

    @Test
    fun dependentsReceiveWrappedValueWithoutExtraRestart() =
        runTest {
            withRoot { app ->
                val probe = Probe()
                val consumer = app.plugin(WrappedConsumer, probe)
                app.intercept(WrappedKey) { "L($it)" }.plugin(WrappedProvider, "db")
                assertEquals(Fork.Status.Active, consumer.status)
                assertEquals(listOf("saw:L(db)"), probe.log)
            }
        }

    @Test
    fun conflictBehaviourIsUnchanged() =
        runTest {
            withRoot { app ->
                app.provide(WrappedKey, "a")
                val other = app.intercept(WrappedKey) { "L($it)" }
                assertFailsWith<CordisException.ServiceConflict> {
                    other.provide(WrappedKey, "b")
                }
                assertEquals("a", app[WrappedKey])
            }
        }
}
