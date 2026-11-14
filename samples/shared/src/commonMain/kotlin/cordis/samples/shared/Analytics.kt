package cordis.samples.shared

import cordis.kernel.Context
import cordis.kernel.Inject
import cordis.kernel.Plugin
import cordis.kernel.ServiceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

interface Analytics {
    val events: List<String>

    fun track(event: String)
}

class InMemoryAnalytics(
    private val logger: Logger,
) : Analytics {
    private val tracked = MutableStateFlow(emptyList<String>())

    override val events: List<String>
        get() = tracked.value

    override fun track(event: String) {
        tracked.update { it + event }
        logger.log("analytics: $event")
    }
}

object AnalyticsKey : ServiceKey<Analytics>

object AnalyticsPlugin : Plugin<Unit> {
    override val inject: Inject = Inject.of(required = listOf(LoggerKey))

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        ctx.provide(AnalyticsKey, InMemoryAnalytics(ctx.require(LoggerKey)))
        ctx.contribute(NavEntries, NavEntry(id = SampleKeys.analytics.value, title = "Analytics"))
    }
}
