package cordis.android

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A [LifecycleOwner] whose state the test sets directly, from any thread. */
class LifecycleOwnerMock(
    initial: Lifecycle.State,
) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = initial
    }

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

/** An in-memory Preferences [DataStore] whose `updateData` applies the transform to the current value. */
class PreferencesDataStoreMock(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also {
            state.value =
                it
        }
}
