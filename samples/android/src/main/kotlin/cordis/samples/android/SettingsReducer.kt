package cordis.samples.android

import cordis.kernel.PluginKey
import cordis.runtime.PluginState

data class SettingsRow(
    val key: PluginKey,
    val title: String,
    val description: String,
    val status: PluginStatus,
    val enabled: Boolean,
    val locked: Boolean,
)

data class SettingsState(
    val rows: List<SettingsRow> = emptyList(),
)

sealed interface SettingsAction {
    data class Toggle(
        val key: PluginKey,
    ) : SettingsAction
}

sealed interface SettingsEffect {
    data class SetEnabled(
        val key: PluginKey,
        val enabled: Boolean,
    ) : SettingsEffect
}

data class SettingsResult(
    val state: SettingsState,
    val effect: SettingsEffect? = null,
)

fun settingsState(plugins: List<PluginState>): SettingsState =
    SettingsState(
        rows =
            plugins.map {
                SettingsRow(
                    key = it.key,
                    title = it.metadata.title,
                    description = it.metadata.description,
                    status = pluginStatus(it),
                    enabled = it.enabled,
                    locked = it.metadata.locked,
                )
            },
    )

// The rows mirror the runtime state, so a toggle never changes them: only the effect does, through the runtime.
fun reduce(
    state: SettingsState,
    action: SettingsAction,
): SettingsResult =
    when (action) {
        is SettingsAction.Toggle -> {
            val row = state.rows.firstOrNull { it.key == action.key }
            if (row == null || row.locked) {
                SettingsResult(state)
            } else {
                SettingsResult(state, SettingsEffect.SetEnabled(row.key, !row.enabled))
            }
        }
    }
