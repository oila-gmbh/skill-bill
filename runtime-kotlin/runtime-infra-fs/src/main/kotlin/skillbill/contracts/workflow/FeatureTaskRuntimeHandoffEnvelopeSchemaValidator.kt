@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import java.nio.file.Files
import java.nio.file.Path

/**
 * Draft 2020-12 validator for the delivered handoff envelope, reached by the runtime only through
 * the domain-owned port. Any violation fails with [InvalidFeatureTaskRuntimeHandoffProjectionError];
 * the message carries schema locations, never projection bodies.
 */
object FeatureTaskRuntimeHandoffEnvelopeSchemaValidator {
  private val schema: JsonSchema by lazy { loadHandoffEnvelopeSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(envelope: Map<String, Any?>, workflowId: String? = null) {
    val consumerPhaseId = envelope["consumer_phase_id"] as? String ?: ""
    val instance: JsonNode = mapper.valueToTree(envelope)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isNotEmpty()) {
      throw InvalidFeatureTaskRuntimeHandoffProjectionError(
        workflowId = workflowId,
        consumerPhaseId = consumerPhaseId,
        projectionName = firstProjectionLocation(errors),
        projectionContractId = FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.EXPECTED_SCHEMA_ID,
        projectionContractVersion = FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
        failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID,
        reason = formatReason(errors),
      )
    }
  }

  // Schema locations only; a violated instance path names where the drift is without quoting values.
  private fun firstProjectionLocation(errors: Set<ValidationMessage>): String =
    errors.minByOrNull { it.instanceLocation?.toString().orEmpty() }
      ?.instanceLocation?.toString()?.ifBlank { "<root>" }
      ?: "<root>"

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(separator = " | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    require(loadedId == FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.EXPECTED_SCHEMA_ID) {
      "Canonical feature-task-runtime handoff envelope schema identity mismatch: loaded '$loadedId' but expected " +
        "'${FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy of the " +
        "schema is on the classpath."
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    require(loadedConst == FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION) {
      "Canonical feature-task-runtime handoff envelope schema contract_version.const mismatch: loaded " +
        "'$loadedConst' but the runtime expects '$FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION'."
    }
  }

  private const val MAX_REPORTED_VIOLATIONS: Int = 3
}

private fun loadHandoffEnvelopeSchema(): JsonSchema = try {
  val yamlNode = YAMLMapper().readTree(readHandoffEnvelopeSchemaText())
  FeatureTaskRuntimeHandoffEnvelopeSchemaValidator.assertIdentity(yamlNode)
  JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    .getSchema(ObjectMapper().writeValueAsString(yamlNode))
} catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
  throw error
} catch (error: Exception) {
  throw InvalidFeatureTaskRuntimeHandoffProjectionError(
    workflowId = null,
    consumerPhaseId = "<schema-load>",
    projectionName = FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.CLASSPATH_RESOURCE,
    projectionContractId = FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.EXPECTED_SCHEMA_ID,
    projectionContractVersion = FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
    failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID,
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )
}

private fun readHandoffEnvelopeSchemaText(): String {
  FeatureTaskRuntimeHandoffEnvelopeSchemaValidator::class.java.classLoader
    .getResourceAsStream(FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  var current: Path? = walkAnchor.normalize()
  while (current != null) {
    val candidate = current.resolve(FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) return Files.readString(candidate)
    current = current.parent
  }
  throw IllegalStateException(
    "Canonical feature-task-runtime handoff envelope schema is missing. Expected it on the JVM classpath at " +
      "'${FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.CLASSPATH_RESOURCE}' or on disk under " +
      "'${FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.REPO_RELATIVE_PATH}' walked up from: $walkAnchor.",
  )
}
