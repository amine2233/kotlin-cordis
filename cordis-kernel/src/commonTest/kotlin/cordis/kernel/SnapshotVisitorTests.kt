package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

object VisitorDbKey : ServiceKey<String>

object VisitorLoggerKey : ServiceKey<String>

private fun pluginInfo(
    name: String,
    status: Fork.Status = Fork.Status.Active,
    parent: String? = null,
    inject: Inject = Inject.NONE,
    missing: List<String> = emptyList(),
): PluginInfo =
    PluginInfo(
        id = PluginId(name),
        name = name,
        status = status,
        parent = parent?.let(::PluginId),
        key = null,
        inject = inject,
        missing = missing.map(::ServiceId),
    )

private fun slotInfo(
    service: String,
    provider: String?,
    realm: String = Slot.SHARED_REALM,
): SlotInfo =
    SlotInfo(
        service = service,
        realm = realm,
        provider = provider?.let(::PluginId),
        generation = 0,
        providerKey = null,
    )

private class RecordingVisitor : SnapshotVisitor {
    val calls = mutableListOf<String>()

    override fun enterPlugin(
        plugin: PluginInfo,
        depth: Int,
    ) {
        calls += "enter ${plugin.name} $depth"
    }

    override fun exitPlugin(
        plugin: PluginInfo,
        depth: Int,
    ) {
        calls += "exit ${plugin.name} $depth"
    }

    override fun visitProvidedSlot(
        slot: SlotInfo,
        provider: PluginInfo,
    ) {
        calls += "slot ${slot.service}@${slot.realm} ${provider.name}"
    }

    override fun visitMissing(
        service: ServiceId,
        plugin: PluginInfo,
    ) {
        calls += "missing ${service.description} ${plugin.name}"
    }
}

private fun RuntimeSnapshot.recordedCalls(): List<String> = RecordingVisitor().also { accept(it) }.calls

private fun RuntimeSnapshot.textTree(): String = TextTreeVisitor().also { accept(it) }.render()

private fun RuntimeSnapshot.mermaid(): String = MermaidVisitor().also { accept(it) }.render()

private fun RuntimeSnapshot.health(): HealthReport = HealthVisitor().also { accept(it) }.report()

class SnapshotVisitorTests {
    @Test
    fun walksTreeDepthFirstWithSlotsAndMissingServices() {
        val snapshot =
            RuntimeSnapshot(
                plugins =
                    listOf(
                        pluginInfo("db"),
                        pluginInfo("repo", parent = "db", missing = listOf("LoggerKey", "CacheKey")),
                        pluginInfo("api"),
                        pluginInfo("cache", parent = "repo"),
                        pluginInfo("web", parent = "db"),
                    ),
                slots =
                    listOf(
                        slotInfo("DbKey", "db"),
                        slotInfo("ApiKey", "api"),
                        slotInfo("DbKey", "db", realm = "tenant"),
                    ),
            )

        assertEquals(
            listOf(
                "enter db 0",
                "slot DbKey@shared db",
                "slot DbKey@tenant db",
                "enter repo 1",
                "missing LoggerKey repo",
                "missing CacheKey repo",
                "enter cache 2",
                "exit cache 2",
                "exit repo 1",
                "enter web 1",
                "exit web 1",
                "exit db 0",
                "enter api 0",
                "slot ApiKey@shared api",
                "exit api 0",
            ),
            snapshot.recordedCalls(),
        )
    }

    @Test
    fun emptySnapshotProducesNoCallbacks() {
        assertEquals(emptyList(), RuntimeSnapshot(plugins = emptyList(), slots = emptyList()).recordedCalls())
    }

    @Test
    fun orphanIsWalkedAsRoot() {
        val snapshot =
            RuntimeSnapshot(
                plugins = listOf(pluginInfo("a"), pluginInfo("orphan", parent = "ghost"), pluginInfo("b", parent = "a")),
                slots = emptyList(),
            )

        assertEquals(
            listOf("enter a 0", "enter b 1", "exit b 1", "exit a 0", "enter orphan 0", "exit orphan 0"),
            snapshot.recordedCalls(),
        )
    }

    @Test
    fun parentCycleAndSelfParentVisitEachPluginOnce() {
        val snapshot =
            RuntimeSnapshot(
                plugins =
                    listOf(
                        pluginInfo("x", parent = "y"),
                        pluginInfo("y", parent = "x"),
                        pluginInfo("self", parent = "self"),
                        pluginInfo("root"),
                    ),
                slots = emptyList(),
            )

        val calls = snapshot.recordedCalls()

        assertEquals(
            listOf(
                "enter root 0",
                "exit root 0",
                "enter x 0",
                "enter y 1",
                "exit y 1",
                "exit x 0",
                "enter self 0",
                "exit self 0",
            ),
            calls,
        )
        for (name in listOf("x", "y", "self", "root")) {
            assertEquals(1, calls.count { it.startsWith("enter $name ") })
            assertEquals(1, calls.count { it.startsWith("exit $name ") })
        }
    }

    @Test
    fun rootProvidedAndUnknownProviderSlotsProduceNoCallback() {
        val snapshot =
            RuntimeSnapshot(
                plugins = listOf(pluginInfo("db")),
                slots = listOf(slotInfo("RootKey", null), slotInfo("GhostKey", "ghost"), slotInfo("DbKey", "db")),
            )

        assertEquals(listOf("enter db 0", "slot DbKey@shared db", "exit db 0"), snapshot.recordedCalls())
    }

    @Test
    fun textTreeRendersRootsAsSiblingsWithStatusAndMissing() {
        val snapshot =
            RuntimeSnapshot(
                plugins =
                    listOf(
                        pluginInfo("db"),
                        pluginInfo("repo", status = Fork.Status.Pending, parent = "db", missing = listOf("LoggerKey")),
                        pluginInfo("api", status = Fork.Status.Failed("boom")),
                    ),
                slots = emptyList(),
            )

        assertEquals(
            """
            ├─ db [Active]
            │  └─ repo [Pending] missing: LoggerKey
            └─ api [Failed: boom]
            """.trimIndent(),
            snapshot.textTree(),
        )
    }

    @Test
    fun textTreeUsesSpacesUnderLastSibling() {
        val snapshot =
            RuntimeSnapshot(
                plugins =
                    listOf(
                        pluginInfo("app"),
                        pluginInfo("loading", status = Fork.Status.Loading, parent = "app"),
                        pluginInfo("last", status = Fork.Status.Disposed, parent = "app"),
                        pluginInfo("leaf", parent = "last", missing = listOf("AKey", "BKey")),
                        pluginInfo("loop", parent = "loop"),
                    ),
                slots = emptyList(),
            )

        assertEquals(
            """
            ├─ app [Active]
            │  ├─ loading [Loading]
            │  └─ last [Disposed]
            │     └─ leaf [Active] missing: AKey, BKey
            └─ loop [Active]
            """.trimIndent(),
            snapshot.textTree(),
        )
    }

    @Test
    fun textTreeOfEmptySnapshotIsEmpty() {
        assertEquals("", RuntimeSnapshot(plugins = emptyList(), slots = emptyList()).textTree())
    }

    @Test
    fun mermaidRendersNodesParentEdgesAndServiceEdges() {
        val snapshot =
            RuntimeSnapshot(
                plugins =
                    listOf(
                        pluginInfo("db \"main\" <x> #1"),
                        pluginInfo(
                            "repo",
                            status = Fork.Status.Pending,
                            parent = "db \"main\" <x> #1",
                            inject = Inject(required = listOf(ServiceId("Key")), optional = listOf(ServiceId("Other"))),
                        ),
                        pluginInfo(
                            "api",
                            status = Fork.Status.Failed("boom\nagain"),
                            inject = Inject(required = listOf(ServiceId("Other"))),
                        ),
                    ),
                slots =
                    listOf(
                        slotInfo("Key", "db \"main\" <x> #1", realm = "tenant"),
                        slotInfo("Other", "db \"main\" <x> #1"),
                        slotInfo("Key", null),
                    ),
            )

        assertEquals(
            """
            flowchart TD
              p0["db #quot;main#quot; #lt;x#gt; #35;1 [Active]"]
              p1["repo [Pending]"]
              p2["api [Failed: boom again]"]
              p0 --> p1
              p0 -.->|"Key@tenant"| p1
              p0 -.->|"Other"| p1
              p0 -.->|"Other"| p2
            """.trimIndent(),
            snapshot.mermaid(),
        )
    }

    @Test
    fun mermaidOfEmptySnapshotIsHeaderOnly() {
        assertEquals("flowchart TD", RuntimeSnapshot(plugins = emptyList(), slots = emptyList()).mermaid())
    }

    @Test
    fun mermaidSkipsEdgeToCyclicParent() {
        val snapshot =
            RuntimeSnapshot(
                plugins = listOf(pluginInfo("x", parent = "y"), pluginInfo("y", parent = "x")),
                slots = emptyList(),
            )

        assertEquals(
            """
            flowchart TD
              p0["x [Active]"]
              p1["y [Active]"]
              p0 --> p1
            """.trimIndent(),
            snapshot.mermaid(),
        )
    }

    @Test
    fun healthReportIsHealthyWhenAllActive() {
        val snapshot =
            RuntimeSnapshot(
                plugins = listOf(pluginInfo("a"), pluginInfo("b", parent = "a")),
                slots = emptyList(),
            )

        val report = snapshot.health()

        assertTrue(report.healthy)
        assertEquals(HealthReport(failed = emptyList(), missingServices = emptyList(), pending = emptyList()), report)
    }

    @Test
    fun healthReportListsFailedMissingAndPendingInVisitOrder() {
        val failed = pluginInfo("failed", status = Fork.Status.Failed("boom"))
        val waiting = pluginInfo("waiting", status = Fork.Status.Pending, missing = listOf("LoggerKey"))
        val active = pluginInfo("active")
        val loading = pluginInfo("loading", status = Fork.Status.Loading)
        val child = pluginInfo("child", status = Fork.Status.Pending, parent = "failed")
        val snapshot = RuntimeSnapshot(plugins = listOf(failed, waiting, active, loading, child), slots = emptyList())

        val report = snapshot.health()

        assertFalse(report.healthy)
        assertEquals(listOf(failed), report.failed)
        assertEquals(listOf(waiting), report.missingServices)
        assertEquals(listOf(child, waiting), report.pending)
    }

    @Test
    fun walksRealGraph() =
        runTest {
            withRoot { app ->
                app.plugin("db") { it.provide(VisitorDbKey, "db") }
                app.plugin("repo", inject = Inject.of(required = listOf(VisitorDbKey, VisitorLoggerKey))) {}
                app.plugin("parent") { it.plugin("child") {} }
                app.plugin("api") { throw IllegalStateException("boom") }

                val snapshot = app.snapshot()
                val logger = VisitorLoggerKey.serviceId.description

                assertEquals(
                    """
                    ├─ db [Active]
                    ├─ repo [Pending] missing: $logger
                    ├─ parent [Active]
                    │  └─ child [Active]
                    └─ api [Failed: boom]
                    """.trimIndent(),
                    snapshot.textTree(),
                )
                assertTrue(
                    "  p0 -.->|\"${VisitorDbKey.serviceId.description}\"| p1" in snapshot.mermaid().lines(),
                )
                val report = snapshot.health()
                assertEquals(listOf("api"), report.failed.map { it.name })
                assertEquals(listOf("repo"), report.missingServices.map { it.name })
                assertEquals(listOf("repo"), report.pending.map { it.name })
            }
        }
}
