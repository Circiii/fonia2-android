package tech.fonia.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Free-form JSON kept in a column, for values whose shape belongs to another layer
 * (attempt detail, talk metrics, sibilant calibration). GRDB stores it as JSON text, and the
 * export nests it as real JSON rather than a string inside a string.
 *
 * Swift's `Codable` conformance becomes a kotlinx.serialization serializer, so another layer can turn its own
 * types into a [JSONObject] through JSON, as the iOS app does with `TalkerCalibration`.
 */
@Serializable(with = JSONValueSerializer::class)
sealed interface JSONValue {
    data object Null : JSONValue

    data class Bool(val value: Boolean) : JSONValue

    /** Every JSON number, whole or not, is a `Double`, as in Swift. */
    data class Number(val value: Double) : JSONValue

    data class String(val value: kotlin.String) : JSONValue

    data class Array(val value: List<JSONValue>) : JSONValue

    data class Object(val value: Map<kotlin.String, JSONValue>) : JSONValue
}

typealias JSONObject = Map<String, JSONValue>

/**
 * Decoding tries, in order, null, `Bool`, `Double`, `String`, an array and an object, so `true` stays a boolean
 * and `1` a number.
 */
internal fun JsonElement.toJSONValue(): JSONValue = when (this) {
    JsonNull -> JSONValue.Null
    is JsonPrimitive -> when {
        isString -> JSONValue.String(content)
        booleanOrNull != null -> JSONValue.Bool(booleanOrNull!!)
        else -> JSONValue.Number(
            content.toDoubleOrNull() ?: throw SerializationException("Not a JSON number: $content"),
        )
    }
    is JsonArray -> JSONValue.Array(map { it.toJSONValue() })
    is JsonObject -> JSONValue.Object(mapValues { it.value.toJSONValue() })
}

internal fun JSONValue.toJsonElement(): JsonElement = when (this) {
    JSONValue.Null -> JsonNull
    is JSONValue.Bool -> JsonPrimitive(value)
    is JSONValue.Number -> JsonPrimitive(value)
    is JSONValue.String -> JsonPrimitive(value)
    is JSONValue.Array -> JsonArray(value.map { it.toJsonElement() })
    is JSONValue.Object -> JsonObject(value.mapValues { it.value.toJsonElement() })
}

/** The value as `JSONEncoder` writes it: numbers without ".0", keys sorted when written. */
internal fun JSONValue.toNode(): JsonNode = when (this) {
    JSONValue.Null -> JNull
    is JSONValue.Bool -> JBool(value)
    is JSONValue.Number -> jsonNumber(value)
    is JSONValue.String -> JText(value)
    is JSONValue.Array -> JArray(value.map { it.toNode() })
    is JSONValue.Object -> JObject(value.mapValues { it.value.toNode() })
}

internal fun JSONObject.toNode(): JsonNode = JObject(mapValues { it.value.toNode() })

/** A JSON object, as a `[String: JSONValue]` decodes; anything else is a type mismatch. */
internal fun JsonElement.toJSONObject(): JSONObject {
    val value = toJSONValue()
    if (value !is JSONValue.Object) throw SerializationException("Expected a JSON object")
    return value.value
}

/** A JSON array, as a `[JSONValue]` decodes. */
internal fun JsonElement.toJSONArray(): List<JSONValue> {
    val value = toJSONValue()
    if (value !is JSONValue.Array) throw SerializationException("Expected a JSON array")
    return value.value
}

internal object JSONValueSerializer : KSerializer<JSONValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JSONValue) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSONValue can only be written as JSON")
        json.encodeJsonElement(value.toJsonElement())
    }

    override fun deserialize(decoder: Decoder): JSONValue {
        val json = decoder as? JsonDecoder ?: throw SerializationException("JSONValue can only be read from JSON")
        return json.decodeJsonElement().toJSONValue()
    }
}
