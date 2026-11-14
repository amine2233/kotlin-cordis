package cordis.loader

import cordis.kernel.PluginKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * An ordered, declarative list of plugins to load, resolved through a [PluginFactoryRegistry].
 */
@Serializable
public data class LoadPlan(
    val entries: List<Entry>,
) {
    /**
     * One plugin to load: a resolve key, an optional unique name and optional JSON config.
     */
    @Serializable
    public data class Entry(
        @Serializable(with = PluginKeySerializer::class)
        val key: PluginKey,
        val name: String? = null,
        val config: JsonElement? = null,
    )

    public companion object {
        /**
         * Parses a plan shaped like `{"entries":[{"key":"db","name":"primary","config":{...}}]}`.
         * Unknown fields are errors; throws [kotlinx.serialization.SerializationException] on malformed input.
         */
        public fun fromJson(text: String): LoadPlan = Json.decodeFromString(serializer(), text)
    }

    /**
     * Returns every error in the plan (unknown key, duplicate name, undecodable config); empty when valid.
     */
    public fun validate(against: PluginFactoryRegistry): List<LoaderError> {
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

/**
 * Writes a [PluginKey] as its plain string value.
 */
internal object PluginKeySerializer : KSerializer<PluginKey> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("cordis.loader.PluginKey", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: PluginKey,
    ) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): PluginKey = PluginKey(decoder.decodeString())
}

public class LoadPlanBuilder(
    private val json: Json,
) {
    internal val entries = mutableListOf<LoadPlan.Entry>()

    public fun plugin(
        key: PluginKey,
        name: String? = null,
    ) {
        entries.add(LoadPlan.Entry(key = key, name = name))
    }

    public fun <C> plugin(
        key: PluginKey,
        config: C,
        serializer: KSerializer<C>,
        name: String? = null,
    ) {
        entries.add(LoadPlan.Entry(key = key, name = name, config = json.encodeToJsonElement(serializer, config)))
    }
}

/**
 * Type-safe [LoadPlan] builder; configs are encoded to JSON with their serializer.
 */
public fun loadPlan(
    json: Json = Json,
    block: LoadPlanBuilder.() -> Unit,
): LoadPlan = LoadPlan(LoadPlanBuilder(json).apply(block).entries.toList())
