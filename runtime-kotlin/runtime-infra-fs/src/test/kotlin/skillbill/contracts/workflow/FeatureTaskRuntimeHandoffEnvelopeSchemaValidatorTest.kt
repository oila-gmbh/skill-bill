package skillbill.contracts.workflow

import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.infrastructure.fs.FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeHandoffEnvelopeSchemaValidatorTest {
  private val validator = FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter()

  @Test
  fun `a well-formed envelope validates through the domain port`() {
    validator.validateEnvelope(envelope(), workflowId = "wftr-1")
  }

  @Test
  fun `a wrong contract version is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      validator.validateEnvelope(envelope(contractVersion = "9.9"), workflowId = "wftr-1")
    }

    assertEquals(FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID, error.failureKind)
    assertEquals("implement", error.consumerPhaseId)
    assertEquals("wftr-1", error.workflowId)
  }

  @Test
  fun `an undeclared wire field is rejected by strict additionalProperties`() {
    val invalid = envelope() + ("upstream_outputs_by_phase_id" to mapOf("plan" to "raw"))

    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> { validator.validateEnvelope(invalid) }
  }

  @Test
  fun `a forbidden raw-context field name is rejected`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      validator.validateEnvelope(envelope(fieldName = "payload"))
    }

    assertContains(error.message.orEmpty(), "projections")
  }

  @Test
  fun `an unknown projection value kind is rejected`() {
    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      validator.validateEnvelope(
        envelope(field = mapOf("name" to "phase_output_receipt", "kind" to "raw_blob", "text" to "x")),
      )
    }
  }

  @Test
  fun `a compact reference longer than the schema bound is rejected`() {
    assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
      validator.validateEnvelope(
        envelope(
          field = mapOf(
            "name" to "phase_output_receipt",
            "kind" to "compact_reference",
            "reference_kind" to "private_evidence_artifact",
            "reference_value" to "a".repeat(600),
          ),
        ),
      )
    }
  }

  private fun envelope(
    contractVersion: String = FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
    fieldName: String = "phase_output_receipt",
    field: Map<String, Any?> = mapOf("name" to fieldName, "kind" to "text", "text" to """{"plan":"ok"}"""),
  ): Map<String, Any?> = mapOf(
    "contract_version" to contractVersion,
    "consumer_phase_id" to "implement",
    "projections" to listOf(
      mapOf(
        "projection_name" to "plan_receipt",
        "source_ref" to "upstream_phase_output:plan",
        "projection_contract_id" to "feature_task_runtime.upstream_phase_receipt",
        "projection_contract_version" to "0.1",
        "prompt_visibility" to "prompt_visible",
        "fields" to listOf(field),
      ),
    ),
    "repository_checkpoint" to mapOf("fingerprint" to "head-abc"),
  )
}
