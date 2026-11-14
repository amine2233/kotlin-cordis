package cordis.loader

/**
 * Every problem the loader can report while validating a plan.
 */
public sealed class LoaderError(
    override val message: String,
) : Exception(message) {
    public abstract val entry: Int

    /** No plugin is registered under the entry's key. */
    public data class UnknownKey(
        val key: String,
        override val entry: Int,
    ) : LoaderError("Entry $entry: no plugin registered under key '$key'")

    /** Two entries share the same name. */
    public data class DuplicateName(
        val name: String,
        override val entry: Int,
    ) : LoaderError("Entry $entry: duplicate entry name '$name'")

    /** The entry's config failed to decode into the plugin's config type. */
    public data class InvalidConfig(
        val key: String,
        override val entry: Int,
        val reason: String,
    ) : LoaderError("Entry $entry ('$key'): invalid config: $reason")
}
