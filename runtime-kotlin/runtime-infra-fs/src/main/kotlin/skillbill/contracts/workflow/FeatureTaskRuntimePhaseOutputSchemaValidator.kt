@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val featureTaskRuntimePhaseOutputLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaValidator")

/**
 * Schema validator for feature-task-runtime per-phase output payloads, reached
 * by the runtime only through the domain-owned port. Empty `{}`, malformed
 * input, and any schema violation fail with
 * [InvalidFeatureTaskRuntimePhaseOutputSchemaError].
 */
object FeatureTaskRuntimePhaseOutputSchemaValidator {
  private val schema: JsonSchema by lazy { loadFeatureTaskRuntimePhaseOutputSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }
  private val mapType = object : TypeReference<Map<String, Any?>>() {}

  fun validate(phaseOutput: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(phaseOutput)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isEmpty()) {
      return
    }
    featureTaskRuntimePhaseOutputLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
      sourceLabel = sourceLabel,
      reason = formatValidationReason(errors.sortedWith(violationOrdering), instance),
    )
  }

  fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
  }

  private fun readPhaseOutputObjectNode(phaseOutputText: String, sourceLabel: String): JsonNode {
    val node =
      try {
        yamlMapper.readTree(phaseOutputText)
      } catch (error: JsonProcessingException) {
        throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "Phase output is malformed: ${error.originalMessage.orEmpty()}",
          cause = error,
        )
      }
    if (node == null || !node.isObject) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "<root> must be an object.",
      )
    }
    return node
  }

  private fun phaseOutputObjectNodeToMap(node: JsonNode, sourceLabel: String): Map<String, Any?> = try {
    mapper.convertValue(node, mapType)
  } catch (error: IllegalArgumentException) {
    throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
      sourceLabel = sourceLabel,
      reason = "Phase output root object cannot be converted to a string-keyed map: ${error.message.orEmpty()}",
      cause = error,
    )
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical feature-task-runtime phase output schema identity mismatch: loaded '\$id' is " +
          "'$loadedId' but expected '${FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or " +
          "shadowed copy of the schema is on the classpath.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != FEATURE_TASK_RUNTIME_CONTRACT_VERSION) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical feature-task-runtime phase output schema contract_version.const mismatch: loaded " +
          "'$loadedConst' but the runtime expects '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'. The schema on the " +
          "classpath is out of date relative to the running runtime-contracts.",
      )
    }
  }

  private fun buildSchemaDriftLog(sourceLabel: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
    val parts = errors.sortedWith(violationOrdering).take(2).map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
    return "Feature-task-runtime phase output failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String {
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(instanceLocation).ifBlank { "<root>" }
    val offendingValue = extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, instanceLocation)
    return buildString {
      append(fieldPath)
      append(": ")
      append(firstError.message)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherPath = featureTaskRuntimePhaseOutputDottedFieldPath(otherLocation).ifBlank { "<root>" }
        val otherValue = extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, otherLocation)
        append(" | ")
        append(otherPath)
        append(": ")
        append(other.message)
        if (otherValue.isNotBlank()) {
          append(" — offending value: ")
          append(otherValue)
        }
      }
    }
  }

  private val violationOrdering: Comparator<ValidationMessage> = compareBy(
    { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
    { it.instanceLocation?.toString().orEmpty() },
    { it.message.orEmpty() },
  )
}

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.REPO_RELATIVE_PATH

private fun loadFeatureTaskRuntimePhaseOutputSchema(): JsonSchema {
  try {
    val yamlText = readFeatureTaskRuntimePhaseOutputSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    FeatureTaskRuntimePhaseOutputSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText)
  } catch (error: Throwable) {
    featureTaskRuntimePhaseOutputLog.log(
      Level.SEVERE,
      "Failed to load canonical feature-task-runtime phase output schema: " +
        "classpath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readFeatureTaskRuntimePhaseOutputSchemaText(): String {
  FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
    .getResourceAsStream(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForFeatureTaskRuntimePhaseOutputSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
    sourceLabel = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
    reason = "Canonical feature-task-runtime phase output schema is missing. Expected to find it on the JVM " +
      "classpath at '$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

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

private fun walkForFeatureTaskRuntimePhaseOutputSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
