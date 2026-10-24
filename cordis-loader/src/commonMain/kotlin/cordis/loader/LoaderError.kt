package cordis.loader

/**
 * Every problem the loader can report while validating a plan.
 */
sealed class LoaderError(
    override val message: String,
) : Exception(message) {
    abstract val entry: Int

    /** No plugin is registered under the entry's key. */
    data class UnknownKey(
        val key: String,
        override val entry: Int,
    ) : LoaderError("Entry $entry: no plugin registered under key '$key'")

    /** Two entries share the same name. */
    data class DuplicateName(
        val name: String,
        override val entry: Int,
    ) : LoaderError("Entry $entry: duplicate entry name '$name'")

    /** The entry's config failed to decode into the plugin's config type. */
    data class InvalidConfig(
        val key: String,
        override val entry: Int,
        val reason: String,
    ) : LoaderError("Entry $entry ('$key'): invalid config: $reason")
}
