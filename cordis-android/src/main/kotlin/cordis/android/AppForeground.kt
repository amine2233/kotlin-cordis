package cordis.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cordis.kernel.Context
import cordis.kernel.Plugin
import cordis.kernel.PluginHandle
import cordis.kernel.ServiceKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Marker service provided under [AppForegroundKey] while the app is in the foreground.
 */
public object AppForeground

/**
 * Filled while the observed lifecycle is at least [Lifecycle.State.STARTED] and empty otherwise, so plugins
 * that require it pause when the app goes to the background and resume when it comes back.
 */
public object AppForegroundKey : ServiceKey<AppForeground>

/**
 * Provides [AppForegroundKey] while the configured [LifecycleOwner] is at least [Lifecycle.State.STARTED].
 * Disposing the plugin stops observing the owner and withdraws the service.
 *
 * Example:
 * ```kotlin
 * ctx.plugin(AppForegroundPlugin, ProcessLifecycleOwner.get())
 * ```
 */
public object AppForegroundPlugin : Plugin<LifecycleOwner> {
    override suspend fun apply(
        ctx: Context,
        config: LifecycleOwner,
    ) {
        ctx.launch {
            var provider: PluginHandle<Unit>? = null
            config.lifecycle.currentStateFlow
                .map { it.isAtLeast(Lifecycle.State.STARTED) }
                .distinctUntilChanged()
                .collect { foreground ->
                    if (foreground) {
                        provider = ctx.plugin("AppForegroundProvider") { it.provide(AppForegroundKey, AppForeground) }
                    } else {
                        provider?.dispose()
                        provider = null
                    }
                }
        }
    }
}

/**
 * Loads [AppForegroundPlugin] observing [owner] and returns its handle; dispose it to stop observing.
 * The default owner is [ProcessLifecycleOwner], which needs an Android runtime: pass another owner in plain JVM code.
 */
public suspend fun Context.installAppForeground(owner: LifecycleOwner = ProcessLifecycleOwner.get()): PluginHandle<LifecycleOwner> =
    plugin(AppForegroundPlugin, owner)
