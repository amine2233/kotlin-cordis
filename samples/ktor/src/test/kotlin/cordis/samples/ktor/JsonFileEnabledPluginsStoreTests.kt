package cordis.samples.ktor

import cordis.kernel.PluginKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonFileEnabledPluginsStoreTests {
    private val directory: Path = createTempDirectory("cordis-store")
    private val file: Path = directory.resolve("nested").resolve("enabled-plugins.json")
    private val weather = PluginKey("weather")
    private val notes = PluginKey("notes")

    @AfterTest
    fun deleteDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun missingFileLoadsNoOverrides() =
        runTest {
            assertEquals(emptyMap(), JsonFileEnabledPluginsStore(file).load())
        }

    @Test
    fun savedOverridesLoadBack() =
        runTest {
            JsonFileEnabledPluginsStore(file).save(mapOf(weather to false, notes to true))

            assertEquals(mapOf(weather to false, notes to true), JsonFileEnabledPluginsStore(file).load())
            assertEquals(
                false,
                Json
                    .parseToJsonElement(file.readText())
                    .jsonObject
                    .getValue("weather")
                    .jsonPrimitive.boolean,
            )
        }

    @Test
    fun saveReplacesThePreviousOverrides() =
        runTest {
            val store = JsonFileEnabledPluginsStore(file)
            store.save(mapOf(weather to false, notes to false))
            store.save(mapOf(notes to true))

            assertEquals(mapOf(notes to true), store.load())
        }

    @Test
    fun saveLeavesNoTemporaryFile() =
        runTest {
            val store = JsonFileEnabledPluginsStore(file)
            store.save(mapOf(weather to false))
            store.save(mapOf(weather to true))

            assertEquals(listOf(file), file.parent.listDirectoryEntries())
        }

    @Test
    fun concurrentSavesLeaveOneOfTheSavedMaps() =
        runTest {
            val store = JsonFileEnabledPluginsStore(file)
            val saved = (0 until 32).map { index -> mapOf(PluginKey("plugin$index") to (index % 2 == 0), weather to false) }

            saved.map { overrides -> async(Dispatchers.Default) { store.save(overrides) } }.awaitAll()

            assertContains(saved, JsonFileEnabledPluginsStore(file).load())
            assertEquals(listOf(file), file.parent.listDirectoryEntries())
        }

    @Test
    fun malformedFileThrowsNamingThePath() =
        runTest {
            file.parent.toFile().mkdirs()
            file.writeText("{\"weather\": ")

            val error = assertFailsWith<IllegalStateException> { JsonFileEnabledPluginsStore(file).load() }
            assertTrue(error.message.orEmpty().contains(file.toString()), error.message)
        }
}
