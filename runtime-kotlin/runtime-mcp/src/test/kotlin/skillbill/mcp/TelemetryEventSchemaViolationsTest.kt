package skillbill.mcp

import skillbill.error.InvalidTelemetryEventSchemaError
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

  /** Schema-clean envelope for `feature_implement_started`. */
  private fun validImplementStartedEnvelope(): MutableMap<String, Any?> = linkedMapOf(
    "event_name" to "feature_implement_started",
    "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
    "feature_size" to "SMALL",
    "acceptance_criteria_count" to 1,
    "open_questions_count" to 0,
    "spec_input_types" to listOf("raw_text"),
    "spec_word_count" to 100,
    "rollout_needed" to false,
    "feature_name" to "read-path-config-variant-resolution",
    "issue_key" to "SKILL-32",
    "spec_summary" to "summary",
  )

  /** Schema-clean envelope for `feature_implement_finished`. */
  private fun validImplementFinishedEnvelope(): MutableMap<String, Any?> = linkedMapOf(
    "event_name" to "feature_implement_finished",
    "contract_version" to TELEMETRY_EVENT_CONTRACT_VERSION,
    "session_id" to "fis-1",
    "completion_status" to "completed",
    "plan_correction_count" to 0,
    "plan_task_count" to 1,
    "plan_phase_count" to 1,
    "feature_flag_used" to false,
    "files_created" to 0,
    "files_modified" to 1,
    "tasks_completed" to 1,
    "review_iterations" to 1,
    "audit_result" to "all_pass",
    "audit_iterations" to 1,
    "validation_result" to "pass",
    "boundary_history_written" to true,
    "pr_created" to false,
    "plan_deviation_notes" to "",
  )

  @Test
  fun `valid base envelope passes validation`() {
    // Sanity-check the fixture — otherwise every violation test below
    // would be ambiguous about which schema rule it actually trips.
    TelemetryEventSchemaValidator.validate(validImplementStartedEnvelope())
    TelemetryEventSchemaValidator.validate(validImplementFinishedEnvelope())
  }

  @Test
  fun `unknown event_name fails validation with event_name in reason`() {
    val envelope = validImplementStartedEnvelope()
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
    val envelope = validImplementStartedEnvelope()
    envelope.remove("feature_name")

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    // Required-property violations may surface at the parent path; the
    // reason MUST name the missing key so callers can pinpoint it.
    assertContains(error.reason.lowercase() + " " + error.fieldPath.lowercase(), "feature_name")
    assertEquals("feature_implement_started", error.eventName)
  }

  @Test
  fun `wrong contract_version fails validation with contract_version field path`() {
    val envelope = validImplementStartedEnvelope()
    envelope["contract_version"] = "9.99"

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    assertContains(error.reason.lowercase() + " " + error.fieldPath.lowercase(), "contract_version")
    assertEquals("feature_implement_started", error.eventName)
  }

  @Test
  fun `unknown additional property fails strict event validation`() {
    val envelope = validImplementStartedEnvelope()
    envelope["bogus_extra"] = true

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    assertContains(error.reason, "bogus_extra")
    assertEquals("feature_implement_started", error.eventName)
  }

  @Test
  fun `type mismatch on a typed field fails validation`() {
    val envelope = validImplementStartedEnvelope()
    // `acceptance_criteria_count` is declared as integer in the
    // schema; a string value should trip the type rule.
    envelope["acceptance_criteria_count"] = "not-a-number"

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(envelope)
    }
    assertContains(error.reason.lowercase() + " " + error.fieldPath.lowercase(), "acceptance_criteria_count")
    assertEquals("feature_implement_started", error.eventName)
  }

  @Test
  fun `discriminator mismatch a finished payload tagged as started fails validation`() {
    // Build a finished-shaped payload but pin event_name to
    // feature_verify_finished — none of the finished-required fields
    // belong to the verify_finished branch, so `oneOf` rejects it.
    val finishedShapedButVerifyTagged = validImplementFinishedEnvelope()
    finishedShapedButVerifyTagged["event_name"] = "feature_verify_finished"

    val error = assertFailsWith<InvalidTelemetryEventSchemaError> {
      TelemetryEventSchemaValidator.validate(finishedShapedButVerifyTagged)
    }
    // The validator should carry the offending event_name as it
    // appears in the envelope — even though the envelope shape does
    // not match the branch.
    assertEquals("feature_verify_finished", error.eventName)
  }
}
