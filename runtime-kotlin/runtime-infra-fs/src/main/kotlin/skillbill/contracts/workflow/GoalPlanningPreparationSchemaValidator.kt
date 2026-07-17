@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val goalPlanningPreparationLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.GoalPlanningPreparationSchemaValidator")

object GoalPlanningPreparationSchemaValidator {
  private val schema: JsonSchema by lazy { loadGoalPlanningPreparationSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(envelope: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(envelope)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isNotEmpty()) {
      val sorted = errors.sortedWith(violationOrdering)
      goalPlanningPreparationLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, sorted, instance))
      throw InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = sourceLabel,
        fieldPath = dottedFieldPath(sorted.first().instanceLocation?.toString().orEmpty()),
        reason = formatValidationReason(sorted, instance),
      )
    }
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE,
        fieldPath = "\$id",
        reason = "Canonical goal planning preparation schema identity mismatch: loaded '\$id' is " +
          "'$loadedId' but expected '${GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or " +
          "shadowed copy of the schema is on the classpath.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != GOAL_PLANNING_PREPARATION_CONTRACT_VERSION) {
      throw InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE,
        fieldPath = "properties.contract_version.const",
        reason = "Canonical goal planning preparation schema contract_version.const mismatch: loaded " +
          "'$loadedConst' but the runtime expects '$GOAL_PLANNING_PREPARATION_CONTRACT_VERSION'. The schema " +
          "on the classpath is out of date relative to the running runtime-contracts.",
      )
    }
  }

  private fun buildSchemaDriftLog(sourceLabel: String, sorted: List<ValidationMessage>, instance: JsonNode): String {
    val parts = sorted.take(2).map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = dottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = offendingValue(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
    return "Goal planning preparation failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${sorted.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String {
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val offendingValue = offendingValue(instance, instanceLocation)
    return buildString {
      append(firstError.message)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherValue = offendingValue(instance, otherLocation)
        append(" | ")
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

  private fun offendingValue(instance: JsonNode, instanceLocation: String): String {
    val dotted = dottedFieldPath(instanceLocation)
    if (dotted.isBlank()) return ""
    var node: JsonNode = instance
    dotted.split('.').forEach { segment ->
      if (segment.isBlank()) return@forEach
      node = node.path(segment)
    }
    return when {
      node.isMissingNode -> ""
      node.isValueNode -> node.asText()
      else -> ""
    }
  }

  private fun dottedFieldPath(instanceLocation: String): String = when {
    instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
    instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
    instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
    else -> instanceLocation.trimStart('/').replace('/', '.')
  }
}

internal const val GOAL_PLANNING_PREPARATION_SCHEMA_CLASSPATH_RESOURCE: String =
  GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE

internal const val GOAL_PLANNING_PREPARATION_SCHEMA_REPO_RELATIVE_PATH: String =
  GoalPlanningPreparationSchemaPaths.REPO_RELATIVE_PATH

private fun loadGoalPlanningPreparationSchema(): JsonSchema {
  try {
    val yamlText = readGoalPlanningPreparationSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    GoalPlanningPreparationSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText)
  } catch (error: Throwable) {
    goalPlanningPreparationLog.log(
      Level.SEVERE,
      "Failed to load canonical goal planning preparation schema: " +
        "classpath='$GOAL_PLANNING_PREPARATION_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$GOAL_PLANNING_PREPARATION_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readGoalPlanningPreparationSchemaText(): String {
  GoalPlanningPreparationSchemaValidator::class.java.classLoader
    .getResourceAsStream(GOAL_PLANNING_PREPARATION_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForGoalPlanningPreparationSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidGoalPlanningPreparationSchemaError(
    sourceLabel = GOAL_PLANNING_PREPARATION_SCHEMA_CLASSPATH_RESOURCE,
    fieldPath = "",
    reason = "Canonical goal planning preparation schema is missing. Expected to find it on the JVM " +
      "classpath at '$GOAL_PLANNING_PREPARATION_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$GOAL_PLANNING_PREPARATION_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

private fun walkForGoalPlanningPreparationSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(GOAL_PLANNING_PREPARATION_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
