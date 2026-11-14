package cordis.samples.android

import cordis.kernel.PluginKey
import cordis.samples.shared.SampleKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsReducerTests {
    private val state =
        SettingsState(
            rows =
                listOf(
                    row(SampleKeys.logger, enabled = true, locked = true),
                    row(SampleKeys.database, enabled = true),
                    row(SampleKeys.weather, enabled = false),
                ),
        )

    @Test
    fun togglingAnEnabledPluginDisablesIt() {
        val result = reduce(state, SettingsAction.Toggle(SampleKeys.database))
        assertEquals(SettingsEffect.SetEnabled(SampleKeys.database, enabled = false), result.effect)
        assertEquals(state, result.state)
    }

    @Test
    fun togglingADisabledPluginEnablesIt() {
        val result = reduce(state, SettingsAction.Toggle(SampleKeys.weather))
        assertEquals(SettingsEffect.SetEnabled(SampleKeys.weather, enabled = true), result.effect)
        assertEquals(state, result.state)
    }

    @Test
    fun togglingTheLockedLoggerHasNoEffect() {
        val result = reduce(state, SettingsAction.Toggle(SampleKeys.logger))
        assertNull(result.effect)
        assertEquals(state, result.state)
    }

    @Test
    fun togglingAnUnknownKeyHasNoEffect() {
        val result = reduce(state, SettingsAction.Toggle(PluginKey("unknown")))
        assertNull(result.effect)
        assertEquals(state, result.state)
    }

    private fun row(
        key: PluginKey,
        enabled: Boolean,
        locked: Boolean = false,
    ) = SettingsRow(
        key = key,
        title = key.value,
        description = "",
        status = if (enabled) PluginStatus.Active else PluginStatus.Disabled,
        enabled = enabled,
        locked = locked,
    )
}
