@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.team

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidTeamBundleSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val teamBundleLog: Logger = Logger.getLogger("skillbill.contracts.team.TeamBundleSchemaValidator")

object TeamBundleSchemaValidator {
  private val schema: JsonSchema by lazy { loadTeamBundleSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }
  private val yamlMapper: YAMLMapper by lazy { YAMLMapper() }
  private val mapType = object : TypeReference<Map<String, Any?>>() {}

  fun validate(bundle: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(bundle)
    val errors = schema.validate(instance)
    if (errors.isEmpty()) {
      return
    }
    teamBundleLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    val violation = firstViolation(errors.sortedWith(violationOrdering), instance)
    throw InvalidTeamBundleSchemaError(sourceLabel, violation.fieldPath, violation.reason)
  }

  fun validateYamlText(yamlText: String, sourceLabel: String): Map<String, Any?> {
    val node = readYamlObjectNode(yamlText, sourceLabel)
    val parsed = yamlObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
    return parsed
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != TeamBundleSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidTeamBundleSchemaError(
        sourceLabel = TeamBundleSchemaPaths.CLASSPATH_RESOURCE,
        fieldPath = "\$id",
        reason = "Canonical team-bundle schema identity mismatch: loaded '\$id' is '$loadedId' but " +
          "expected '${TeamBundleSchemaPaths.EXPECTED_SCHEMA_ID}'.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != TEAM_BUNDLE_CONTRACT_VERSION) {
      throw InvalidTeamBundleSchemaError(
        sourceLabel = TeamBundleSchemaPaths.CLASSPATH_RESOURCE,
        fieldPath = "properties.contract_version.const",
        reason = "Canonical team-bundle schema contract_version.const mismatch: loaded '$loadedConst' " +
          "but the runtime expects '$TEAM_BUNDLE_CONTRACT_VERSION'.",
      )
    }
  }

  private fun readYamlObjectNode(yamlText: String, sourceLabel: String): JsonNode {
    val node =
      try {
        yamlMapper.readTree(yamlText)
      } catch (error: JsonProcessingException) {
        throw InvalidTeamBundleSchemaError(
          sourceLabel = sourceLabel,
          fieldPath = "<root>",
          reason = "YAML is malformed: ${error.originalMessage.orEmpty()}",
          cause = error,
        )
      }
    if (node == null || !node.isObject) {
      throw InvalidTeamBundleSchemaError(sourceLabel, "<root>", "<root> must be an object.")
    }
    return node
  }

  private fun yamlObjectNodeToMap(node: JsonNode, sourceLabel: String): Map<String, Any?> = try {
    mapper.convertValue(node, mapType)
  } catch (error: IllegalArgumentException) {
    throw InvalidTeamBundleSchemaError(
      sourceLabel = sourceLabel,
      fieldPath = "<root>",
      reason = "YAML root object cannot be converted to a string-keyed map: ${error.message.orEmpty()}",
      cause = error,
    )
  }
}

private data class TeamBundleViolation(val fieldPath: String, val reason: String)

private fun buildSchemaDriftLog(sourceLabel: String, errors: Set<ValidationMessage>, instance: JsonNode): String {
  val parts = errors.sortedWith(violationOrdering).take(2).map { error ->
    val location = error.instanceLocation?.toString().orEmpty()
    val fieldPath = teamBundleDottedFieldPath(location).ifBlank { "<root>" }
    val offendingValue = extractTeamBundleOffendingValue(instance, location)
    if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
  }
  return "Team bundle failed schema validation: source='$sourceLabel' " +
    "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
}

private fun firstViolation(sorted: List<ValidationMessage>, instance: JsonNode): TeamBundleViolation {
  val firstError = sorted.first()
  val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
  val fieldPath = teamBundleDottedFieldPath(instanceLocation).ifBlank { "<root>" }
  val offendingValue = extractTeamBundleOffendingValue(instance, instanceLocation)
  val reason = buildString {
    append(firstError.message)
    if (offendingValue.isNotBlank()) {
      append(" — offending value: ")
      append(offendingValue)
    }
  }
  return TeamBundleViolation(fieldPath, reason)
}

private val violationOrdering: Comparator<ValidationMessage> = compareBy(
  { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
  { it.instanceLocation?.toString().orEmpty() },
  { it.message.orEmpty() },
)

private fun loadTeamBundleSchema(): JsonSchema {
  try {
    val yamlText = readTeamBundleSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    TeamBundleSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(jsonText)
  } catch (error: Throwable) {
    teamBundleLog.log(
      Level.SEVERE,
      "Failed to load canonical team-bundle schema: classpath='${TeamBundleSchemaPaths.CLASSPATH_RESOURCE}' " +
        "repoRelativePath='${TeamBundleSchemaPaths.REPO_RELATIVE_PATH}' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readTeamBundleSchemaText(): String {
  TeamBundleSchemaValidator::class.java.classLoader
    .getResourceAsStream(TeamBundleSchemaPaths.CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor = Path.of("").toAbsolutePath()
  val resolved = walkForTeamBundleSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidTeamBundleSchemaError(
    sourceLabel = TeamBundleSchemaPaths.CLASSPATH_RESOURCE,
    fieldPath = "<root>",
    reason = "Canonical team-bundle schema is missing. Expected to find it on the JVM classpath at " +
      "'${TeamBundleSchemaPaths.CLASSPATH_RESOURCE}' or on disk under '${TeamBundleSchemaPaths.REPO_RELATIVE_PATH}'.",
  )
}

fun teamBundleDottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

fun extractTeamBundleOffendingValue(instance: JsonNode, instanceLocation: String): String {
  val dotted = teamBundleDottedFieldPath(instanceLocation)
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
      node.isArray && rawSegment.toIntOrNull() != null -> node = node.path(rawSegment.toInt())
      else -> node = node.path(rawSegment)
    }
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}

private fun walkForTeamBundleSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(TeamBundleSchemaPaths.REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
