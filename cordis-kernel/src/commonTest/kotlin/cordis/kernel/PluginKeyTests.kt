package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

object KeyedServiceA : ServiceKey<Int>

object KeyedServiceB : ServiceKey<Int>

object KeyedServiceC : ServiceKey<Int>

object KeyedDatabase : Plugin<Unit> {
    override val pluginKey = PluginKey("db")

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        ctx.provide(KeyedServiceA, 1)
    }
}

object UnkeyedPlugin : Plugin<Unit> {
    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

object KeyedRejected : Plugin<Unit> {
    class Rejected : Exception("rejected")

    override val pluginKey = PluginKey("rejected")

    override fun validate(config: Unit): Unit = throw Rejected()

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

object KeyedWaiting : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(KeyedServiceA, KeyedServiceB), optional = listOf(KeyedServiceC))

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

object KeyedTenantConsumer : Plugin<Unit> {
    override val inject = Inject.of(required = listOf(KeyedServiceA))

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {}
}

class PluginKeyTests {
    @Test
    fun declaredKeyAppearsInSnapshotAndMissingKeyIsNull() =
        runTest {
            val app = Context.root()
            app.plugin(KeyedDatabase)
            app.plugin(UnkeyedPlugin)

            val plugins = app.snapshot().plugins
            assertEquals(PluginKey("db"), plugins[0].key)
            assertNull(plugins[1].key)
        }

    @Test
    fun lambdaOverloadCarriesKey() =
        runTest {
            val app = Context.root()
            app.plugin("metrics", key = PluginKey("metrics")) { }
            app.plugin("x") { }

            val plugins = app.snapshot().plugins
            assertEquals(PluginKey("metrics"), plugins[0].key)
            assertNull(plugins[1].key)
        }

    @Test
    fun sameKeyedPluginAppliedTwiceKeepsBothRegistered() =
        runTest {
            val app = Context.root()
            app.plugin(UnkeyedPlugin)
            val keyed =
                object : Plugin<Unit> {
                    override val pluginKey = PluginKey("twice")

                    override suspend fun apply(
                        ctx: Context,
                        config: Unit,
                    ) {}
                }
            val first = app.plugin(keyed)
            val second = app.plugin(keyed)

            assertEquals(Fork.Status.Active, first.status)
            assertEquals(Fork.Status.Active, second.status)
            val keyedInfos = app.snapshot().plugins.filter { it.key == PluginKey("twice") }
            assertEquals(listOf(first.fork.id, second.fork.id), keyedInfos.map { it.id })
        }

    @Test
    fun slotCarriesProviderKey() =
        runTest {
            val app = Context.root()
            app.plugin(KeyedDatabase)
            app.provide(KeyedServiceB, 2)

            val slots = app.snapshot().slots
            assertEquals(PluginKey("db"), slots[0].providerKey)
            assertNull(slots[1].providerKey)
            assertNull(slots[1].provider)
        }

    @Test
    fun replaceValueKeepsProviderKey() =
        runTest {
            val app = Context.root()
            app.plugin("keyed-provider", key = PluginKey("provider")) { ctx ->
                ctx.provide(KeyedServiceA, 1)
                ctx.replaceValue(KeyedServiceA) { it + 1 }
            }

            assertEquals(PluginKey("provider"), app.snapshot().slots[0].providerKey)
        }

    @Test
    fun statusChangesCarryKey() =
        runTest {
            val app = Context.root()
            val events = mutableListOf<PluginStatusChanged>()
            app.on<PluginStatusChanged> { events.add(it) }

            val handle =
                app.plugin("waiting", Inject.of(required = listOf(KeyedServiceA)), key = PluginKey("waiting")) { }
            app.provide(KeyedServiceA, 1)
            handle.dispose()

            assertEquals(
                listOf(Fork.Status.Loading, Fork.Status.Active, Fork.Status.Disposed),
                events.map { it.status },
            )
            assertTrue(events.all { it.key == PluginKey("waiting") })
        }

    @Test
    fun pluginFailedCarriesKeyForApplyAndValidate() =
        runTest {
            val app = Context.root()
            val events = mutableListOf<PluginFailed>()
            app.on<PluginFailed> { events.add(it) }

            app.plugin("throws", key = PluginKey("throws")) { throw BoomException() }
            app.plugin(KeyedRejected)

            assertEquals(listOf(PluginKey("throws"), PluginKey("rejected")), events.map { it.key })
        }

    @Test
    fun teardownFailedCarriesKeyAndRootHasNone() =
        runTest {
            val app = Context.root()
            val events = mutableListOf<TeardownFailed>()
            app.on<TeardownFailed> { events.add(it) }

            val handle =
                app.plugin("teardown", key = PluginKey("teardown")) { ctx ->
                    ctx.onDispose { throw TeardownBoom(1) }
                }
            handle.dispose()
            app.onDispose { throw TeardownBoom(2) }
            app.dispose()

            assertEquals(listOf("teardown" to PluginKey("teardown"), "root" to null), events.map { it.plugin to it.key })
        }

    @Test
    fun missingListsRequiredServicesInDeclarationOrder() =
        runTest {
            val app = Context.root()
            app.plugin(KeyedWaiting)

            fun info() = app.snapshot().plugins.single()

            assertEquals(KeyedWaiting.inject, info().inject)
            assertEquals(listOf(KeyedServiceA.serviceId, KeyedServiceB.serviceId), info().missing)
            assertEquals(Fork.Status.Pending, info().status)

            app.provide(KeyedServiceA, 1)
            assertEquals(listOf(KeyedServiceB.serviceId), info().missing)

            app.provide(KeyedServiceB, 2)
            assertTrue(info().missing.isEmpty())
            assertEquals(Fork.Status.Active, info().status)
            assertTrue(app.snapshot().plugins.none { KeyedServiceC.serviceId in it.missing })
        }

    @Test
    fun missingResolvesInTheForksRealms() =
        runTest {
            val app = Context.root()
            app.provide(KeyedServiceA, 1)
            val tenant = app.isolate(KeyedServiceA, "tenant")
            val handle = tenant.plugin(KeyedTenantConsumer)

            fun info() = app.snapshot().plugins.first { it.id == handle.fork.id }

            assertEquals(listOf(KeyedServiceA.serviceId), info().missing)

            tenant.provide(KeyedServiceA, 2)
            assertTrue(info().missing.isEmpty())
            assertEquals(Fork.Status.Active, info().status)
        }
}
