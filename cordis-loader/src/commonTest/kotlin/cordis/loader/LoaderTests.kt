package cordis.loader

import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.Inject
import cordis.kernel.Plugin
import cordis.kernel.PluginKey
import cordis.kernel.ServiceKey
import cordis.testing.withTestApp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

fun json(text: String): JsonElement = Json.parseToJsonElement(text)

fun LoadOutcome.loaded(): LoadOutcome.Loaded = assertIs<LoadOutcome.Loaded>(this)

fun makeRegistry(): PluginFactoryRegistry {
    val registry = PluginFactoryRegistry()
    registry.register(BasePlugin, serializer = BaseConfig.serializer(), key = PluginKey("base"))
    registry.register(DerivedPlugin, key = PluginKey("derived"))
    registry.register(BrokenPlugin, key = PluginKey("broken"))
    registry.register(PositivePlugin, serializer = BaseConfig.serializer(), key = PluginKey("positive"))
    return registry
}

class LoaderTests {
    @Test
    fun validationReportsUnknownKey() {
        val plan = LoadPlan(entries = listOf(LoadPlan.Entry(key = PluginKey("nope"))))
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
                        LoadPlan.Entry(key = PluginKey("derived"), name = "a"),
                        LoadPlan.Entry(key = PluginKey("broken"), name = "a"),
                    ),
            )
        val errors = plan.validate(against = makeRegistry())
        assertEquals(1, errors.size)
        assertTrue(errors.first() is LoaderError.DuplicateName)
        assertEquals(1, errors.first().entry)
    }

    @Test
    fun validationReportsConfigDecodeFailure() {
        val plan = LoadPlan(entries = listOf(LoadPlan.Entry(key = PluginKey("base"), config = json("""{"value":"x"}"""))))
        val errors = plan.validate(against = makeRegistry())
        assertEquals(1, errors.size)
        assertTrue(errors.first() is LoaderError.InvalidConfig)
        assertEquals(0, errors.first().entry)
    }

    @Test
    fun validationReportsMissingConfig() {
        val plan = LoadPlan(entries = listOf(LoadPlan.Entry(key = PluginKey("base"))))
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
                        LoadPlan.Entry(key = PluginKey("nope")),
                        LoadPlan.Entry(key = PluginKey("derived"), name = "a"),
                        LoadPlan.Entry(key = PluginKey("derived"), name = "a"),
                        LoadPlan.Entry(key = PluginKey("base"), config = json("{}")),
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
                                LoadPlan.Entry(key = PluginKey("broken")),
                                LoadPlan.Entry(key = PluginKey("nope")),
                            ),
                    )
                val outcomes = app.load(plan, registry = makeRegistry())
                assertEquals(2, outcomes.size)
                assertEquals(LoadOutcome.NotLoaded(PluginKey("broken"), name = null), outcomes[0])
                val invalid = assertIs<LoadOutcome.Invalid>(outcomes[1])
                assertEquals(listOf(LoaderError.UnknownKey(PluginKey("nope"), entry = 1)), invalid.errors)
                assertEquals("Entry 1: no plugin registered under key 'nope'", invalid.errors.single().message)
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
                                LoadPlan.Entry(key = PluginKey("derived")),
                                LoadPlan.Entry(key = PluginKey("base"), config = json("""{"value":41}""")),
                            ),
                    )
                val outcomes = app.load(plan, registry = makeRegistry())
                assertEquals(listOf(Fork.Status.Active, Fork.Status.Active), outcomes.map { it.loaded().handle.awaitActive() })
                assertEquals(listOf(PluginKey("derived"), PluginKey("base")), outcomes.map { it.key })
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
                                LoadPlan.Entry(key = PluginKey("broken")),
                                LoadPlan.Entry(key = PluginKey("base"), name = "primary", config = json("""{"value":1}""")),
                            ),
                    )
                val outcomes = app.load(plan, registry = makeRegistry())
                assertEquals(2, outcomes.size)
                assertTrue(outcomes[0].loaded().status is Fork.Status.Failed)
                assertEquals(Fork.Status.Active, outcomes[1].loaded().status)
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
                                LoadPlan.Entry(key = PluginKey("positive"), config = json("""{"value":0}""")),
                                LoadPlan.Entry(key = PluginKey("base"), name = "primary", config = json("""{"value":1}""")),
                            ),
                    )
                assertTrue(plan.validate(against = makeRegistry()).isEmpty())
                val outcomes = app.load(plan, registry = makeRegistry())
                assertEquals(2, outcomes.size)
                assertEquals(Fork.Status.Failed("value must be positive"), outcomes[0].loaded().status)
                assertEquals(Fork.Status.Active, outcomes[1].loaded().status)
                assertEquals(1, app[BaseKey])
                assertNull(app[DerivedKey])
            }
        }

    @Test
    fun configIsDecodedIntoPluginConfig() =
        runTest {
            withTestApp { app, _ ->
                val plan = LoadPlan(entries = listOf(LoadPlan.Entry(key = PluginKey("base"), config = json("""{"value":7}"""))))
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
                    plugin(PluginKey("base"), config = BaseConfig(41), serializer = BaseConfig.serializer(), name = "b")
                    plugin(PluginKey("derived"))
                }
            assertEquals(listOf("b", null), plan.entries.map { it.name })
            assertEquals(json("""{"value":41}"""), plan.entries.first().config)

            withTestApp { app, _ ->
                val outcomes = app.load(plan, makeRegistry())
                assertTrue(outcomes.all { it.loaded().status == Fork.Status.Active })
                assertEquals(42, app[DerivedKey])
            }
        }
}

object DefaultsKey : ServiceKey<String>

@Serializable
data class DefaultsConfig(
    val host: String = "localhost",
    val port: Int = 8080,
)

object DefaultsPlugin : Plugin<DefaultsConfig> {
    override suspend fun apply(
        ctx: Context,
        config: DefaultsConfig,
    ) {
        ctx.provide(DefaultsKey, "${config.host}:${config.port}")
    }
}

object KeyedPlugin : Plugin<Unit> {
    override val pluginKey: PluginKey = PluginKey("keyed")

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

object UnkeyedPlugin : Plugin<Unit> {
    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

object EchoKey : ServiceKey<String>

object EchoPlugin : Plugin<String> {
    override suspend fun apply(
        ctx: Context,
        config: String,
    ) {
        ctx.provide(EchoKey, config)
    }
}

class LoadPlanJsonTests {
    @Test
    fun fromJsonReadsNameAndConfig() {
        val plan = LoadPlan.fromJson("""{"entries":[{"key":"db","name":"primary","config":{"url":"x","pool":{"size":2}}}]}""")
        val entry = plan.entries.single()
        assertEquals(PluginKey("db"), entry.key)
        assertEquals("primary", entry.name)
        assertEquals(json("""{"url":"x","pool":{"size":2}}"""), entry.config)
    }

    @Test
    fun fromJsonWithOnlyKey() {
        val plan = LoadPlan.fromJson("""{"entries":[{"key":"metrics"}]}""")
        assertEquals(listOf(LoadPlan.Entry(PluginKey("metrics"))), plan.entries)
        assertNull(plan.entries.single().name)
        assertNull(plan.entries.single().config)
    }

    @Test
    fun keyIsEncodedAsPlainString() {
        val text = Json.encodeToString(LoadPlan.serializer(), LoadPlan(listOf(LoadPlan.Entry(PluginKey("db")))))
        assertEquals("""{"entries":[{"key":"db"}]}""", text)
    }

    @Test
    fun roundTripPreservesPlan() {
        val plan =
            LoadPlan(
                listOf(
                    LoadPlan.Entry(PluginKey("db"), name = "primary", config = json("""{"url":"x","pool":{"size":2,"tags":["a","b"]}}""")),
                    LoadPlan.Entry(PluginKey("metrics")),
                ),
            )
        assertEquals(plan, LoadPlan.fromJson(Json.encodeToString(LoadPlan.serializer(), plan)))
    }

    @Test
    fun malformedJsonThrows() {
        assertFailsWith<SerializationException> { LoadPlan.fromJson("""{"entries":[""") }
    }

    @Test
    fun entryWithoutKeyThrows() {
        assertFailsWith<SerializationException> { LoadPlan.fromJson("""{"entries":[{"name":"primary"}]}""") }
    }
}

class PluginFactoryRegistryTests {
    @Test
    fun registrationDefaultsToPluginKey() {
        val registry = PluginFactoryRegistry()
        registry.register(KeyedPlugin)
        assertTrue(registry.isRegistered(PluginKey("keyed")))
    }

    @Test
    fun registrationWithoutAnyKeyThrows() {
        val registry = PluginFactoryRegistry()
        val error = assertFailsWith<IllegalArgumentException> { registry.register(UnkeyedPlugin) }
        assertTrue(error.message.orEmpty().contains("UnkeyedPlugin"))
    }

    @Test
    fun missingConfigLoadsDefaults() =
        runTest {
            val registry = PluginFactoryRegistry()
            registry.register(DefaultsPlugin, serializer = DefaultsConfig.serializer(), key = PluginKey("defaults"))
            withTestApp { app, _ ->
                val outcomes = app.load(LoadPlan(listOf(LoadPlan.Entry(PluginKey("defaults")))), registry)
                assertEquals(Fork.Status.Active, outcomes.single().loaded().status)
                assertEquals("localhost:8080", app[DefaultsKey])
            }
        }

    @Test
    fun missingConfigWithRequiredFieldsIsInvalid() =
        runTest {
            val plan = LoadPlan(listOf(LoadPlan.Entry(PluginKey("base"))))
            val errors = plan.validate(against = makeRegistry())
            assertIs<LoaderError.InvalidConfig>(errors.single())
            withTestApp { app, _ ->
                val outcome = assertIs<LoadOutcome.Invalid>(app.load(plan, makeRegistry()).single())
                assertIs<LoaderError.InvalidConfig>(outcome.errors.single())
                assertTrue(app.snapshot().plugins.isEmpty())
            }
        }

    @Test
    fun loadedHandleIsUsable() =
        runTest {
            withTestApp { app, _ ->
                val plan = LoadPlan(listOf(LoadPlan.Entry(PluginKey("base"), config = json("""{"value":3}"""))))
                val outcome = app.load(plan, makeRegistry()).single()
                val handle = outcome.loaded().handle
                assertEquals(Fork.Status.Active, handle.awaitActive())
                assertEquals(3, app[BaseKey])
                handle.dispose()
                assertEquals(Fork.Status.Disposed, handle.status)
                assertNull(app[BaseKey])
            }
        }

    @Test
    fun duplicateNameMarksEntryInvalidAndSkipsOthers() =
        runTest {
            withTestApp { app, _ ->
                val plan =
                    LoadPlan(
                        listOf(
                            LoadPlan.Entry(PluginKey("derived"), name = "a"),
                            LoadPlan.Entry(PluginKey("broken"), name = "a"),
                        ),
                    )
                val outcomes = app.load(plan, makeRegistry())
                assertEquals(LoadOutcome.NotLoaded(PluginKey("derived"), name = "a"), outcomes[0])
                val invalid = assertIs<LoadOutcome.Invalid>(outcomes[1])
                assertEquals(listOf(LoaderError.DuplicateName("a", entry = 1)), invalid.errors)
                assertTrue(app.snapshot().plugins.isEmpty())
            }
        }

    @Test
    fun customDecodeReceivesRawElement() =
        runTest {
            val received = mutableListOf<JsonElement?>()
            val registry = PluginFactoryRegistry()
            registry.register(EchoPlugin, key = PluginKey("echo")) { element ->
                received.add(element)
                val text = element?.jsonObject?.get("text") ?: return@register "none"
                text.jsonPrimitive.content
            }
            val config = json("""{"text":"hi"}""")

            withTestApp { app, _ ->
                app.load(LoadPlan(listOf(LoadPlan.Entry(PluginKey("echo"), config = config))), registry)
                assertEquals("hi", app[EchoKey])
            }
            assertTrue(received.isNotEmpty())
            assertTrue(received.all { it == config })

            received.clear()
            withTestApp { app, _ ->
                app.load(LoadPlan(listOf(LoadPlan.Entry(PluginKey("echo")))), registry)
                assertEquals("none", app[EchoKey])
            }
            assertTrue(received.isNotEmpty())
            assertTrue(received.all { it == null })
        }
}
