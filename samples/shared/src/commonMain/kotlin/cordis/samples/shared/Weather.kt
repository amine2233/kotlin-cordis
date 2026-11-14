package cordis.samples.shared

import cordis.every
import cordis.kernel.Context
import cordis.kernel.CordisClock
import cordis.kernel.DefaultClock
import cordis.kernel.Plugin
import cordis.kernel.ServiceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class WeatherReading(
    val update: Int,
    val temperatureCelsius: Int,
)

interface Weather {
    val reading: StateFlow<WeatherReading>
}

/**
 * A fake weather station: each [refresh] moves to the next temperature of a fixed cycle.
 */
class InMemoryWeather : Weather {
    private val current = MutableStateFlow(readingAt(0))

    override val reading: StateFlow<WeatherReading> = current.asStateFlow()

    fun refresh() {
        current.update { readingAt(it.update + 1) }
    }

    private fun readingAt(update: Int) = WeatherReading(update, TEMPERATURES[update % TEMPERATURES.size])

    private companion object {
        val TEMPERATURES = listOf(18, 19, 21, 22, 20, 17)
    }
}

data class WeatherConfig(
    val interval: Duration = 15.minutes,
    val clock: CordisClock = DefaultClock,
)

object WeatherKey : ServiceKey<Weather>

object WeatherPlugin : Plugin<WeatherConfig> {
    override suspend fun apply(
        ctx: Context,
        config: WeatherConfig,
    ) {
        val weather = InMemoryWeather()
        ctx.provide(WeatherKey, weather)
        ctx.contribute(NavEntries, NavEntry(id = SampleKeys.weather.value, title = "Weather"))
        ctx.every(config.interval, config.clock) { weather.refresh() }
    }
}
