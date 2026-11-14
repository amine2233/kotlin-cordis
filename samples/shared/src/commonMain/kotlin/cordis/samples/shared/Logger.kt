package cordis.samples.shared

import cordis.kernel.Context
import cordis.kernel.Plugin
import cordis.kernel.ServiceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

interface Logger {
    val lines: List<String>

    fun log(line: String)
}

class InMemoryLogger : Logger {
    private val recorded = MutableStateFlow(emptyList<String>())

    override val lines: List<String>
        get() = recorded.value

    override fun log(line: String) {
        recorded.update { it + line }
    }
}

object LoggerKey : ServiceKey<Logger>

object LoggerPlugin : Plugin<Unit> {
    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        ctx.provide(LoggerKey, InMemoryLogger())
        ctx.contribute(NavEntries, NavEntry(id = SampleKeys.logger.value, title = "Logs"))
    }
}
