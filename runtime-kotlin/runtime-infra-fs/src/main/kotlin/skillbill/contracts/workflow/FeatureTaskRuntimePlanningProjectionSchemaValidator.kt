@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import java.nio.file.Files
import java.nio.file.Path

/**
 * Draft 2020-12 validator for the implementation receipt planning projection.
 * and forwards; any violation fails with [InvalidFeatureTaskRuntimePlanningProjectionSchemaError], the
 * message carrying schema locations, never projection bodies.
 */
object FeatureTaskRuntimePlanningProjectionSchemaValidator {
  private val schemaDocument: JsonNode by lazy { loadPlanningProjectionsSchemaDocument() }
  private val schema: JsonSchema by lazy { compile(schemaDocument) }
  private val variantSchemas: Map<String, JsonSchema> by lazy { compileVariants(schemaDocument) }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(payload: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(payload)
    // Validating a mismatched payload against the whole `oneOf` reports every violation of all four
    // variants at once, burying the actual offending field under two dozen irrelevant ones. When the
    // payload declares a known kind, check that variant directly so the message names the real cause;
    // an unknown or absent kind still falls back to the full family.
    val declaredKind = payload["projection_kind"] as? String
    val effective = variantSchemas[declaredKind] ?: schema
    val errors: Set<ValidationMessage> = effective.validate(instance)
    if (errors.isNotEmpty()) {
      throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = sourceLabel,
        reason = formatReason(errors),
      )
    }
  }

  private fun compileVariants(document: JsonNode): Map<String, JsonSchema> =
    PROJECTION_VARIANT_DEFS.associateWith { variant ->
      val wrapper = document.deepCopy<ObjectNode>()
      wrapper.remove("oneOf")
      wrapper.put("\$ref", "#/\$defs/$variant")
      compile(wrapper)
    }

  private fun compile(document: JsonNode): JsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    .getSchema(ObjectMapper().writeValueAsString(document), LOCALE_STABLE_SCHEMA_CONFIG)

  private val PROJECTION_VARIANT_DEFS: Set<String> = emptySet()

  private fun formatReason(errors: Set<ValidationMessage>): String = errors.sortedBy { instanceLocationOf(it) }
    .take(MAX_REPORTED_VIOLATIONS)
    .joinToString(separator = " | ") { locatedMessage(it) } +
    if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""

  // Every networknt message template renders the instance location as its leading argument, so prefixing
  // the location again would report each violated location twice. Only an absent or blank location, which
  // renders as an empty leading argument, needs the explicit root marker.
  private fun locatedMessage(error: ValidationMessage): String {
    val message = error.message.orEmpty()
    if (instanceLocationOf(error).isNotBlank()) return message
    return "$ROOT_INSTANCE_LOCATION: ${message.removePrefix(":").trim()}"
  }

  private fun instanceLocationOf(error: ValidationMessage): String = error.instanceLocation?.toString().orEmpty()

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    require(loadedId == FeatureTaskRuntimePlanningProjectionsSchemaPaths.EXPECTED_SCHEMA_ID) {
      "Canonical feature-task-runtime planning-projections schema identity mismatch: loaded '$loadedId' but expected " +
        "'${FeatureTaskRuntimePlanningProjectionsSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy of the " +
        "schema is on the classpath."
    }
    val loadedConst = yamlNode.path("\$defs").path("contractVersion").path("const").asText("")
    require(loadedConst == FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION) {
      "Canonical feature-task-runtime planning-projections schema contractVersion.const mismatch: loaded " +
        "'$loadedConst' but the runtime expects '$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION'."
    }
  }

  private const val MAX_REPORTED_VIOLATIONS: Int = 3

  private const val ROOT_INSTANCE_LOCATION: String = "<root>"
}

private fun loadPlanningProjectionsSchemaDocument(): JsonNode = try {
  val yamlNode = YAMLMapper().readTree(readPlanningProjectionsSchemaText())
  FeatureTaskRuntimePlanningProjectionSchemaValidator.assertIdentity(yamlNode)
  yamlNode
} catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
  throw error
} catch (error: Exception) {
  throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
    sourceLabel = FeatureTaskRuntimePlanningProjectionsSchemaPaths.CLASSPATH_RESOURCE,
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )
}

private fun readPlanningProjectionsSchemaText(): String {
  FeatureTaskRuntimePlanningProjectionSchemaValidator::class.java.classLoader
    .getResourceAsStream(FeatureTaskRuntimePlanningProjectionsSchemaPaths.CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  var current: Path? = walkAnchor.normalize()
  while (current != null) {
    val candidate = current.resolve(FeatureTaskRuntimePlanningProjectionsSchemaPaths.REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) return Files.readString(candidate)
    current = current.parent
  }
  throw IllegalStateException(
    "Canonical feature-task-runtime planning-projections schema is missing. Expected it on the JVM classpath at " +
      "'${FeatureTaskRuntimePlanningProjectionsSchemaPaths.CLASSPATH_RESOURCE}' or on disk under " +
      "'${FeatureTaskRuntimePlanningProjectionsSchemaPaths.REPO_RELATIVE_PATH}' walked up from: $walkAnchor.",
  )
}
