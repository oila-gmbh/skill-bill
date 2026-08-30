@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.ValidationMessage

fun featureTaskRuntimePhaseOutputDottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

fun extractFeatureTaskRuntimePhaseOutputOffendingValue(instance: JsonNode, instanceLocation: String): String {
  val dotted = featureTaskRuntimePhaseOutputDottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  var node: JsonNode = instance
  dotted.split('.').forEach { rawSegment ->
    if (rawSegment.isBlank()) return@forEach
    val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
    when {
      arrayMatch != null -> {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) {
          node = node.path(keyPart)
        }
        node = node.path(indexPart.toInt())
      }
      node.isArray && rawSegment.toIntOrNull() != null -> {
        node = node.path(rawSegment.toInt())
      }
      else -> {
        node = node.path(rawSegment)
      }
    }
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}

internal fun buildSchemaDriftLog(sourceLabel: String, errors: Set<ValidationMessage>): String {
  val parts = errors.sortedWith(featureTaskRuntimePhaseOutputViolationOrdering).take(2).map { error ->
    val location = error.instanceLocation?.toString().orEmpty()
    val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(location).ifBlank { "<root>" }
    val constraint = error.message.orEmpty().trim()
    if (constraint.isNotEmpty()) "$fieldPath: $constraint" else fieldPath
  }
  return "Feature-task-runtime phase output failed schema validation: source='$sourceLabel' " +
    "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
}

internal data class PhaseOutputViolationReasons(val valueBearing: String, val payloadFree: String)

internal fun formatViolationReasons(
  sorted: List<ValidationMessage>,
  instance: JsonNode,
): PhaseOutputViolationReasons {
  val violations = sorted.map { error ->
    val location = error.instanceLocation?.toString().orEmpty()
    val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(location).ifBlank { "<root>" }
    val head = "$fieldPath: ${error.message}"
    head to extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, location)
  }
  fun render(includeOffendingValues: Boolean): String =
    violations.joinToString(separator = " | ") { (head, offendingValue) ->
      if (includeOffendingValues && offendingValue.isNotBlank()) {
        "$head — offending value: $offendingValue"
      } else {
        head
      }
    }
  return PhaseOutputViolationReasons(valueBearing = render(true), payloadFree = render(false))
}

internal val featureTaskRuntimePhaseOutputViolationOrdering: Comparator<ValidationMessage> = compareBy(
  { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
  { it.instanceLocation?.toString().orEmpty() },
  { it.message.orEmpty() },
)
