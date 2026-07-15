package com.gofilm.app.data.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * 兼容后端 nav 既可能是对象 {id,name,...}，也可能是数组（少数接口）。
 */
object NavSerializer : KSerializer<CategoryItem?> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("NavCategory")

    override fun deserialize(decoder: Decoder): CategoryItem? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(CategoryItem.serializer())
        return when (val el = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonObject -> jsonDecoder.json.decodeFromJsonElement(CategoryItem.serializer(), el)
            is JsonArray -> {
                val first = el.firstOrNull()
                if (first is JsonObject) {
                    jsonDecoder.json.decodeFromJsonElement(CategoryItem.serializer(), first)
                } else null
            }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: CategoryItem?) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder == null) {
            if (value != null) encoder.encodeSerializableValue(CategoryItem.serializer(), value)
            return
        }
        if (value == null) {
            jsonEncoder.encodeJsonElement(JsonNull)
        } else {
            jsonEncoder.encodeJsonElement(
                jsonEncoder.json.encodeToJsonElement(CategoryItem.serializer(), value)
            )
        }
    }
}
