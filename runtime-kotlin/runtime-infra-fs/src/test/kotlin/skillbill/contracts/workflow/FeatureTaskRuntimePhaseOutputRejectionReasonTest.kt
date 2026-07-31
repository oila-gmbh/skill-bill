package skillbill.contracts.workflow

import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairRuleViolation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** SKILL-152 AC-001 / AC-003: the dual value-bearing and payload-free rejection reason variants. */
class FeatureTaskRuntimePhaseOutputRejectionReasonTest {
  @Test
  fun `a schema violation reports the offending value privately and withholds it from the payload-free reason`() {
    val offendingValue = "totally-done-trust-me"
    val envelope =
      """{"contract_version":"0.3","phase_id":"plan","status":"$offendingValue",""" +
        """"summary":"Plan output.","produced_outputs":{"tasks":["task-1"]}}"""

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "plan")
    }

    assertContains(error.reason, offendingValue, message = "the private reason must keep the offending value")
    assertContains(error.reason, " — offending value: ")
    val payloadFree = assertNotNull(error.payloadFreeReason)
    assertFalse(
      payloadFree.contains(offendingValue),
      "the payload-free reason leaked the offending value: $payloadFree",
    )
    assertFalse(payloadFree.contains(" — offending value: "))
    // Same rule and field-path content in both variants: only the values differ.
    assertContains(payloadFree, "status")
    assertContains(payloadFree, "enumeration")
  }

  @Test
  fun `every reported violation keeps its rule and path in the payload-free variant`() {
    // Two violations at once, so the multi-violation join is covered in both renderings.
    val envelope =
      """{"contract_version":"9.9","phase_id":"plan","status":"nope",""" +
        """"summary":"Plan output.","produced_outputs":{"tasks":["task-1"]}}"""

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "plan")
    }

    val payloadFree = assertNotNull(error.payloadFreeReason)
    assertEquals(
      error.reason.split(" | ").size,
      payloadFree.split(" | ").size,
      "both variants must report the same violation count",
    )
    assertFalse(payloadFree.contains("nope"), "payload-free variant leaked a value: $payloadFree")
  }

  @Test
  fun `a phase_id mismatch names the expected phase without echoing the produced one`() {
    val envelope =
      """{"contract_version":"0.3","phase_id":"implement-but-lying","status":"completed",""" +
        """"summary":"Plan output.","produced_outputs":{"tasks":["task-1"]}}"""

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "plan")
    }

    assertContains(error.reason, "implement-but-lying")
    val payloadFree = assertNotNull(error.payloadFreeReason)
    assertContains(payloadFree, "phase_id must match the executing phase 'plan'")
    assertFalse(payloadFree.contains("implement-but-lying"), "payload-free reason echoed the agent's phase_id")
  }

  @Test
  fun `a malformed output keeps its composer key phrase without the offending token`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText("{\"a\": :}", "plan")
    }

    val payloadFree = assertNotNull(error.payloadFreeReason)
    // The prompt composer's unparseable-root correction keys on this prefix, so it must survive.
    assertContains(payloadFree, "Phase output is malformed")
    assertEquals(FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED, error.failureKind)
  }

  @Test
  fun `a root that is not an object carries the same value-free reason in both variants`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText("- just\n- a list", "plan")
    }

    assertEquals("<root> must be an object.", error.payloadFreeReason)
  }

  // The runtime expands the audit's compact gaps into the repair plan; an author-supplied
  // `audit_repair_plan` is forbidden by the phase-output schema. A backtick in `issue` survives the
  // compact-gap pattern and then fails the expanded plan's `compactSummary` rule.
  @Test
  fun `an expanded audit repair plan schema violation reaches the payload-free reason under its field prefix`() {
    val offendingIssue = "The `prepareLaunch` checkpoint is absent."
    val envelope =
      """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"audit",""" +
        """"verdict":"gaps_found","produced_outputs":{"gaps":[{"criterion":"AC-001","severity":"blocker",""" +
        """"location":"Runtime.prepareLaunch","issue":"$offendingIssue",""" +
        """"fix":"Resolve the producer checkpoint."}]}}"""

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "audit")
    }

    assertContains(error.reason, "produced_outputs.audit_repair_plan: ")
    val payloadFree = assertNotNull(error.payloadFreeReason)
    assertContains(payloadFree, "produced_outputs.audit_repair_plan: ")
    assertFalse(payloadFree.contains(offendingIssue), "payload-free reason leaked a value: $payloadFree")
  }

  // The typed rule seam itself: a violated audit-repair rule must carry a restatement that names the rule
  // and the field while omitting the value the private message quotes.
  @Test
  fun `a typed audit repair rule violation restates its rule without the offending value`() {
    val offendingCheckRef = "ran it locally, looked fine"

    val violation = assertFailsWith<FeatureTaskRuntimeAuditRepairRuleViolation> {
      FeatureTaskRuntimeEvidence(
        observation = FeatureTaskRuntimeEvidence.Observation.REQUIRED_BEHAVIOR_ABSENT,
        artifactRef = "src/main/Example.kt",
        checkRef = offendingCheckRef,
      )
    }

    assertContains(
      requireNotNull(violation.message),
      offendingCheckRef,
      message = "the private message must keep the offending value",
    )
    assertContains(violation.payloadFreeMessage, "check_ref must match AC-###")
    assertFalse(
      violation.payloadFreeMessage.contains(offendingCheckRef),
      "the payload-free restatement leaked the offending value: ${violation.payloadFreeMessage}",
    )
  }
}
