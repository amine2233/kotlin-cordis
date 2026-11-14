package cordis.samples.android

import cordis.kernel.Fork
import cordis.runtime.PluginState

sealed interface PluginStatus {
    val label: String

    data object Active : PluginStatus {
        override val label: String = "Active"
    }

    data class Waiting(
        val services: List<String>,
    ) : PluginStatus {
        override val label: String = "Waiting for ${services.joinToString(", ")}"
    }

    data class Failed(
        val reason: String,
    ) : PluginStatus {
        override val label: String = "Failed"
    }

    data object Starting : PluginStatus {
        override val label: String = "Starting"
    }

    data object Disabled : PluginStatus {
        override val label: String = "Disabled"
    }
}

fun pluginStatus(state: PluginState): PluginStatus {
    val status = state.status
    return when {
        !state.enabled || status == null -> PluginStatus.Disabled
        status is Fork.Status.Failed -> PluginStatus.Failed(status.reason)
        status == Fork.Status.Active -> PluginStatus.Active
        state.missing.isNotEmpty() -> PluginStatus.Waiting(state.missing.map { it.description })
        else -> PluginStatus.Starting
    }
}
