package cordis.ktor

import cordis.kernel.Context
import cordis.runtime.PluginRuntime
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.runBlocking

/**
 * Configures the [Cordis] application plugin.
 */
public class CordisConfig {
    /** The root context the plugin owns and disposes on stop; `null` makes the plugin create `Context.root()`. */
    public var context: Context? = null

    /** Builds the runtime on the root context; it is started at install and stopped on stop. `null` means no runtime. */
    public var runtime: ((Context) -> PluginRuntime)? = null
}

private val ContextAttribute = AttributeKey<Context>("cordis.context")
private val RuntimeAttribute = AttributeKey<PluginRuntime>("cordis.runtime")

/**
 * Ties a Cordis root [Context], and optionally a [PluginRuntime], to the application.
 * The runtime is started with `runBlocking` before `install` returns. On `ApplicationStopped` the runtime is
 * stopped, then the root context is disposed, even when `stop()` throws.
 */
public val Cordis: ApplicationPlugin<CordisConfig> =
    createApplicationPlugin(name = "Cordis", createConfiguration = ::CordisConfig) {
        val context = pluginConfig.context ?: Context.root()
        val runtime = pluginConfig.runtime?.invoke(context)
        if (runtime != null) runBlocking { runtime.start() }
        application.attributes.put(ContextAttribute, context)
        if (runtime != null) application.attributes.put(RuntimeAttribute, runtime)

        on(MonitoringEvent(ApplicationStopped)) {
            runBlocking {
                try {
                    runtime?.stop()
                } finally {
                    context.dispose()
                }
            }
        }
    }

/** The root context owned by [Cordis]; throws [IllegalStateException] when [Cordis] is not installed. */
public val Application.cordis: Context
    get() = attributes.getOrNull(ContextAttribute) ?: notInstalled()

/** The runtime configured on [Cordis], or `null` when none is; throws [IllegalStateException] when [Cordis] is not installed. */
public val Application.cordisRuntime: PluginRuntime?
    get() {
        if (!attributes.contains(ContextAttribute)) notInstalled()
        return attributes.getOrNull(RuntimeAttribute)
    }

/** The root context owned by [Cordis]; throws [IllegalStateException] when [Cordis] is not installed. */
public val ApplicationCall.cordis: Context
    get() = application.cordis

/** The runtime configured on [Cordis], or `null` when none is; throws [IllegalStateException] when [Cordis] is not installed. */
public val ApplicationCall.cordisRuntime: PluginRuntime?
    get() = application.cordisRuntime

private fun notInstalled(): Nothing = error("Cordis is not installed: call install(Cordis) on the application first")
