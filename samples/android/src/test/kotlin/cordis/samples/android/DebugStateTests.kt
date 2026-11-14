package cordis.samples.android

import cordis.kernel.Fork
import cordis.kernel.Inject
import cordis.kernel.PluginId
import cordis.kernel.PluginInfo
import cordis.kernel.RuntimeSnapshot
import cordis.kernel.ServiceId
import cordis.kernel.TextTreeVisitor
import cordis.kernel.accept
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugStateTests {
    private val snapshot =
        RuntimeSnapshot(
            plugins =
                listOf(
                    pluginInfo("LoggerPlugin"),
                    pluginInfo("WeatherPlugin", status = Fork.Status.Failed("boom")),
                    pluginInfo("NotesPlugin", status = Fork.Status.Pending, missing = listOf("DatabaseKey")),
                ),
            slots = emptyList(),
        )

    @Test
    fun mapsTheHealthReportAndTheTree() {
        val state = debugState(snapshot)

        assertFalse(state.healthy)
        assertEquals(listOf("WeatherPlugin: boom"), state.failed)
        assertEquals(listOf("NotesPlugin: DatabaseKey"), state.missingServices)
        assertEquals(listOf("NotesPlugin"), state.pending)
        assertEquals(TextTreeVisitor().also { snapshot.accept(it) }.render(), state.tree)
    }

    @Test
    fun usesFreshVisitorsOnEveryCall() {
        assertEquals(debugState(snapshot), debugState(snapshot))
    }

    @Test
    fun emptySnapshotIsHealthyWithAnEmptyTree() {
        val state = debugState(RuntimeSnapshot(plugins = emptyList(), slots = emptyList()))

        assertTrue(state.healthy)
        assertEquals(emptyList(), state.failed)
        assertEquals(emptyList(), state.missingServices)
        assertEquals(emptyList(), state.pending)
        assertEquals("", state.tree)
    }

    private fun pluginInfo(
        name: String,
        status: Fork.Status = Fork.Status.Active,
        missing: List<String> = emptyList(),
    ) = PluginInfo(
        id = PluginId(name),
        name = name,
        status = status,
        parent = null,
        key = null,
        inject = Inject.NONE,
        missing = missing.map(::ServiceId),
    )
}
