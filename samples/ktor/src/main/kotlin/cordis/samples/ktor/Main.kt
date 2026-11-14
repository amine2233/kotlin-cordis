package cordis.samples.ktor

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.io.path.Path

fun main() {
    val storeFile = System.getenv("CORDIS_SAMPLE_STORE")?.takeIf { it.isNotBlank() } ?: "enabled-plugins.json"
    embeddedServer(CIO, host = "127.0.0.1", port = 8080) { sampleModule(Path(storeFile)) }.start(wait = true)
}
