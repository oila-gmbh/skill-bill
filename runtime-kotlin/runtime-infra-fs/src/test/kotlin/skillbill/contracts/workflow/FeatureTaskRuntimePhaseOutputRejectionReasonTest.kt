package skillbill.contracts.workflow

import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
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
      """{"contract_version":"0.4","phase_id":"plan","status":"$offendingValue",""" +
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
    assertEquals("schema_invalid", error.failureCode)
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
      """{"contract_version":"0.4","phase_id":"implement-but-lying","status":"completed",""" +
        """"summary":"Plan output.","produced_outputs":{"tasks":["task-1"]}}"""

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "plan")
    }

    assertContains(error.reason, "implement-but-lying")
    val payloadFree = assertNotNull(error.payloadFreeReason)
    assertContains(payloadFree, "phase_id must match the executing phase 'plan'")
    assertFalse(payloadFree.contains("implement-but-lying"), "payload-free reason echoed the agent's phase_id")
    assertEquals("phase_id_mismatch", error.failureCode)
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
    assertEquals("malformed", error.failureCode)
  }

  @Test
  fun `a root that is not an object carries the same value-free reason in both variants`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText("- just\n- a list", "plan")
    }

    assertEquals("<root> must be an object.", error.payloadFreeReason)
  }

  @Test
  fun `an unmet-criterion note carrying backticks is rejected without leaking the value`() {
    val offendingNote = "The ```prepareLaunch``` checkpoint is absent."
    val envelope =
      """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"audit",""" +
        """"verdict":"gaps_found","produced_outputs":{"unmet_criteria":[""" +
        """{"criterion":"AC-001","note":"$offendingNote"}]}}"""

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      FeatureTaskRuntimePhaseOutputSchemaValidator.validatePhaseOutputText(envelope, "audit")
    }

    assertContains(error.reason, "produced_outputs.unmet_criteria")
    val payloadFree = assertNotNull(error.payloadFreeReason)
    assertFalse(payloadFree.contains(offendingNote), "payload-free reason leaked a value: $payloadFree")
  }
}
