package cordis.loader

/**
 * An ordered, declarative list of plugins to load, resolved through a [PluginRegistry].
 */
data class LoadPlan(
    val entries: List<Entry>
) {
    /**
     * One plugin to load: a resolve key, an optional unique name and optional JSON config.
     */
    data class Entry(
        val key: String,
        val name: String? = null,
        val config: String? = null
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
                        reason = t.message ?: t.toString()
                    )
                )
            }
        }
        return errors
    }
}
