package skillbill.contracts.workflow

import skillbill.error.InvalidIdeStatusSchemaError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IdeStatusSchemaValidatorTest {
  @Test
  fun `valid idle snapshot passes`() {
    IdeStatusSchemaValidator.validate(validIdleSnapshot(), "test-idle")
  }

  @Test
  fun `valid active feature-goal snapshot passes`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-148",
        "workflow_id" to "goal-1",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T10:00:00Z",
        "current_subtask" to linkedMapOf("id" to "1", "started_at" to "2026-08-06T10:05:00Z"),
        "updated_at" to "2026-08-06T10:10:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-148 is active on implement.",
      ),
      "test-goal",
    )
  }

  @Test
  fun `unknown lifecycle state fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["lifecycle_state"] = "exploded"
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-lifecycle")
    }
  }

  @Test
  fun `unknown problem code fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["problem"] = linkedMapOf(
      "code" to "not_a_code",
      "message" to "bad",
    )
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-problem")
    }
  }

  @Test
  fun `wrong contract version fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["contract_version"] = "9.9"
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-bad-version")
    }
  }

  @Test
  fun `unknown additional property fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["sqlite_row"] = "nope"
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-additional-property")
    }
  }

  @Test
  fun `malformed nested current_step fails loudly with typed error`() {
    val malformed = validIdleSnapshot().toMutableMap()
    malformed["current_step"] = linkedMapOf("id" to "idle")
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(malformed, "test-malformed-step")
    }
  }

  @Test
  fun `goal snapshot with a fully populated planning object passes`() {
    IdeStatusSchemaValidator.validate(
      goalSnapshotWithPlanning(
        linkedMapOf(
          "state" to "partially_planned",
          "shared_preplan_prepared" to true,
          "planned_subtask_count" to 2,
          "total_subtask_count" to 5,
          "current_planning_subtask_id" to "3",
          "reason" to "Planning subtask 3.",
        ),
      ),
      "test-planning-valid",
    )
  }

  @Test
  fun `goal snapshot with only required planning properties passes`() {
    IdeStatusSchemaValidator.validate(
      goalSnapshotWithPlanning(
        linkedMapOf(
          "state" to "not_started",
          "shared_preplan_prepared" to false,
          "planned_subtask_count" to 0,
          "total_subtask_count" to 0,
        ),
      ),
      "test-planning-minimal",
    )
  }

  @Test
  fun `unknown planning property fails loudly with typed error`() {
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "preplanned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to 1,
            "total_subtask_count" to 4,
            "planning_notes" to "nope",
          ),
        ),
        "test-planning-unknown-property",
      )
    }
  }

  @Test
  fun `negative planning counts fail loudly with typed error`() {
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "preplanned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to -1,
            "total_subtask_count" to 4,
          ),
        ),
        "test-planning-negative-planned",
      )
    }
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "preplanned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to 1,
            "total_subtask_count" to -4,
          ),
        ),
        "test-planning-negative-total",
      )
    }
  }

  @Test
  fun `invalid planning state fails loudly with typed error`() {
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "half_planned",
            "shared_preplan_prepared" to true,
            "planned_subtask_count" to 1,
            "total_subtask_count" to 4,
          ),
        ),
        "test-planning-bad-state",
      )
    }
  }

  @Test
  fun `planning object missing a required property fails loudly with typed error`() {
    assertFailsWith<InvalidIdeStatusSchemaError> {
      IdeStatusSchemaValidator.validate(
        goalSnapshotWithPlanning(
          linkedMapOf(
            "state" to "preplanned",
            "planned_subtask_count" to 1,
            "total_subtask_count" to 4,
          ),
        ),
        "test-planning-missing-required",
      )
    }
  }

  private fun goalSnapshotWithPlanning(planning: Map<String, Any?>): LinkedHashMap<String, Any?> = linkedMapOf(
    "contract_version" to IDE_STATUS_CONTRACT_VERSION,
    "repository_identity" to "repo-root-realpath-v1:/repo",
    "issue_key" to "SKILL-165",
    "workflow_id" to "goal-1",
    "workflow_family" to "feature-goal",
    "lifecycle_state" to "active",
    "current_step" to linkedMapOf("id" to "planning", "label" to "Planning"),
    "planning" to planning,
    "updated_at" to "2026-08-06T10:10:00Z",
    "freshness" to "fresh",
    "summary" to "Goal SKILL-165 is planning subtasks (2/5 planned).",
  )

  private fun validIdleSnapshot(): LinkedHashMap<String, Any?> = linkedMapOf(
    "contract_version" to IDE_STATUS_CONTRACT_VERSION,
    "repository_identity" to "repo-root-realpath-v1:/repo",
    "lifecycle_state" to "idle",
    "current_step" to linkedMapOf("id" to "none", "label" to "No matching work"),
    "updated_at" to "2026-08-06T10:00:00Z",
    "freshness" to "fresh",
    "summary" to "No matching Skill Bill work for this repository.",
    "problem" to linkedMapOf(
      "code" to "no_matching_work",
      "message" to "No matching Skill Bill work for this repository.",
    ),
  )
}
