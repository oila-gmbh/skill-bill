@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.error.InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError
import java.nio.file.Files
import java.nio.file.Path

/**
 * Draft 2020-12 validator for the append-only checkpoint-identity store. Any violation fails with
 * [InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError], the message carrying the offending
 * instance location only — never a path inventory, a commit message, or a diff body.
 */
object FeatureTaskRuntimeCheckpointIdentitySchemaValidator {
  private val schemaDocument: JsonNode by lazy { loadCheckpointIdentitySchemaDocument() }
  private val schema: JsonSchema by lazy { compile(schemaDocument) }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(payload: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(payload)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isNotEmpty()) {
      throw InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError(
        sourceLabel = sourceLabel,
        reason = formatReason(errors),
      )
    }
  }

  private fun compile(document: JsonNode): JsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    .getSchema(ObjectMapper().writeValueAsString(document), LOCALE_STABLE_SCHEMA_CONFIG)

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(separator = " | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    require(loadedId == FeatureTaskRuntimeCheckpointIdentitySchemaPaths.EXPECTED_SCHEMA_ID) {
      "Canonical feature-task-runtime checkpoint-identity schema identity mismatch: loaded '$loadedId' but " +
        "expected '${FeatureTaskRuntimeCheckpointIdentitySchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or " +
        "shadowed copy of the schema is on the classpath."
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    require(loadedConst == FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION) {
      "Canonical feature-task-runtime checkpoint-identity schema contract_version.const mismatch: loaded " +
        "'$loadedConst' but the runtime expects " +
        "'$FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION'."
    }
  }

  private const val MAX_REPORTED_VIOLATIONS: Int = 3
}

private fun loadCheckpointIdentitySchemaDocument(): JsonNode = try {
  val yamlNode = YAMLMapper().readTree(readCheckpointIdentitySchemaText())
  FeatureTaskRuntimeCheckpointIdentitySchemaValidator.assertIdentity(yamlNode)
  yamlNode
} catch (error: InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError) {
  throw error
} catch (error: Exception) {
  throw InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError(
    sourceLabel = FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE,
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )
}

private fun readCheckpointIdentitySchemaText(): String {
  FeatureTaskRuntimeCheckpointIdentitySchemaValidator::class.java.classLoader
    .getResourceAsStream(FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  var current: Path? = walkAnchor.normalize()
  while (current != null) {
    val candidate = current.resolve(FeatureTaskRuntimeCheckpointIdentitySchemaPaths.REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) return Files.readString(candidate)
    current = current.parent
  }
  throw IllegalStateException(
    "Canonical feature-task-runtime checkpoint-identity schema is missing. Expected it on the JVM " +
      "classpath at '${FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE}' or on disk " +
      "under '${FeatureTaskRuntimeCheckpointIdentitySchemaPaths.REPO_RELATIVE_PATH}' walked up from: " +
      "$walkAnchor.",
  )
}
