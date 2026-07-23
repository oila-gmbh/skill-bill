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

  @Test
  fun `non blocking audit findings accept the compact gap vocabulary`() {
    FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(satisfiedWithGapShapedFindings, "audit")
  }

  @Test
  fun `non blocking audit findings still require a severity`() {
    val envelope = """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"audit",""" +
      """"verdict":"satisfied","produced_outputs":{"gaps":[],"non_blocking_findings":[""" +
      """{"message":"Small cleanup remains."}]}}"""

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "audit")
    }
  }

  private fun auditGapEnvelope(severity: String): String =
    """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"audit",""" +
      """"verdict":"gaps_found","produced_outputs":{"gaps":[""" +
      """{"criterion":"AC-128","severity":"$severity","location":"ReviewRunner.merge",""" +
      """"issue":"Behavior is missing.","fix":"Add the missing behavior."}]}}"""

  private val satisfiedWithNonBlockingFindings =
    """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"audit",""" +
      """"verdict":"satisfied","produced_outputs":{"gaps":[],"non_blocking_findings":[""" +
      """{"acceptance_criterion_ref":"AC-128","message":"Small cleanup remains.","severity":"minor"},""" +
      """{"acceptance_criterion_ref":"AC-129","message":"Naming could improve.","severity":"nit"}]}}"""

  private val satisfiedWithGapShapedFindings =
    """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"audit",""" +
      """"verdict":"satisfied","produced_outputs":{"gaps":[],"non_blocking_findings":[""" +
      """{"criterion":"AC-128","file":"ReviewRunner.kt","issue":"Small cleanup remains.",""" +
      """"fix":"Extract the helper.","severity":"minor"}]}}"""
}
