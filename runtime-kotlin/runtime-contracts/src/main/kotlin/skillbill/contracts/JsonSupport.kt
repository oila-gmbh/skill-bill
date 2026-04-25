package skillbill.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object JsonSupport {
  val json: Json =
    Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

  fun parseObjectOrNull(rawValue: String): JsonObject? {
    val parsed =
      try {
        json.parseToJsonElement(rawValue)
      } catch (_: Exception) {
        return null
      }
    return parsed as? JsonObject
  }

  fun parseArrayOrEmpty(rawValue: String): List<Any?> {
    val parsed =
      try {
        json.parseToJsonElement(rawValue)
      } catch (_: Exception) {
        return emptyList()
      }
    return if (parsed is JsonArray) {
      parsed.map(::jsonElementToValue)
    } else {
      emptyList()
    }
  }

  fun jsonElementToValue(element: JsonElement): Any? = when (element) {
    JsonNull -> null
    is JsonObject -> element.entries.associate { (key, value) -> key to jsonElementToValue(value) }
    is JsonArray -> element.map(::jsonElementToValue)
    is JsonPrimitive -> primitiveToValue(element)
  }

  fun anyToStringAnyMap(value: Any?): Map<String, Any?>? =
    (value as? Map<*, *>)?.entries?.filter { it.key is String }?.associate { it.key as String to it.value }

  fun mapToJsonString(map: Map<String, Any?>): String =
    json.encodeToString(JsonObject.serializer(), mapToJsonObject(map))

  fun mapToJsonObject(map: Map<String, Any?>): JsonObject = buildJsonObject {
    map.forEach { (key, value) ->
      put(key, valueToJsonElement(value))
    }
  }

  fun valueToJsonElement(value: Any?): JsonElement = primitiveJsonElement(value)
    ?: collectionJsonElement(value)
    ?: JsonPrimitive(value.toString())

  private fun primitiveToValue(primitive: JsonPrimitive): Any? = if (primitive.isJsonString()) {
    primitive.contentOrNull
  } else {
    listOfNotNull(
      primitive.booleanOrNull,
      primitive.intOrNull,
      primitive.longOrNull,
      primitive.doubleOrNull,
      primitive.contentOrNull,
    ).firstOrNull()
  }

  private fun primitiveJsonElement(value: Any?): JsonElement? = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value.toDouble())
    else -> null
  }
}

private fun JsonPrimitive.isJsonString(): Boolean = toString().startsWith("\"")

private fun JsonSupport.collectionJsonElement(value: Any?): JsonElement? =
  mapJsonElement(value) ?: iterableJsonElement(value) ?: arrayJsonElement(value)

private fun JsonSupport.mapJsonElement(value: Any?): JsonElement? = (value as? Map<*, *>)?.let { entries ->
  buildJsonObject {
    entries.forEach { (entryKey, entryValue) ->
      if (entryKey is String) {
        put(entryKey, valueToJsonElement(entryValue))
      }
    }
  }
}

private fun JsonSupport.iterableJsonElement(value: Any?): JsonElement? = (value as? Iterable<*>)?.let { entries ->
  buildJsonArray {
    entries.forEach { add(valueToJsonElement(it)) }
  }
}

private fun JsonSupport.arrayJsonElement(value: Any?): JsonElement? = (value as? Array<*>)?.let { entries ->
  buildJsonArray {
    entries.forEach { add(valueToJsonElement(it)) }
  }
}
