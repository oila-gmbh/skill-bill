package skillbill.infrastructure.fs.contracts.workflow

import skillbill.contracts.SharedPayloadKeys

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.contracts.workflow.REJECTED_OUTPUT_DIAGNOSTIC_CONTRACT_VERSION
import skillbill.contracts.workflow.RejectedOutputDiagnosticSchemaPaths
import skillbill.error.InvalidRejectedOutputDiagnosticSchemaError
import skillbill.infrastructure.fs.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic

object RejectedOutputDiagnosticSchemaValidator {
  private val mapper = ObjectMapper()
  private val schema: JsonSchema by lazy { loadSchema() }

  fun validate(metadata: RejectedOutputDiagnostic) {
    val instance = mapper.createObjectNode().apply {
      put(SharedPayloadKeys.CONTRACT_VERSION, REJECTED_OUTPUT_DIAGNOSTIC_CONTRACT_VERSION)
      put("identity", metadata.identity)
      put(SharedPayloadKeys.WORKFLOW_ID, metadata.workflowId)
      put(SharedPayloadKeys.PHASE_ID, metadata.phaseId)
      put("attempt", metadata.attempt)
      put("repair_turn", metadata.repairTurn)
      put("rule", metadata.rule)
      put("path", metadata.path)
      put("reason", metadata.reason)
      put("agent_id", metadata.agentId)
      put("model", metadata.model)
      put("recorded_at", metadata.recordedAt.toString())
      put("byte_size", metadata.byteSize)
      put("sha256", metadata.sha256)
      put("lifecycle", metadata.lifecycle.name.lowercase())
    }
    val violations = schema.validate(instance)
    if (violations.isNotEmpty()) {
      throw InvalidRejectedOutputDiagnosticSchemaError(
        "Rejected output diagnostic '${metadata.identity.ifBlank { "<invalid>" }}' fails canonical " +
          "contract $REJECTED_OUTPUT_DIAGNOSTIC_CONTRACT_VERSION.",
      )
    }
  }

  private fun loadSchema(): JsonSchema {
    val resource = RejectedOutputDiagnosticSchemaValidator::class.java.classLoader
      .getResourceAsStream(RejectedOutputDiagnosticSchemaPaths.CLASSPATH_RESOURCE)
      ?: throw InvalidRejectedOutputDiagnosticSchemaError(
        "Canonical rejected-output diagnostic schema resource is missing.",
      )
    val document: JsonNode = resource.use { stream -> YAMLMapper().readTree(stream) }
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(document, LOCALE_STABLE_SCHEMA_CONFIG)
  }
}
