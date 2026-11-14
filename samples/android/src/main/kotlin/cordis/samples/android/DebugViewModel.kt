package cordis.samples.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cordis.kernel.RuntimeSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DebugViewModel(
    snapshots: StateFlow<RuntimeSnapshot>,
) : ViewModel() {
    val state: StateFlow<DebugState> =
        snapshots
            .map { debugState(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, debugState(snapshots.value))
}
