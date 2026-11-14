package cordis.samples.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cordis.runtime.PluginRuntime
import cordis.runtime.PluginRuntimeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val runtime: PluginRuntime,
) : ViewModel() {
    private val mutableState = MutableStateFlow(settingsState(runtime.state.value))
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            runtime.state.collect { mutableState.value = settingsState(it) }
        }
    }

    fun dispatch(action: SettingsAction) {
        val result = reduce(mutableState.value, action)
        mutableState.value = result.state
        result.effect?.let(::run)
    }

    private fun run(effect: SettingsEffect) {
        when (effect) {
            is SettingsEffect.SetEnabled -> {
                viewModelScope.launch {
                    try {
                        runtime.setEnabled(effect.key, effect.enabled)
                    } catch (_: PluginRuntimeException) {
                        // The rows follow runtime.state, so a rejected toggle simply leaves them as they are.
                    }
                }
            }
        }
    }
}
