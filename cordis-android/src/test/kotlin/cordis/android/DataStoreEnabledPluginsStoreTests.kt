package cordis.android

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import cordis.kernel.PluginKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DataStoreEnabledPluginsStoreTests {
    private val alpha = PluginKey("alpha")
    private val beta = PluginKey("beta")

    @Test
    fun savedOverridesLoadBack() =
        runTest {
            val store = DataStoreEnabledPluginsStore(PreferencesDataStoreMock())
            store.save(mapOf(alpha to true, beta to false))
            assertEquals(mapOf(alpha to true, beta to false), store.load())
        }

    @Test
    fun saveReplacesEveryPreviousOverride() =
        runTest {
            val store = DataStoreEnabledPluginsStore(PreferencesDataStoreMock())
            store.save(mapOf(alpha to true, beta to false))
            store.save(mapOf(beta to true))
            assertEquals(mapOf(beta to true), store.load())
        }

    @Test
    fun emptyStoreLoadsNoOverrides() =
        runTest {
            assertEquals(emptyMap(), DataStoreEnabledPluginsStore(PreferencesDataStoreMock()).load())
        }

    @Test
    fun unrelatedPreferencesSurviveSaveAndAreNotLoaded() =
        runTest {
            val theme = stringPreferencesKey("theme")
            val dataStore = PreferencesDataStoreMock(preferencesOf(theme to "dark"))
            val store = DataStoreEnabledPluginsStore(dataStore)

            store.save(mapOf(alpha to true))
            store.save(mapOf(beta to false))

            assertEquals(mapOf(beta to false), store.load())
            val stored = dataStore.data.first()
            assertEquals("dark", stored[theme])
            assertEquals(false, stored[booleanPreferencesKey("cordis.enabled.beta")])
            assertEquals(null, stored[booleanPreferencesKey("cordis.enabled.alpha")])
        }
}
