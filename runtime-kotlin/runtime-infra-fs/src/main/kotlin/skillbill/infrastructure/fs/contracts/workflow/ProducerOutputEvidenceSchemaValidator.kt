package skillbill.infrastructure.fs.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.contracts.workflow.PRODUCER_OUTPUT_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.ProducerOutputEvidenceSchemaPaths
import skillbill.error.InvalidProducerOutputEvidenceSchemaError
import skillbill.ports.persistence.ProducerOutputEvidence

object ProducerOutputEvidenceSchemaValidator {
  private val mapper = ObjectMapper()
  private val schema: JsonSchema by lazy { loadSchema() }

  fun validate(evidence: ProducerOutputEvidence) {
    val instance = mapper.createObjectNode().apply {
      put("contract_version", PRODUCER_OUTPUT_EVIDENCE_CONTRACT_VERSION)
      put("workflow_id", evidence.workflowId)
      put("phase_id", evidence.phaseId)
      put("generation", evidence.generation)
      put("attempt", evidence.attempt)
      put("repair_turn", evidence.repairTurn)
      put("agent_id", evidence.agentId)
      put("model", evidence.model)
      put("recorded_at", evidence.recordedAt.toString())
      put("byte_size", evidence.byteSize)
      put("sha256", evidence.sha256)
    }
    val violations = schema.validate(instance)
    if (violations.isNotEmpty()) {
      throw InvalidProducerOutputEvidenceSchemaError(
        "Producer output evidence '${evidence.workflowId}:${evidence.phaseId}:" +
          "${evidence.generation}:${evidence.attempt}:${evidence.repairTurn}' fails canonical contract " +
          "$PRODUCER_OUTPUT_EVIDENCE_CONTRACT_VERSION.",
      )
    }
  }

  private fun loadSchema(): JsonSchema {
    val resource = ProducerOutputEvidenceSchemaValidator::class.java.classLoader
      .getResourceAsStream(ProducerOutputEvidenceSchemaPaths.CLASSPATH_RESOURCE)
      ?: throw InvalidProducerOutputEvidenceSchemaError(
        "Canonical producer output evidence schema resource is missing.",
      )
    val document: JsonNode = resource.use { stream -> YAMLMapper().readTree(stream) }
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(document, LOCALE_STABLE_SCHEMA_CONFIG)
  }
}
