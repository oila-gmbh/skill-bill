package skillbill.workflow.engine

import skillbill.contracts.JsonCodec
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.MalformedJsonTextError

internal fun decodeSteps(rawValue: String): List<Map<String, Any?>> {
  val parsed = parseDurableJson(rawValue, "stepsJson") as? List<*>
    ?: throw InvalidWorkflowStateSchemaError("Workflow state stepsJson must decode to a JSON array.")
  return parsed.mapIndexed { index, element ->
    JsonCodec.anyToStringAnyMap(element)
      ?: throw InvalidWorkflowStateSchemaError(
        "Workflow state stepsJson[$index] must decode to a JSON object.",
      )
  }
}

internal fun decodeObject(rawValue: String): Map<String, Any?> {
  val parsed = parseDurableJson(rawValue, "artifactsJson")
  return JsonCodec.anyToStringAnyMap(parsed)
    ?: throw InvalidWorkflowStateSchemaError("Workflow state artifactsJson must decode to a JSON object.")
}

internal fun parseDurableJson(rawValue: String, fieldName: String): Any? = try {
  JsonCodec.parseValue(rawValue)
} catch (error: MalformedJsonTextError) {
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
