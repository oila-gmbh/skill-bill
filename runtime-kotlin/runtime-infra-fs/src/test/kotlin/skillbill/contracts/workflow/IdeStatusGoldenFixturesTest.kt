package skillbill.contracts.workflow

import kotlin.test.Test

/**
 * SKILL-148 Subtask 1: golden wire snapshots for every workflow family and typed
 * problem outcome. Each fixture must remain schema-valid.
 */
class IdeStatusGoldenFixturesTest {
  @Test
  fun `feature-task-runtime golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-148",
        "workflow_id" to "wfl-runtime-1",
        "workflow_family" to "feature-task-runtime",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 3, "total" to 9),
        "started_at" to "2026-08-06T08:00:00Z",
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "feature-task-runtime SKILL-148 is active on Implement.",
      ),
      "golden-runtime",
    )
  }

  /**
   * SKILL-183: the model-present shape. The model-absent shape stays pinned by every other
   * fixture here, which carries no `current_model` key at all.
   */
  @Test
  fun `feature-task-runtime current-model golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-183",
        "workflow_id" to "wfl-runtime-2",
        "workflow_family" to "feature-task-runtime",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 3, "total" to 9),
        "started_at" to "2026-08-06T08:00:00Z",
        "current_model" to linkedMapOf("model" to "claude-opus-4-8", "effort" to "high"),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "feature-task-runtime SKILL-183 is active on Implement.",
      ),
      "golden-runtime-current-model",
    )
  }

  /** Cursor's merged form carries the effort inside the model string, so no `effort` key is emitted. */
  @Test
  fun `feature-goal current-model without effort golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-183",
        "workflow_id" to "goal-6",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "current_subtask" to linkedMapOf("id" to "2"),
        "current_model" to linkedMapOf("model" to "claude-opus-4-8[effort=high]"),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-183 is active on Implement.",
      ),
      "golden-goal-current-model",
    )
  }

  @Test
  fun `feature-verify golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-148",
        "workflow_id" to "wfl-verify-1",
        "workflow_family" to "feature-verify",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "verify", "label" to "Verify"),
        "progress" to linkedMapOf("completed" to 1, "total" to 4),
        "started_at" to "2026-08-06T08:00:00Z",
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "feature-verify SKILL-148 is active on Verify.",
      ),
      "golden-verify",
    )
  }

  @Test
  fun `feature-goal golden validates`() {
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
        "started_at" to "2026-08-06T08:00:00Z",
        "current_subtask" to linkedMapOf("id" to "2", "started_at" to "2026-08-06T09:00:00Z"),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-148 is active on Implement.",
      ),
      "golden-goal",
    )
  }

  @Test
  fun `feature-goal mid-planning golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-165",
        "workflow_id" to "goal-2",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "planning", "label" to "Planning"),
        "progress" to linkedMapOf("completed" to 0, "total" to 5),
        "started_at" to "2026-08-06T08:00:00Z",
        // Both required and optional planning properties in one fixture.
        "planning" to linkedMapOf(
          "state" to "partially_planned",
          "shared_preplan_prepared" to true,
          "planned_subtask_count" to 2,
          "total_subtask_count" to 5,
          "current_planning_subtask_id" to "3",
          "reason" to "Planning subtask 3.",
        ),
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-165 is planning subtasks (2/5 planned).",
      ),
      "golden-goal-planning",
    )
  }

  @Test
  fun `feature-goal pause-requested-not-consumed golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-168",
        "workflow_id" to "goal-3",
        "workflow_family" to "feature-goal",
        // Still genuinely running its current subtask; the request is a modifier, not a lifecycle.
        "lifecycle_state" to "active",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "pause_requested" to true,
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-168 is active on Implement.",
      ),
      "golden-goal-pause-requested",
    )
  }

  @Test
  fun `feature-goal pause-consumed-with-timestamp golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-168",
        "workflow_id" to "goal-4",
        "workflow_family" to "feature-goal",
        "lifecycle_state" to "paused",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "paused_at" to "2026-08-06T09:45:00Z",
        "updated_at" to "2026-08-06T10:00:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-168 is paused.",
      ),
      "golden-goal-pause-consumed",
    )
  }

  @Test
  fun `feature-goal lease-expired-without-timestamp golden validates`() {
    IdeStatusSchemaValidator.validate(
      linkedMapOf(
        "contract_version" to IDE_STATUS_CONTRACT_VERSION,
        "repository_identity" to "repo-root-realpath-v1:/repo",
        "issue_key" to "SKILL-168",
        "workflow_id" to "goal-5",
        "workflow_family" to "feature-goal",
        // Paused by lease-expiry inference: no durable pause record, so no paused_at.
        // updated_at carries the inferred stop anchor instead.
        "lifecycle_state" to "paused",
        "current_step" to linkedMapOf("id" to "implement", "label" to "Implement"),
        "progress" to linkedMapOf("completed" to 1, "total" to 3),
        "started_at" to "2026-08-06T08:00:00Z",
        "updated_at" to "2026-08-06T09:50:00Z",
        "freshness" to "fresh",
        "summary" to "Goal SKILL-168 is paused.",
      ),
      "golden-goal-lease-expired",
    )
  }

  @Test
  fun `typed problem goldens validate`() {
    listOf(
      "missing_repository_identity",
      "absent_database",
      "no_matching_work",
      "incompatible_record",
      "invalid_repository_input",
      "schema_incompatible",
    ).forEach { code ->
      IdeStatusSchemaValidator.validate(
        linkedMapOf(
          "contract_version" to IDE_STATUS_CONTRACT_VERSION,
          "repository_identity" to "repo-root-realpath-v1:/repo",
          "lifecycle_state" to "idle",
          "current_step" to linkedMapOf("id" to "none", "label" to "Unavailable"),
          "updated_at" to "2026-08-06T10:00:00Z",
          "freshness" to "unknown",
          "summary" to "Unavailable: $code",
          "problem" to linkedMapOf("code" to code, "message" to "Unavailable: $code"),
        ),
        "golden-problem-$code",
      )
    }
  }
}
