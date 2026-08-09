package skillbill.mcp

import skillbill.error.InvalidTelemetryEventSchemaError
import skillbill.mcp.telemetry.TELEMETRY_EVENT_CONTRACT_VERSION
import skillbill.mcp.telemetry.TelemetryEventSchemaValidator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * SKILL-48 Subtask 2d AC7: per-violation tests covering the
 * highest-signal rules — unknown event_name, missing required field,
 * wrong contract_version, unknown additional property, type mismatch,
 * and discriminator mismatch (a payload whose body shape belongs to a
 * different event_name than the one declared).
 *
 * Each case starts from a known-valid envelope and mutates one field;
 * the test asserts that `TelemetryEventSchemaValidator.validate` throws
 * [InvalidTelemetryEventSchemaError] with the expected dotted
 * `fieldPath` and `eventName`. Mirrors `InstallPlanSchemaViolationsTest`
 * (Subtask 2b).
 */
class TelemetryEventSchemaViolationsTest {

  /** Schema-clean envelope for `feature_verify_started`. */
  private fun validVerifyStartedEnvelope(): MutableMap<String, Any?> = linkedMapOf(
    "event_name" to "feature_verify_started",
    "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
    "acceptance_criteria_count" to 1,
    "rollout_relevant" to false,
    "spec_summary" to "summary",
  )

  /** Schema-clean envelope for `feature_verify_finished`. */
  private fun validVerifyFinishedEnvelope(): MutableMap<String, Any?> = linkedMapOf(
    "event_name" to "feature_verify_finished",
    "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
    "feature_flag_audit_performed" to true,
    "review_iterations" to 1,
    "audit_result" to "all_pass",
    "completion_status" to "completed",
    "session_id" to "fvs-1",
    "gaps_found" to emptyList<String>(),
    "orchestrated" to false,
    "acceptance_criteria_count" to 1,
    "rollout_relevant" to false,
    "spec_summary" to "summary",
    "duration_seconds" to 120,
  )

  @Test
  fun `valid base envelope passes validation`() {
    // Sanity-check the fixture — otherwise every violation test below
    // would be ambiguous about which schema rule it actually trips.
    TelemetryEventSchemaValidator.validate(validVerifyStartedEnvelope())
    TelemetryEventSchemaValidator.validate(validVerifyFinishedEnvelope())
  }

  @Test
  fun `unknown event_name fails validation with event_name in reason`() {
    val envelope = validVerifyStartedEnvelope()
    envelope["event_name"] = "this_event_does_not_exist"

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    // The validator carries the offending event_name on the typed
    // field — grep by event name remains useful even when the name is
    // bogus.
    assertEquals("this_event_does_not_exist", error.eventName)
    // The reason mentions `oneOf` or `event_name` because no branch
    // matched the discriminator.
    val combined = (error.reason + " " + error.fieldPath).lowercase()
    val signals = listOf("oneof", "event_name", "anyof", "schema")
    val hits = signals.count { it in combined }
    assertEquals(
      hits > 0,
      true,
      "Unknown event_name violation reason should mention oneOf/event_name signal — got reason='${error.reason}' " +
        "fieldPath='${error.fieldPath}'.",
    )
  }

  @Test
  fun `missing required field fails validation`() {
    val envelope = validVerifyStartedEnvelope()
    envelope.remove("spec_summary")

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    // Required-property violations may surface at the parent path; the
    // reason MUST name the missing key so callers can pinpoint it.
    assertContains(error.reason.lowercase() + " " + error.fieldPath.lowercase(), "spec_summary")
    assertEquals("feature_verify_started", error.eventName)
  }

  @Test
  fun `wrong contract_version fails validation with contract_version field path`() {
    val envelope = validVerifyStartedEnvelope()
    envelope["contract_version"] = "9.99"

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    assertContains(error.reason.lowercase() + " " + error.fieldPath.lowercase(), "contract_version")
    assertEquals("feature_verify_started", error.eventName)
  }

  @Test
  fun `unknown additional property fails strict event validation`() {
    val envelope = validVerifyStartedEnvelope()
    envelope["bogus_extra"] = true

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    assertContains(error.reason, "bogus_extra")
    assertEquals("feature_verify_started", error.eventName)
  }

  @Test
  fun `type mismatch on a typed field fails validation`() {
    val envelope = validVerifyStartedEnvelope()
    // `acceptance_criteria_count` is declared as integer in the
    // schema; a string value should trip the type rule.
    envelope["acceptance_criteria_count"] = "not-a-number"

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    assertContains(error.reason.lowercase() + " " + error.fieldPath.lowercase(), "acceptance_criteria_count")
    assertEquals("feature_verify_started", error.eventName)
  }

  @Test
  fun `discriminator mismatch a finished payload tagged as started fails validation`() {
    // Build a finished-shaped payload but pin event_name to
    // feature_verify_started — none of the finished-required fields
    // belong to the verify_started branch, so `oneOf` rejects it.
    val finishedShapedButStartedTagged = validVerifyFinishedEnvelope()
    finishedShapedButStartedTagged["event_name"] = "feature_verify_started"

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(finishedShapedButStartedTagged)
    }
    // The validator should carry the offending event_name as it
    // appears in the envelope — even though the envelope shape does
    // not match the branch.
    assertEquals("feature_verify_started", error.eventName)
  }

  @Test
  fun `SKILL-175 retired prose events are rejected as unknown event names`() {
    listOf(
      "feature_task_prose_started",
      "feature_task_prose_finished",
      "feature_task_prose_stats",
      "feature_implement_started",
      "goal_prose_started",
      "goal_prose_subtask_finished",
      "goal_prose_finished",
    ).forEach { retired ->
      val envelope = linkedMapOf<String, Any?>(
        "event_name" to retired,
        "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
      )

      val error = assertFailsWith<InvalidTelemetryEventSchemaError>(message = retired) {
        TelemetryEventSchemaValidator.validate(envelope)
      }
      assertEquals(retired, error.eventName)
    }
  }
}
