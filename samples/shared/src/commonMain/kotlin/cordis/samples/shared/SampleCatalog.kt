package cordis.samples.shared

import cordis.kernel.CordisClock
import cordis.kernel.DefaultClock
import cordis.kernel.PluginKey
import cordis.runtime.PluginCatalog
import cordis.runtime.PluginMetadata
import cordis.runtime.pluginCatalog

object SampleKeys {
    val logger = PluginKey("logger")
    val database = PluginKey("database")
    val notes = PluginKey("notes")
    val weather = PluginKey("weather")
    val analytics = PluginKey("analytics")
}

/**
 * The five sample features, in display order; only the logger is locked. [clock] drives the weather updates.
 */
fun sampleCatalog(clock: CordisClock = DefaultClock): PluginCatalog =
    pluginCatalog {
        plugin(
            SampleKeys.logger,
            LoggerPlugin,
            PluginMetadata("Logger", "Records log lines from every feature.", locked = true),
        )
        plugin(
            SampleKeys.database,
            DatabasePlugin,
            PluginMetadata("Database", "In-memory key/value storage."),
        )
        plugin(
            SampleKeys.notes,
            NotesPlugin,
            PluginMetadata("Notes", "Write and list notes, stored in the database."),
        )
        plugin(
            SampleKeys.weather,
            WeatherPlugin,
            WeatherConfig(clock = clock),
            PluginMetadata("Weather", "A simulated reading that refreshes on a timer."),
        )
        plugin(
            SampleKeys.analytics,
            AnalyticsPlugin,
            PluginMetadata("Analytics", "Tracks events and writes them to the log."),
        )
    }
