package cordis.android

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import cordis.kernel.PluginKey
import cordis.runtime.EnabledPluginsStore
import kotlinx.coroutines.flow.first

private const val KEY_PREFIX = "cordis.enabled."

/**
 * An [EnabledPluginsStore] backed by a Preferences [dataStore]. Each override is a boolean preference named
 * `cordis.enabled.<plugin key>`; preferences without that prefix are never read or written.
 */
public class DataStoreEnabledPluginsStore(
    private val dataStore: DataStore<Preferences>,
) : EnabledPluginsStore {
    override suspend fun load(): Map<PluginKey, Boolean> =
        dataStore.data
            .first()
            .asMap()
            .mapNotNull { (key, value) ->
                if (key.name.startsWith(KEY_PREFIX) && value is Boolean) PluginKey(key.name.removePrefix(KEY_PREFIX)) to value else null
            }.toMap()

    override suspend fun save(overrides: Map<PluginKey, Boolean>) {
        dataStore.edit { preferences ->
            preferences
                .asMap()
                .keys
                .filter { it.name.startsWith(KEY_PREFIX) }
                .forEach { preferences.remove(it) }
            overrides.forEach { (key, enabled) -> preferences[booleanPreferencesKey(KEY_PREFIX + key.value)] = enabled }
        }
    }
}
