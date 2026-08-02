package top.jie65535.mirai.profile

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

object EvidenceReferenceListSerializer : KSerializer<List<Int>> {
    private val delegate = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<Int> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = jsonDecoder.decodeJsonElement() as? JsonArray ?: return listOf(INVALID_REFERENCE)
        return array.map { element ->
            val raw = (element as? JsonPrimitive)?.content?.trim() ?: return@map INVALID_REFERENCE
            val numeric = if (raw.startsWith("e:", ignoreCase = true)) raw.substring(2) else raw
            numeric.toIntOrNull()?.takeIf { it > 0 } ?: INVALID_REFERENCE
        }
    }

    override fun serialize(encoder: Encoder, value: List<Int>) {
        delegate.serialize(encoder, value)
    }

    private const val INVALID_REFERENCE = 0
}
