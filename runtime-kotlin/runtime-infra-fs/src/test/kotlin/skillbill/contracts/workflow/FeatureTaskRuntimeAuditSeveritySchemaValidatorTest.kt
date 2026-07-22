package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeAuditSeveritySchemaValidatorTest {
  @Test
  fun `audit gaps accept only blocker and major while satisfied preserves minor and nit`() {
    listOf("blocker", "major").forEach { severity ->
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(auditGapEnvelope(severity), "audit")
    }

    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(satisfiedWithNonBlockingFindings, "audit")
  }

  @Test
  fun `minor and nit cannot be declared as audit gaps`() {
    listOf("minor", "nit").forEach { severity ->
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(auditGapEnvelope(severity), "audit")
      }
    }
  }

  @Test
  fun `non blocking audit findings reject blocker and major`() {
    listOf("blocker", "major").forEach { severity ->
      val envelope = satisfiedWithNonBlockingFindings.replace("minor", severity)
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "audit")
      }
    }
  }

  private fun auditGapEnvelope(severity: String): String =
    """{"contract_version":"0.2","phase_id":"audit","status":"completed","summary":"audit",""" +
      """"verdict":"gaps_found","produced_outputs":{"unmet_criteria":[""" +
      """{"acceptance_criterion_ref":"AC-128","message":"Behavior is missing.","severity":"$severity"}],""" +
      """"audit_repair_plan":$auditRepairPlanJson}}"""

  private val satisfiedWithNonBlockingFindings =
    """{"contract_version":"0.2","phase_id":"audit","status":"completed","summary":"audit",""" +
      """"verdict":"satisfied","produced_outputs":{"unmet_criteria":[],"non_blocking_findings":[""" +
      """{"acceptance_criterion_ref":"AC-128","message":"Small cleanup remains.","severity":"minor"},""" +
      """{"acceptance_criterion_ref":"AC-129","message":"Naming could improve.","severity":"nit"}]}}"""

  private val auditRepairPlanJson =
    """
    {"contract_version":"0.2","gaps":[{
      "gap_id":"ac-128-gap-1",
      "acceptance_criterion_ref":"AC-128",
      "acceptance_criterion_text":"Behavior exists.",
      "failure_evidence":{"observation":"required_behavior_absent",
        "artifact_ref":"runtime-kotlin/application","check_ref":"AC-128"},
      "diagnosis":"Add the missing behavior.",
      "affected_boundary":"runtime application",
      "repair_items":[{
        "repair_item_id":"ac-128-gap-1-item-1",
        "intended_outcome":"The required behavior exists.",
        "implementation_actions":["Add the required behavior."],
        "affected_paths_or_symbols":["runtime-application/src/main"],
        "required_verification":["Run the focused test."],
        "depends_on":[],
        "status":"pending"
      }]
    }]}
    """.trimIndent()
}
