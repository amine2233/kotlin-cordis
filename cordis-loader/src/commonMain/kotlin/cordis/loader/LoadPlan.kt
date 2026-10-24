package cordis.loader

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * An ordered, declarative list of plugins to load, resolved through a [PluginRegistry].
 */
data class LoadPlan(
    val entries: List<Entry>,
) {
    /**
     * One plugin to load: a resolve key, an optional unique name and optional JSON config.
     */
    data class Entry(
        val key: String,
        val name: String? = null,
        val config: String? = null,
    )

    /**
     * Returns every error in the plan (unknown key, duplicate name, undecodable config); empty when valid.
     */
    fun validate(against: PluginRegistry): List<LoaderError> {
        val errors = mutableListOf<LoaderError>()
        val seenNames = mutableSetOf<String>()

        for ((index, entry) in entries.withIndex()) {
            if (entry.name != null && !seenNames.add(entry.name)) {
                errors.add(LoaderError.DuplicateName(entry.name, entry = index))
            }
            if (!against.isRegistered(entry.key)) {
                errors.add(LoaderError.UnknownKey(entry.key, entry = index))
                continue
            }
            try {
                against.loader(entry.key, entry.config)
            } catch (t: Throwable) {
                errors.add(
                    LoaderError.InvalidConfig(
                        key = entry.key,
                        entry = index,
                        reason = t.message ?: t.toString(),
                    ),
                )
            }
        }
        return errors
    }
}

class LoadPlanBuilder(
    private val json: Json,
) {
    internal val entries = mutableListOf<LoadPlan.Entry>()

    fun plugin(
        key: String,
        name: String? = null,
    ) {
        entries.add(LoadPlan.Entry(key = key, name = name))
    }

    fun <C> plugin(
        key: String,
        config: C,
        serializer: KSerializer<C>,
        name: String? = null,
    ) {
        entries.add(LoadPlan.Entry(key = key, name = name, config = json.encodeToString(serializer, config)))
    }
}

/**
 * Type-safe [LoadPlan] builder; configs are encoded to JSON with their serializer.
 */
fun loadPlan(
    json: Json = Json,
    block: LoadPlanBuilder.() -> Unit,
): LoadPlan = LoadPlan(LoadPlanBuilder(json).apply(block).entries.toList())
