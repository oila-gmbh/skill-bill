package skillbill.infrastructure.fs

import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseOutputSynthesizeAdapterTest {
  @Test
  fun `implement implementation_receipt sibling is accepted via synthesizer`() {
    val raw =
      """
      {
        "contract_version": "0.6",
        "phase_id": "implement",
        "status": "completed",
        "summary": "Did the work.",
        "produced_outputs": {
          "implementation_receipt": {
            "projection_kind": "implementation_receipt",
            "completed_task_ids": ["task-1"]
          }
        }
      }
      """.trimIndent()

    val result = FeatureTaskRuntimePhaseOutputValidatorAdapter().validatePhaseOutput(raw, "implement")
    val accepted = assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
    val produced = JsonSupport.anyToStringAnyMap(accepted.normalizedOutput.envelope["produced_outputs"])
    assertTrue(produced?.get("value").toString().contains("completed_task_ids"))
  }

  @Test
  fun `bare non-json prose is not synthesized into an accepted envelope`() {
    val result = FeatureTaskRuntimePhaseOutputValidatorAdapter()
      .validatePhaseOutput("not a json object", "implement")
    assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(result)
  }

  @Test
  fun `settlement-shaped envelope validates for gate consumption`() {
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = "implement",
        status = "completed",
        value = "receipt prose",
        summary = "receipt prose",
      ),
    )
    val result = FeatureTaskRuntimePhaseOutputValidatorAdapter()
      .validatePhaseOutput(JsonSupport.mapToJsonString(envelope), "implement")
    assertIs<FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged>(result)
  }

  @Test
  fun `schema-invalid settlement-shaped envelope stays rejected at the gate`() {
    val corrupt =
      """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"implement",""" +
        """"status":"completed","summary":"bad","produced_outputs":{}}"""
    val rejected = FeatureTaskRuntimePhaseOutputValidatorAdapter()
      .validatePhaseOutput(corrupt, "implement")
    assertIs<FeatureTaskRuntimePhaseOutputValidationResult.Rejected>(rejected)
  }
}
