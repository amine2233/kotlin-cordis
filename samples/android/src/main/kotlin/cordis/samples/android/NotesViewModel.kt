package cordis.samples.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cordis.samples.shared.Notes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotesState(
    val available: Boolean = false,
    val notes: List<String> = emptyList(),
    val input: String = "",
) {
    val canAdd: Boolean
        get() = available && input.isNotBlank()
}

class NotesViewModel(
    notes: Flow<Notes?>,
) : ViewModel() {
    private var service: Notes? = null
    private val mutableState = MutableStateFlow(NotesState())
    val state: StateFlow<NotesState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            notes.collect { notes ->
                service = notes
                mutableState.update { it.copy(available = notes != null, notes = notes?.list().orEmpty()) }
            }
        }
    }

    fun onInputChange(input: String) {
        mutableState.update { it.copy(input = input) }
    }

    fun add() {
        val notes = service ?: return
        val input = mutableState.value.input
        if (input.isBlank()) return
        notes.add(input)
        mutableState.update { it.copy(notes = notes.list(), input = "") }
    }
}
