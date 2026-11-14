package cordis.loader

import cordis.kernel.Context
import cordis.kernel.Fork
import cordis.kernel.PluginHandle
import cordis.kernel.PluginKey

/**
 * The result of loading one plan entry.
 */
public sealed class LoadOutcome {
    public abstract val key: PluginKey
    public abstract val name: String?

    /** The plugin was handed to the kernel; [status] reports whether it is active or failed. */
    public data class Loaded(
        override val key: PluginKey,
        override val name: String?,
        val handle: PluginHandle<*>,
    ) : LoadOutcome() {
        public val status: Fork.Status get() = handle.status
    }

    /** The entry has validation errors of its own. */
    public data class Invalid(
        override val key: PluginKey,
        override val name: String?,
        val errors: List<LoaderError>,
    ) : LoadOutcome()

    /** The entry was valid but skipped because the plan was rejected. */
    public data class NotLoaded(
        override val key: PluginKey,
        override val name: String?,
    ) : LoadOutcome()
}

/**
 * Loads the plan in order and returns one outcome per entry.
 * An invalid plan loads nothing: entries with errors are [LoadOutcome.Invalid], the rest [LoadOutcome.NotLoaded].
 */
public suspend fun Context.load(
    plan: LoadPlan,
    registry: PluginFactoryRegistry,
): List<LoadOutcome> {
    val errors = plan.validate(against = registry)
    if (errors.isNotEmpty()) {
        return plan.entries.mapIndexed { index, entry ->
            val entryErrors = errors.filter { it.entry == index }
            if (entryErrors.isEmpty()) {
                LoadOutcome.NotLoaded(key = entry.key, name = entry.name)
            } else {
                LoadOutcome.Invalid(key = entry.key, name = entry.name, errors = entryErrors)
            }
        }
    }

    return plan.entries.mapIndexed { index, entry ->
        val loader =
            try {
                registry.loader(entry.key, entry.config)
            } catch (t: Throwable) {
                val error = LoaderError.InvalidConfig(key = entry.key, entry = index, reason = t.message ?: t.toString())
                return@mapIndexed LoadOutcome.Invalid(key = entry.key, name = entry.name, errors = listOf(error))
            }
        if (loader == null) {
            val error = LoaderError.UnknownKey(entry.key, entry = index)
            LoadOutcome.Invalid(key = entry.key, name = entry.name, errors = listOf(error))
        } else {
            LoadOutcome.Loaded(key = entry.key, name = entry.name, handle = loader(this))
        }
    }
}
