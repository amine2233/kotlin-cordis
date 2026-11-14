package cordis.samples.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cordis.samples.shared.NavEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Destination {
    data class Feature(
        val id: String,
    ) : Destination

    data object Settings : Destination
}

data class DrawerState(
    val entries: List<NavEntry> = emptyList(),
    val selected: Destination = Destination.Settings,
) {
    val title: String
        get() =
            when (selected) {
                is Destination.Feature -> entries.firstOrNull { it.id == selected.id }?.title.orEmpty()
                Destination.Settings -> SETTINGS_TITLE
            }

    companion object {
        const val SETTINGS_TITLE = "Settings"
    }
}

class DrawerViewModel(
    entries: StateFlow<List<NavEntry>>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DrawerState(entries = entries.value))
    val state: StateFlow<DrawerState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            entries.collect { list ->
                mutableState.update { current ->
                    val selected = current.selected
                    val stillPresent = selected !is Destination.Feature || list.any { it.id == selected.id }
                    DrawerState(entries = list, selected = if (stillPresent) selected else Destination.Settings)
                }
            }
        }
    }

    fun select(destination: Destination) {
        mutableState.update { current ->
            val available = destination !is Destination.Feature || current.entries.any { it.id == destination.id }
            if (available) current.copy(selected = destination) else current
        }
    }
}
