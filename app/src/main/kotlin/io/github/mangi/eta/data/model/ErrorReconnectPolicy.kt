package io.github.mangi.eta.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Total retry duration after an error, not the delay between attempts. Applies to all models. */
@Serializable(with = ErrorReconnectPolicySerializer::class)
enum class ErrorReconnectPolicy(
    val persistedValue: String,
    val windowMillis: Long?,
) {
    NONE("none", 0L),
    WINDOW_30S("window_30s", 30_000L),
    WINDOW_1M("window_1m", 60_000L),
    WINDOW_5M("window_5m", 300_000L),
    CONTINUOUS("continuous", null);

    companion object {
        /** Missing or unrecognized values must never silently enable retries. */
        fun fromPersistedValue(value: String?): ErrorReconnectPolicy =
            entries.firstOrNull { it.persistedValue == value } ?: NONE
    }
}

/** Keep serialized Settings compatible with the stable preference values and future policies. */
internal object ErrorReconnectPolicySerializer : KSerializer<ErrorReconnectPolicy> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ErrorReconnectPolicy", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ErrorReconnectPolicy) {
        encoder.encodeString(value.persistedValue)
    }

    override fun deserialize(decoder: Decoder): ErrorReconnectPolicy =
        ErrorReconnectPolicy.fromPersistedValue(decoder.decodeString())
}
