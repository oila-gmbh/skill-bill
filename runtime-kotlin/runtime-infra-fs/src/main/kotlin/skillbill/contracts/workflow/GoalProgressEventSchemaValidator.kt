@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidGoalProgressEventSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val goalProgressLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.GoalProgressEventSchemaValidator")

object GoalProgressEventSchemaValidator {
  private val schema: JsonSchema by lazy { loadGoalProgressEventSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(event: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(event)
    val errors = schema.validate(instance)
    if (errors.isEmpty()) return
    goalProgressLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    val sortedErrors = errors.sortedWith(violationOrdering)
    throw InvalidGoalProgressEventSchemaError(
      sourceLabel = sourceLabel,
      fieldPath = goalObservabilityDottedFieldPath(
        sortedErrors.first().instanceLocation?.toString().orEmpty(),
      ),
      reason = formatValidationReason(sortedErrors, instance),
    )
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != GoalProgressEventSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidGoalProgressEventSchemaError(
        sourceLabel = GoalProgressEventSchemaPaths.CLASSPATH_RESOURCE,
        fieldPath = "\$id",
        reason = "Canonical goal progress event schema identity mismatch: loaded '\$id' is '$loadedId' " +
          "but expected '${GoalProgressEventSchemaPaths.EXPECTED_SCHEMA_ID}'.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != GOAL_PROGRESS_EVENT_CONTRACT_VERSION) {
      throw InvalidGoalProgressEventSchemaError(
        sourceLabel = GoalProgressEventSchemaPaths.CLASSPATH_RESOURCE,
        fieldPath = "contract_version",
        reason = "Canonical goal progress event schema contract_version.const mismatch: loaded " +
          "'$loadedConst' but runtime expects '$GOAL_PROGRESS_EVENT_CONTRACT_VERSION'.",
      )
    }
  }

  private fun buildSchemaDriftLog(sourceLabel: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
    val parts = errors.sortedWith(violationOrdering).take(2).map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = goalObservabilityDottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = extractGoalObservabilityOffendingValue(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
    return "Goal progress event failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String =
    sorted.joinToString(" | ") { error ->
      val instanceLocation = error.instanceLocation?.toString().orEmpty()
      val fieldPath = goalObservabilityDottedFieldPath(instanceLocation).ifBlank { "<root>" }
      val offendingValue = extractGoalObservabilityOffendingValue(instance, instanceLocation)
      buildString {
        append(fieldPath)
        append(": ")
        append(error.message)
        if (offendingValue.isNotBlank()) {
          append(" — offending value: ")
          append(offendingValue)
        }
      }
    }

  private val violationOrdering: Comparator<ValidationMessage> = compareBy(
    { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
    { it.instanceLocation?.toString().orEmpty() },
    { it.message.orEmpty() },
  )
}

internal const val GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE: String =
  GoalProgressEventSchemaPaths.CLASSPATH_RESOURCE

internal const val GOAL_PROGRESS_EVENT_SCHEMA_REPO_RELATIVE_PATH: String =
  GoalProgressEventSchemaPaths.REPO_RELATIVE_PATH

private fun loadGoalProgressEventSchema(): JsonSchema {
  try {
    val yamlText = readGoalProgressEventSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    GoalProgressEventSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(jsonText)
  } catch (error: Throwable) {
    goalProgressLog.log(
      Level.SEVERE,
      "Failed to load canonical goal progress event schema: " +
        "classpath='$GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$GOAL_PROGRESS_EVENT_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readGoalProgressEventSchemaText(): String {
  GoalProgressEventSchemaValidator::class.java.classLoader
    .getResourceAsStream(GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor = Path.of("").toAbsolutePath()
  val resolved = walkForGoalProgressEventSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidGoalProgressEventSchemaError(
    sourceLabel = GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE,
    fieldPath = "",
    reason = "Canonical goal progress event schema is missing. Expected classpath resource " +
      "'$GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE' or repo path " +
      "'$GOAL_PROGRESS_EVENT_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

private fun walkForGoalProgressEventSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(GOAL_PROGRESS_EVENT_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) return candidate
    current = current.parent
  }
  return null
}
