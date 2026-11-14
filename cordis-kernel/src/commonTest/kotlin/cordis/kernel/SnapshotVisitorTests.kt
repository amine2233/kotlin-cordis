package cordis.kernel

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
