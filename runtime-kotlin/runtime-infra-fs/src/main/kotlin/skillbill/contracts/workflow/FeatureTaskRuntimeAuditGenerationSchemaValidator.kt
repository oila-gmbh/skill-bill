@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidFeatureTaskRuntimeAuditGenerationSchemaError
import java.nio.file.Files
import java.nio.file.Path

/**
 * Draft 2020-12 validator for one append-only audit generation. Any violation fails with
 * [InvalidFeatureTaskRuntimeAuditGenerationSchemaError], the message carrying the offending instance
 * location, never gap text or evidence bodies.
 */
object FeatureTaskRuntimeAuditGenerationSchemaValidator {
  private val schemaDocument: JsonNode by lazy { loadAuditGenerationSchemaDocument() }
  private val schema: JsonSchema by lazy { compile(schemaDocument) }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(payload: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(payload)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isNotEmpty()) {
      throw InvalidFeatureTaskRuntimeAuditGenerationSchemaError(
        sourceLabel = sourceLabel,
        reason = formatReason(errors),
      )
    }
  }

  private fun compile(document: JsonNode): JsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    .getSchema(ObjectMapper().writeValueAsString(document))

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(separator = " | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    require(loadedId == FeatureTaskRuntimeAuditGenerationSchemaPaths.EXPECTED_SCHEMA_ID) {
      "Canonical feature-task-runtime audit-generation schema identity mismatch: loaded '$loadedId' but " +
        "expected '${FeatureTaskRuntimeAuditGenerationSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed " +
        "copy of the schema is on the classpath."
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    require(loadedConst == FEATURE_TASK_RUNTIME_AUDIT_GENERATION_CONTRACT_VERSION) {
      "Canonical feature-task-runtime audit-generation schema contract_version.const mismatch: loaded " +
        "'$loadedConst' but the runtime expects '$FEATURE_TASK_RUNTIME_AUDIT_GENERATION_CONTRACT_VERSION'."
    }
  }

  private const val MAX_REPORTED_VIOLATIONS: Int = 3
}

private fun loadAuditGenerationSchemaDocument(): JsonNode = try {
  val yamlNode = YAMLMapper().readTree(readAuditGenerationSchemaText())
  FeatureTaskRuntimeAuditGenerationSchemaValidator.assertIdentity(yamlNode)
  yamlNode
} catch (error: InvalidFeatureTaskRuntimeAuditGenerationSchemaError) {
  throw error
} catch (error: Exception) {
  throw InvalidFeatureTaskRuntimeAuditGenerationSchemaError(
    sourceLabel = FeatureTaskRuntimeAuditGenerationSchemaPaths.CLASSPATH_RESOURCE,
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )
}

private fun readAuditGenerationSchemaText(): String {
  FeatureTaskRuntimeAuditGenerationSchemaValidator::class.java.classLoader
    .getResourceAsStream(FeatureTaskRuntimeAuditGenerationSchemaPaths.CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  var current: Path? = walkAnchor.normalize()
  while (current != null) {
    val candidate = current.resolve(FeatureTaskRuntimeAuditGenerationSchemaPaths.REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) return Files.readString(candidate)
    current = current.parent
  }
  throw IllegalStateException(
    "Canonical feature-task-runtime audit-generation schema is missing. Expected it on the JVM classpath " +
      "at '${FeatureTaskRuntimeAuditGenerationSchemaPaths.CLASSPATH_RESOURCE}' or on disk under " +
      "'${FeatureTaskRuntimeAuditGenerationSchemaPaths.REPO_RELATIVE_PATH}' walked up from: $walkAnchor.",
  )
}
