package skillbill.contracts.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalPlanningPreparationSchemaValidatorTest {
  @Test
  fun `a well-formed envelope validates against the canonical schema`() {
    GoalPlanningPreparationSchemaValidator.validate(wellFormedEnvelope(), "goal-1#1")
  }

  @Test
  fun `an envelope missing a required field is rejected`() {
    val envelope = wellFormedEnvelope().toMutableMap().apply { remove("parent_goal_workflow_id") }.toMap()

    val error =
      assertFailsWith<skillbill.error.InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningPreparationSchemaValidator.validate(envelope, "goal-1#1")
      }
    assertEquals("goal-1#1", error.sourceLabel)
  }

  @Test
  fun `an envelope with an unsupported preparation status is rejected`() {
    val envelope = wellFormedEnvelope().toMutableMap().apply { this["preparation_status"] = "archived" }.toMap()

    assertFailsWith<skillbill.error.InvalidGoalPlanningPreparationSchemaError> {
      GoalPlanningPreparationSchemaValidator.validate(envelope, "goal-1#1")
    }
  }

  @Test
  fun `an envelope with a non-positive subtask id is rejected`() {
    val envelope = wellFormedEnvelope().toMutableMap().apply { this["subtask_id"] = 0 }.toMap()

    assertFailsWith<skillbill.error.InvalidGoalPlanningPreparationSchemaError> {
      GoalPlanningPreparationSchemaValidator.validate(envelope, "goal-1#1")
    }
  }

  @Test
  fun `an envelope with an incompatible contract version is rejected`() {
    val envelope = wellFormedEnvelope().toMutableMap().apply { this["contract_version"] = "0.2" }.toMap()

    assertFailsWith<skillbill.error.InvalidGoalPlanningPreparationSchemaError> {
      GoalPlanningPreparationSchemaValidator.validate(envelope, "goal-1#1")
    }
  }

  @Test
  fun `an envelope with an empty payload is rejected`() {
    val envelope = wellFormedEnvelope().toMutableMap().apply { this["plan_payload"] = "" }.toMap()

    assertFailsWith<skillbill.error.InvalidGoalPlanningPreparationSchemaError> {
      GoalPlanningPreparationSchemaValidator.validate(envelope, "goal-1#1")
    }
  }

  @Test
  fun `an envelope with incomplete provenance is rejected`() {
    val envelope = wellFormedEnvelope().toMutableMap().apply {
      this["provenance"] = mapOf(
        "parent_spec_hash" to "p",
        "sub_spec_hash" to "s",
        "decomposition_manifest_hash" to "m",
      )
    }.toMap()

    assertFailsWith<skillbill.error.InvalidGoalPlanningPreparationSchemaError> {
      GoalPlanningPreparationSchemaValidator.validate(envelope, "goal-1#1")
    }
  }

  private fun wellFormedEnvelope(): Map<String, Any?> = linkedMapOf(
    "contract_version" to "0.1",
    "parent_goal_workflow_id" to "goal-1",
    "normalized_issue_key" to "SKILL-128",
    "repository_identity" to "repo-root-realpath-v1:/repository",
    "subtask_id" to 1,
    "governed_sub_spec_path" to ".feature-specs/SKILL-128/spec_subtask_1.md",
    "preparation_status" to "prepared",
    "provenance" to linkedMapOf(
      "parent_spec_hash" to "parent-hash",
      "sub_spec_hash" to "sub-hash",
      "decomposition_manifest_hash" to "manifest-hash",
      "phase_output_contract_id" to FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
      "phase_output_contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
    ),
    "preplan_payload" to """{"phase_id":"preplan"}""",
    "plan_payload" to """{"phase_id":"plan"}""",
  )
}
