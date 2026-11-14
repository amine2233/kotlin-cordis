package cordis.samples.ktor

import cordis.kernel.PluginKey
import cordis.runtime.EnabledPluginsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * An [EnabledPluginsStore] in a JSON file at [path] mapping plugin key to boolean, e.g. `{"weather": false}`.
 * A save writes a temporary file next to [path] and moves it over [path] atomically; `load` and `save` are serialised.
 */
class JsonFileEnabledPluginsStore(
    private val path: Path,
) : EnabledPluginsStore {
    private val mutex = Mutex()

    override suspend fun load(): Map<PluginKey, Boolean> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (!path.exists()) return@withContext emptyMap()
                val stored =
                    try {
                        Json.decodeFromString<Map<String, Boolean>>(path.readText())
                    } catch (error: IllegalArgumentException) {
                        throw IllegalStateException("Malformed enabled-plugins file $path: ${error.message}", error)
                    }
                stored.mapKeys { (key, _) -> PluginKey(key) }
            }
        }

    override suspend fun save(overrides: Map<PluginKey, Boolean>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val directory = path.toAbsolutePath().parent
                directory.createDirectories()
                val temporary = Files.createTempFile(directory, "${path.fileName}.", ".tmp")
                try {
                    temporary.writeText(Json.encodeToString(overrides.mapKeys { (key, _) -> key.value }))
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    temporary.deleteIfExists()
                }
            }
        }
    }
}
