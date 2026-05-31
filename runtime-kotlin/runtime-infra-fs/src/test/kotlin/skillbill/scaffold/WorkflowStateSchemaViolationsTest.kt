package skillbill.scaffold

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.contracts.workflow.CanonicalWorkflowStateSchemaValidator
import skillbill.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.contracts.workflow.extractOffendingValueFromInstance
import skillbill.error.InvalidWorkflowStateSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * SKILL-48 Subtask 2a AC5: per-violation loud-fail coverage for the
 * highest-signal rules in the canonical schema. Each case asserts both
 * the exception type (`InvalidWorkflowStateSchemaError`) and that the
 * loud-fail message names the offending field path so a future
 * regression cannot silently swallow validation errors.
 */
class WorkflowStateSchemaViolationsTest {

  private val validator: WorkflowStateSchemaValidator = CanonicalWorkflowStateSchemaValidator()

  @Test
  fun `unknown step status enum value loud-fails`() {
    // The schema's per-skill `oneOf` block emits many sub-errors when a
    // snapshot fails to match either branch. We assert that the
    // exception type is the typed `InvalidWorkflowStateSchemaError` and
    // that the loud-fail message references either the offending step
    // field or the enum constraint that catches it. Asserting one
    // specific path would couple the test to networknt's reporting
    // order across library upgrades.
    val snapshot = baseSnapshot().toMutableMap().apply {
      put(
        "steps",
        listOf(
          linkedMapOf<String, Any?>(
            "step_id" to "assess",
            "status" to "frobnicated",
            "attempt_count" to 1,
          ),
        ),
      )
    }
    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      validator.validate(snapshot, "bill-feature-task")
    }
    val message = error.message.orEmpty()
    // The offending value `frobnicated` must surface somewhere in the
    // loud-fail message so a regression that silently swallows the
    // value name is caught.
    assertContains(message, "frobnicated")
  }

  @Test
  fun `missing required field loud-fails`() {
    val snapshot = baseSnapshot().toMutableMap().apply {
      remove("current_step_id")
    }
    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      validator.validate(snapshot, "bill-feature-task")
    }
    // Either the explicit `current_step_id` required-property error
    // or the per-skill `oneOf` branch failure must surface. Both
    // mention `current_step_id` in their detail message.
    assertContains(error.message.orEmpty(), "current_step_id")
  }

  @Test
  fun `additional unknown top-level property loud-fails with the offending key`() {
    val snapshot = baseSnapshot().toMutableMap().apply {
      put("extra_field", "x")
    }
    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      validator.validate(snapshot, "bill-feature-task")
    }
    val message = error.message.orEmpty()
    assertContains(message, "extra_field")
  }

  @Test
  fun `wrong contract_version loud-fails with contract_version path`() {
    val snapshot = baseSnapshot().toMutableMap().apply {
      put("contract_version", "999")
    }
    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      validator.validate(snapshot, "bill-feature-task")
    }
    assertContains(error.message.orEmpty(), "contract_version")
  }

  @Test
  fun `extractOffendingValueFromInstance reads array index from JSON-Pointer-format instanceLocation`() {
    // F-303: networknt's older builds report instanceLocation in
    // JSON-Pointer form (`/steps/0/status`) instead of JSONPath form
    // (`$.steps[0].status`). The dotted path becomes `steps.0.status`
    // and `JsonNode.path("0")` on an array used to return MissingNode,
    // so offending-value extraction silently returned the empty string.
    // Pin the fixed behaviour: pure-integer segments index the array.
    val snapshot = baseSnapshot().toMutableMap().apply {
      put(
        "steps",
        listOf(
          linkedMapOf<String, Any?>(
            "step_id" to "assess",
            "status" to "frobnicated",
            "attempt_count" to 1,
          ),
        ),
      )
    }
    val instance = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(snapshot)
    val offendingValue = extractOffendingValueFromInstance(instance, "/steps/0/status")
    assertEquals("frobnicated", offendingValue)
  }

  @Test
  fun `per-skill workflow_status enum mismatch loud-fails`() {
    // `blocked` is valid for `bill-feature-task` but NOT for
    // `bill-feature-verify` — the per-skill `oneOf` branch must reject
    // it. The schema currently has the verify branch declaring a 5-
    // value enum without `blocked`.
    val snapshot = baseVerifySnapshot().toMutableMap().apply {
      put("workflow_status", "blocked")
    }
    val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
      validator.validate(snapshot, "bill-feature-verify")
    }
    val message = error.message.orEmpty()
    // The message must reference `workflow_status` or the offending
    // value `blocked` so the per-skill enum failure is unmistakable.
    assertContains(message, "workflow_status")
  }

  private fun baseSnapshot(): Map<String, Any?> = linkedMapOf(
    "workflow_id" to "wfl-19700101-000000-aaaa",
    "session_id" to "",
    "workflow_name" to "bill-feature-task",
    "contract_version" to "0.1",
    "workflow_status" to "running",
    "current_step_id" to "assess",
    "steps" to listOf(
      linkedMapOf<String, Any?>(
        "step_id" to "assess",
        "status" to "running",
        "attempt_count" to 1,
      ),
    ),
    "artifacts" to emptyMap<String, Any?>(),
    "started_at" to "",
    "updated_at" to "",
    "finished_at" to "",
  )

  private fun baseVerifySnapshot(): Map<String, Any?> = linkedMapOf(
    "workflow_id" to "wfv-19700101-000000-aaaa",
    "session_id" to "",
    "workflow_name" to "bill-feature-verify",
    "contract_version" to "0.1",
    "workflow_status" to "running",
    "current_step_id" to "gather_diff",
    "steps" to listOf(
      linkedMapOf<String, Any?>(
        "step_id" to "gather_diff",
        "status" to "running",
        "attempt_count" to 1,
      ),
    ),
    "artifacts" to emptyMap<String, Any?>(),
    "started_at" to "",
    "updated_at" to "",
    "finished_at" to "",
  )
}
