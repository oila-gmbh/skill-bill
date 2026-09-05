package skillbill.workflow.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonCodec
import skillbill.error.InvalidWorkflowStateSchemaError

internal fun decodeSteps(rawValue: String): List<Map<String, Any?>> {
  val parsed = parseDurableJson(rawValue, "stepsJson")
  if (parsed !is JsonArray) {
    throw InvalidWorkflowStateSchemaError("Workflow state stepsJson must decode to a JSON array.")
  }
  return parsed.mapIndexed { index, element ->
    JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(element))
      ?: throw InvalidWorkflowStateSchemaError(
        "Workflow state stepsJson[$index] must decode to a JSON object.",
      )
  }
}

internal fun decodeObject(rawValue: String): Map<String, Any?> {
  val parsed = parseDurableJson(rawValue, "artifactsJson")
  if (parsed !is JsonObject) {
    throw InvalidWorkflowStateSchemaError("Workflow state artifactsJson must decode to a JSON object.")
  }
  return JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(parsed))
    ?: throw InvalidWorkflowStateSchemaError("Workflow state artifactsJson must decode to a JSON object.")
}

internal fun parseDurableJson(rawValue: String, fieldName: String): JsonElement = try {
  JsonCodec.json.parseToJsonElement(rawValue)
} catch (error: SerializationException) {
  throw InvalidWorkflowStateSchemaError("Workflow state $fieldName contains malformed JSON.", error)
} catch (error: IllegalArgumentException) {
  throw InvalidWorkflowStateSchemaError("Workflow state $fieldName contains malformed JSON.", error)
}

internal fun requiredStringAnyMap(value: Any?, field: String): Map<String, Any?> = JsonCodec.anyToStringAnyMap(value)
  ?: throw InvalidWorkflowStateSchemaError("Workflow state $field must decode to a JSON object.")

internal fun requiredStringAnyMapList(value: Any?, field: String): List<Map<String, Any?>> {
  val list = value as? List<*>
    ?: throw InvalidWorkflowStateSchemaError("Workflow state $field must decode to a JSON array.")
  return list.mapIndexed { index, entry ->
    JsonCodec.anyToStringAnyMap(entry)
      ?: throw InvalidWorkflowStateSchemaError("Workflow state $field[$index] must decode to a JSON object.")
  }
}
