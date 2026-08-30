package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import java.util.logging.Level

object FeatureTaskRuntimePhaseOutputSchemaValidator {
  internal val schema: JsonSchema by lazy { loadFeatureTaskRuntimePhaseOutputSchema() }
  internal val mapper: ObjectMapper by lazy { ObjectMapper() }
  internal val yamlMapper: YAMLMapper by lazy {
    YAMLMapper(YAMLFactory().apply { enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) })
  }
  internal val mapType = object : TypeReference<Map<String, Any?>>() {}

  fun validate(phaseOutput: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(phaseOutput)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isNotEmpty()) {
      featureTaskRuntimePhaseOutputLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors))
      val reasons = formatViolationReasons(errors.sortedWith(featureTaskRuntimePhaseOutputViolationOrdering), instance)
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = reasons.valueBearing,
        payloadFreeReason = reasons.payloadFree,
      )
    }
    val phaseId = phaseOutput["phase_id"] as? String
    if (phaseId != sourceLabel) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "phase_id must match the executing phase '$sourceLabel' but was '${phaseId.orEmpty()}'.",
        payloadFreeReason = "phase_id must match the executing phase '$sourceLabel'.",
        failureCode = "phase_id_mismatch",
      )
    }
  }

  fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
  }

  fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
    return parsed
  }

  fun normalizePhaseOutput(phaseOutputText: String, sourceLabel: String): NormalizedFeatureTaskRuntimePhaseOutput {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
    return NormalizedFeatureTaskRuntimePhaseOutput(
      canonicalJson = mapper.writeValueAsString(parsed),
      envelope = parsed,
    )
  }

  fun normalizeVerifyingPhaseOutputLenient(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val node = readPhaseOutputObjectNodeLenient(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validateVerifyingEnvelopeShell(parsed, sourceLabel)
    return NormalizedFeatureTaskRuntimePhaseOutput(
      canonicalJson = mapper.writeValueAsString(parsed),
      envelope = parsed,
    )
  }

  fun normalizeAuditPhaseOutputLenient(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput = normalizeVerifyingPhaseOutputLenient(phaseOutputText, sourceLabel)

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
}
