package cordis.loader

import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.Inject
import cordis.kernel.Plugin
import cordis.kernel.ServiceKey
import cordis.testing.withTestApp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

object BaseKey : ServiceKey<Int>

object DerivedKey : ServiceKey<Int>

@Serializable
data class BaseConfig(
    val value: Int,
)

object BasePlugin : Plugin<BaseConfig> {
    override suspend fun apply(
        ctx: Context,
        config: BaseConfig,
    ) {
        ctx.provide(BaseKey, config.value)
    }
}

object DerivedPlugin : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(BaseKey))

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        ctx.provide(DerivedKey, ctx.require(BaseKey) + 1)
    }
}

class LoaderTestFailure : Exception("Broken")

object BrokenPlugin : Plugin<Unit> {
    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ): Unit = throw LoaderTestFailure()
}

object PositivePlugin : Plugin<BaseConfig> {
    override fun validate(config: BaseConfig) {
        require(config.value > 0) { "value must be positive" }
    }

    override suspend fun apply(
        ctx: Context,
        config: BaseConfig,
    ) {
        ctx.provide(DerivedKey, config.value)
    }
}

fun makeRegistry(): PluginRegistry {
    val registry = PluginRegistry()
    registry.register(BasePlugin, key = "base", serializer = BaseConfig.serializer())
    registry.register(DerivedPlugin, key = "derived")
    registry.register(BrokenPlugin, key = "broken")
    registry.register(PositivePlugin, key = "positive", serializer = BaseConfig.serializer())
    return registry
}

class LoaderTests {
    @Test
    fun validationReportsUnknownKey() {
        val plan = LoadPlan(entries = listOf(LoadPlan.Entry(key = "nope")))
        val errors = plan.validate(against = makeRegistry())
        assertEquals(1, errors.size)
        assertTrue(errors.first() is LoaderError.UnknownKey)
        assertEquals(0, errors.first().entry)
    }

    @Test
    fun validationReportsDuplicateName() {
        val plan =
            LoadPlan(
                entries =
                    listOf(
                        LoadPlan.Entry(key = "derived", name = "a"),
                        LoadPlan.Entry(key = "broken", name = "a"),
                    ),
            )
        val errors = plan.validate(against = makeRegistry())
        assertEquals(1, errors.size)
        assertTrue(errors.first() is LoaderError.DuplicateName)
        assertEquals(1, errors.first().entry)
    }

    @Test
    fun validationReportsConfigDecodeFailure() {
        val plan = LoadPlan(entries = listOf(LoadPlan.Entry(key = "base", config = """{"value":"x"}""")))
        val errors = plan.validate(against = makeRegistry())
        assertEquals(1, errors.size)
        assertTrue(errors.first() is LoaderError.InvalidConfig)
        assertEquals(0, errors.first().entry)
    }

    @Test
    fun validationReportsMissingConfig() {
        val plan = LoadPlan(entries = listOf(LoadPlan.Entry(key = "base")))
        val errors = plan.validate(against = makeRegistry())
        assertEquals(1, errors.size)
        assertTrue(errors.first() is LoaderError.InvalidConfig)
    }

    @Test
    fun validationCollectsEveryError() {
        val plan =
            LoadPlan(
                entries =
                    listOf(
                        LoadPlan.Entry(key = "nope"),
                        LoadPlan.Entry(key = "derived", name = "a"),
                        LoadPlan.Entry(key = "derived", name = "a"),
                        LoadPlan.Entry(key = "base", config = "{}"),
                    ),
            )
        val errors = plan.validate(against = makeRegistry())
        assertEquals(3, errors.size)
    }

    @Test
    fun invalidPlanLoadsNothing() =
        runTest {
            withTestApp { app, _ ->
                val plan =
                    LoadPlan(
                        entries =
                            listOf(
                                LoadPlan.Entry(key = "broken"),
                                LoadPlan.Entry(key = "nope"),
                            ),
                    )
                val outcomes = app.load(plan, registry = makeRegistry())
                assertEquals(2, outcomes.size)
                assertTrue(outcomes.all { it.status is Fork.Status.Failed })
                assertTrue(app.snapshot().plugins.isEmpty())
            }
        }

    @Test
    fun dependencyChainDeclaredOutOfOrder() =
        runTest {
            withTestApp { app, _ ->
                val plan =
                    LoadPlan(
                        entries =
                            listOf(
                                LoadPlan.Entry(key = "derived"),
                                LoadPlan.Entry(key = "base", config = """{"value":41}"""),
                            ),
                    )
                val outcomes = app.load(plan, registry = makeRegistry())
                assertEquals(listOf(Fork.Status.Pending, Fork.Status.Active), outcomes.map { it.status })
                assertEquals(listOf("derived", "base"), outcomes.map { it.key })
                assertEquals(42, app[DerivedKey])
                assertTrue(app.snapshot().plugins.all { it.status == Fork.Status.Active })
            }
        }

    @Test
    fun failedEntryDoesNotStopOthers() =
        runTest {
            withTestApp { app, _ ->
                val plan =
                    LoadPlan(
                        entries =
                            listOf(
                                LoadPlan.Entry(key = "broken"),
                                LoadPlan.Entry(key = "base", name = "primary", config = """{"value":1}"""),
                            ),
                    )
                val outcomes = app.load(plan, registry = makeRegistry())
                assertEquals(2, outcomes.size)
                assertTrue(outcomes[0].status is Fork.Status.Failed)
                assertEquals(Fork.Status.Active, outcomes[1].status)
                assertEquals("primary", outcomes[1].name)
                assertEquals(1, app[BaseKey])
            }
        }

    @Test
    fun validateRejectionFailsEntryWithoutStoppingOthers() =
        runTest {
            withTestApp { app, _ ->
                val plan =
                    LoadPlan(
                        entries =
                            listOf(
                                LoadPlan.Entry(key = "positive", config = """{"value":0}"""),
                                LoadPlan.Entry(key = "base", name = "primary", config = """{"value":1}"""),
                            ),
                    )
                assertTrue(plan.validate(against = makeRegistry()).isEmpty())
                val outcomes = app.load(plan, registry = makeRegistry())
                assertEquals(2, outcomes.size)
                assertEquals(Fork.Status.Failed("value must be positive"), outcomes[0].status)
                assertEquals(Fork.Status.Active, outcomes[1].status)
                assertEquals(1, app[BaseKey])
                assertNull(app[DerivedKey])
            }
        }

    @Test
    fun configIsDecodedIntoPluginConfig() =
        runTest {
            withTestApp { app, _ ->
                val plan = LoadPlan(entries = listOf(LoadPlan.Entry(key = "base", config = """{"value":7}""")))
                app.load(plan, registry = makeRegistry())
                assertEquals(7, app[BaseKey])
            }
        }
}

class LoadPlanBuilderTests {
    @Test
    fun builderEncodesConfigAndLoads() =
        runTest {
            val plan =
                loadPlan {
                    plugin("base", config = BaseConfig(41), serializer = BaseConfig.serializer(), name = "b")
                    plugin("derived")
                }
            assertEquals(listOf("b", null), plan.entries.map { it.name })
            assertEquals("""{"value":41}""", plan.entries.first().config)

            withTestApp { app, _ ->
                val outcomes = app.load(plan, makeRegistry())
                assertTrue(outcomes.all { it.status == Fork.Status.Active })
                assertEquals(42, app[DerivedKey])
            }
        }
}
