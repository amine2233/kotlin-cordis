package cordis.samples.android

import cordis.kernel.Fork
import cordis.kernel.ServiceId
import cordis.runtime.PluginMetadata
import cordis.runtime.PluginState
import cordis.samples.shared.DatabaseKey
import cordis.samples.shared.LoggerKey
import cordis.samples.shared.SampleKeys
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginStatusTests {
    @Test
    fun disabledWhenTurnedOffOrWithoutAHandle() {
        assertEquals(PluginStatus.Disabled, pluginStatus(state(enabled = false, status = Fork.Status.Active)))
        assertEquals(PluginStatus.Disabled, pluginStatus(state(status = null)))
        assertEquals("Disabled", PluginStatus.Disabled.label)
    }

    @Test
    fun failedKeepsTheReason() {
        val status = pluginStatus(state(status = Fork.Status.Failed("boom")))
        assertEquals(PluginStatus.Failed("boom"), status)
        assertEquals("Failed", status.label)
    }

    @Test
    fun active() {
        val status = pluginStatus(state(status = Fork.Status.Active))
        assertEquals(PluginStatus.Active, status)
        assertEquals("Active", status.label)
    }

    @Test
    fun waitingNamesEveryMissingService() {
        val status =
            pluginStatus(state(status = Fork.Status.Pending, missing = listOf(DatabaseKey.serviceId, LoggerKey.serviceId)))
        assertEquals(PluginStatus.Waiting(listOf("DatabaseKey", "LoggerKey")), status)
        assertEquals("Waiting for DatabaseKey, LoggerKey", status.label)
    }

    @Test
    fun startingWhenNothingIsMissing() {
        listOf(Fork.Status.Pending, Fork.Status.Loading, Fork.Status.Disposed).forEach {
            val status = pluginStatus(state(status = it))
            assertEquals(PluginStatus.Starting, status)
            assertEquals("Starting", status.label)
        }
    }

    private fun state(
        enabled: Boolean = true,
        status: Fork.Status?,
        missing: List<ServiceId> = emptyList(),
    ) = PluginState(
        key = SampleKeys.notes,
        metadata = PluginMetadata("Notes", "Notes."),
        enabled = enabled,
        status = status,
        missing = missing,
    )
}
