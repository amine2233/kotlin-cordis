package cordis.samples.shared

import cordis.kernel.ContributionKey

/**
 * One navigation destination a feature plugin adds to the app; [id] is the plugin's catalog key.
 */
data class NavEntry(
    val id: String,
    val title: String,
)

/**
 * The contribution point every feature plugin adds its [NavEntry] to.
 */
object NavEntries : ContributionKey<NavEntry>
