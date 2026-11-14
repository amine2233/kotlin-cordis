package cordis.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginCatalogTests {
    @Test
    fun entriesKeepDeclarationOrder() {
        assertEquals(listOf(Core, Feature, Extra), testCatalog.entries.map { it.key })
        assertEquals("Feature", testCatalog.entries[1].metadata.title)
    }

    @Test
    fun duplicateKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            pluginCatalog {
                plugin(Feature, FeatureEntryPlugin, PluginMetadata("A", "First"))
                plugin(Feature, ExtraPlugin, PluginMetadata("B", "Second"))
            }
        }
    }

    @Test
    fun lockedEntryDisabledByDefaultIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            pluginCatalog {
                plugin(Core, CorePlugin, PluginMetadata("Core", "Locked", locked = true, enabledByDefault = false))
            }
        }
    }
}
