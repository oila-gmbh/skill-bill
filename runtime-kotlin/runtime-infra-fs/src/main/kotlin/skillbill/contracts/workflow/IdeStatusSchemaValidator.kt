package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.contracts.logSchemaLoadFailure
import skillbill.error.InvalidIdeStatusSchemaError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val ideStatusLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.IdeStatusSchemaValidator")

object IdeStatusSchemaValidator {
  private val schema: JsonSchema by lazy { loadIdeStatusSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(snapshot: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(snapshot)
    val errors = schema.validate(instance)
    if (errors.isEmpty()) return
    ideStatusLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    val sortedErrors = errors.sortedWith(violationOrdering)
    throw InvalidIdeStatusSchemaError(
      sourceLabel = sourceLabel,
      fieldPath = goalObservabilityDottedFieldPath(
        sortedErrors.first().instanceLocation?.toString().orEmpty(),
      ),
      reason = formatValidationReason(sortedErrors, instance),
    )
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != IdeStatusSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidIdeStatusSchemaError(
        sourceLabel = IdeStatusSchemaPaths.CLASSPATH_RESOURCE,
        fieldPath = "\$id",
        reason = "Canonical IDE status schema identity mismatch: loaded '\$id' is '$loadedId' " +
          "but expected '${IdeStatusSchemaPaths.EXPECTED_SCHEMA_ID}'.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != IDE_STATUS_CONTRACT_VERSION) {
      throw InvalidIdeStatusSchemaError(
        sourceLabel = IdeStatusSchemaPaths.CLASSPATH_RESOURCE,
        fieldPath = "contract_version",
        reason = "Canonical IDE status schema contract_version.const mismatch: loaded " +
          "'$loadedConst' but runtime expects '$IDE_STATUS_CONTRACT_VERSION'.",
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
    return "IDE status failed schema validation: source='$sourceLabel' " +
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

internal const val IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE: String =
  IdeStatusSchemaPaths.CLASSPATH_RESOURCE

internal const val IDE_STATUS_SCHEMA_REPO_RELATIVE_PATH: String =
  IdeStatusSchemaPaths.REPO_RELATIVE_PATH

private fun loadIdeStatusSchema(): JsonSchema {
  var failure: Throwable? = null
  try {
    val yamlText = readIdeStatusSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    IdeStatusSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(jsonText, LOCALE_STABLE_SCHEMA_CONFIG)
  } catch (error: InvalidIdeStatusSchemaError) {
    logSchemaLoadFailure(
      ideStatusLog,
      "IDE status",
      IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
      IDE_STATUS_SCHEMA_REPO_RELATIVE_PATH,
      error,
    )
    failure = error
  } catch (error: IOException) {
    logSchemaLoadFailure(
      ideStatusLog,
      "IDE status",
      IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
      IDE_STATUS_SCHEMA_REPO_RELATIVE_PATH,
      error,
    )
    failure = error
  } catch (error: JsonProcessingException) {
    logSchemaLoadFailure(
      ideStatusLog,
      "IDE status",
      IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
      IDE_STATUS_SCHEMA_REPO_RELATIVE_PATH,
      error,
    )
    failure = error
  }
  throw failure
}

private fun readIdeStatusSchemaText(): String {
  IdeStatusSchemaValidator::class.java.classLoader
    .getResourceAsStream(IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor = Path.of("").toAbsolutePath()
  val resolved = walkForIdeStatusSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidIdeStatusSchemaError(
    sourceLabel = IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
    fieldPath = "",
    reason = "Canonical IDE status schema is missing. Expected classpath resource " +
      "'$IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE' or repo path " +
      "'$IDE_STATUS_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

private fun walkForIdeStatusSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(IDE_STATUS_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) return candidate
    current = current.parent
  }
  return null
}
