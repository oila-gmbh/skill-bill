package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.ValidationMessage

internal val workflowStateSchemaViolationOrdering: Comparator<ValidationMessage> = compareBy(
  { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
  { it.instanceLocation?.toString().orEmpty() },
  { it.message.orEmpty() },
)

internal fun buildWorkflowStateSchemaDriftLog(slug: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
  val sorted = errors.sortedWith(workflowStateSchemaViolationOrdering)
  val topTwo = sorted.take(2)
  val parts = topTwo.map { error ->
    val location = error.instanceLocation?.toString().orEmpty()
    val fieldPath = workflowStateSchemaDottedFieldPath(location).ifBlank { "<root>" }
    val offendingValue = extractOffendingValueFromInstance(instance, location)
    if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
  }
  return "Workflow state snapshot failed schema validation: slug='$slug' violations=${parts.joinToString(", ")} " +
    "totalViolations=${errors.size}"
}

internal fun formatWorkflowStateValidationMessage(
  slug: String,
  errors: Set<ValidationMessage>,
  instance: JsonNode,
): String {
  val sorted = errors.sortedWith(workflowStateSchemaViolationOrdering)
  val firstError = sorted.first()
  val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
  val fieldPath = workflowStateSchemaDottedFieldPath(instanceLocation)
  val detail = firstError.message
  val offendingValue = extractOffendingValueFromInstance(instance, instanceLocation)
  return buildString {
    append("Workflow '")
    append(slug)
    append("': snapshot fails schema validation at '")
    append(fieldPath.ifBlank { "<root>" })
    append("': ")
    append(detail)
    if (offendingValue.isNotBlank()) {
      append(" — offending value: ")
      append(offendingValue)
    }
    sorted.drop(1).forEach { other ->
      appendWorkflowStateSecondaryViolation(this, instance, other)
    }
  }
}

private fun appendWorkflowStateSecondaryViolation(
  builder: StringBuilder,
  instance: JsonNode,
  other: ValidationMessage,
) {
  val otherLocation = other.instanceLocation?.toString().orEmpty()
  val otherPath = workflowStateSchemaDottedFieldPath(otherLocation).ifBlank { "<root>" }
  val otherValue = extractOffendingValueFromInstance(instance, otherLocation)
  builder.append(" | ")
  builder.append(otherPath)
  builder.append(": ")
  builder.append(other.message)
  if (otherValue.isNotBlank()) {
    builder.append(" — offending value: ")
    builder.append(otherValue)
  }
}
