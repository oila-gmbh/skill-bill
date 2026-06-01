package skillbill.cli

import skillbill.application.model.WorkflowGetResult
import skillbill.contracts.workflow.GoalObservabilityEventSchemaValidator
import skillbill.error.InvalidGoalObservabilityEventSchemaError
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import skillbill.workflow.model.WorkflowSnapshotView
import skillbill.workflow.model.WorkflowStepState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkflowCliResultMappersTest {
  @Test
  fun `goal diff mapper exposes bounded selected hunk wire shape`() {
    val diffStat = GoalObservabilityDiffStat(filesChanged = 1, insertions = 2, deletions = 1).toGoalDiffStatCliMap()
    val hunks = GoalObservabilitySelectedDiffHunks(
      hunks = listOf(
        GoalObservabilitySelectedDiffHunk(
          path = "runtime.txt",
          staged = false,
          header = "@@ -1 +1 @@",
          lines = listOf("-old", "+new"),
          truncated = false,
        ),
      ),
      truncated = false,
    ).toGoalSelectedDiffHunksCliMap()

    assertEquals(1, diffStat["files_changed"])
    assertEquals(2, diffStat["insertions"])
    assertEquals(1, diffStat["deletions"])
    assertEquals(false, hunks["truncated"])
    val firstHunk = (hunks["hunks"] as List<*>).single() as Map<*, *>
    assertEquals("runtime.txt", firstHunk["path"])
    assertEquals(listOf("-old", "+new"), firstHunk["lines"])
  }

  @Test
  fun `workflow mapper exposes compact goal observability summary without heavy fields`() {
    val mapped = WorkflowGetResult.Ok(
      workflowId = "wfl-1",
      dbPath = "/tmp/metrics.db",
      snapshot = snapshotWithObservability(),
    ).toCliMap(testGoalObservabilityEventValidator)

    val observability = mapped["goal_observability"] as Map<*, *>
    assertEquals("implement", observability["workflow_phase"])
    assertEquals("phase_subagent", observability["worker_role"])
    assertEquals("durable_progress", observability["liveness_class"])
    assertFalse(observability.containsKey("changed_files"))
  }

  @Test
  fun `workflow mapper loud-fails malformed goal observability latest event`() {
    val error = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(
          event = mapOf(
            "contract_version" to "0.1",
            "subtask_id" to 1,
            "workflow_phase" to "implement",
            "worker_role" to "phase_subagent",
            "liveness_class" to "durable_progress",
            "activity_summary" to "editing",
            "sequence_number" to 1,
            "timestamp" to "2026-06-01T00:00:00Z",
          ),
        ),
      ).toCliMap(testGoalObservabilityEventValidator)
    }

    assertEquals("", error.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails schema-invalid extra goal observability field`() {
    val error = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("unknown" to true)),
      ).toCliMap(testGoalObservabilityEventValidator)
    }

    assertEquals("", error.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails malformed optional goal observability summary`() {
    val error = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(
          event = snapshotWithObservabilityEvent() + ("changed_file_summary" to "not-an-object"),
        ),
      ).toCliMap(testGoalObservabilityEventValidator)
    }

    assertEquals("changed_file_summary", error.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails malformed optional goal observability arrays`() {
    val changedFilesError = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(
          event = snapshotWithObservabilityEvent() + ("changed_files" to listOf(123)),
        ),
      ).toCliMap(testGoalObservabilityEventValidator)
    }
    assertEquals("changed_files[0]", changedFilesError.fieldPath)

    val samplePathsError = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(
          event = snapshotWithObservabilityEvent() + (
            "changed_file_summary" to mapOf(
              "total" to 1,
              "added" to 0,
              "modified" to 1,
              "deleted" to 0,
              "renamed" to 0,
              "untracked" to 0,
              "sample_paths" to "not-an-array",
            )
            ),
        ),
      ).toCliMap(testGoalObservabilityEventValidator)
    }
    assertEquals("changed_file_summary.sample_paths", samplePathsError.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails schema-invalid scalar coercion`() {
    val issueKeyError = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("issue_key" to 61)),
      ).toCliMap(testGoalObservabilityEventValidator)
    }
    assertEquals("issue_key", issueKeyError.fieldPath)

    val subtaskIdError = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("subtask_id" to "1")),
      ).toCliMap(testGoalObservabilityEventValidator)
    }
    assertEquals("subtask_id", subtaskIdError.fieldPath)

    val timestampError = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(event = snapshotWithObservabilityEvent() + ("timestamp" to 20260601)),
      ).toCliMap(testGoalObservabilityEventValidator)
    }
    assertEquals("timestamp", timestampError.fieldPath)
  }

  @Test
  fun `workflow mapper loud-fails schema-only invalid heavy goal observability fields`() {
    val error = assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      WorkflowGetResult.Ok(
        workflowId = "wfl-1",
        dbPath = "/tmp/metrics.db",
        snapshot = snapshotWithObservability(
          event = snapshotWithObservabilityEvent() + ("changed_files" to List(501) { "file-$it.kt" }),
        ),
      ).toCliMap(testGoalObservabilityEventValidator)
    }

    assertEquals("changed_files", error.fieldPath)
  }

  private fun snapshotWithObservability(
    event: Map<String, Any?> = snapshotWithObservabilityEvent(),
  ): WorkflowSnapshotView = WorkflowSnapshotView(
    workflowId = "wfl-1",
    sessionId = "fis-1",
    workflowName = "bill-feature-task",
    contractVersion = "0.1",
    workflowStatus = "running",
    currentStepId = "implement",
    steps = listOf(WorkflowStepState("implement", "running", 1)),
    artifacts = mapOf(
      "goal_observability_latest_event" to event,
    ),
    startedAt = "2026-06-01 00:00:00",
    updatedAt = "2026-06-01 00:00:00",
    finishedAt = "",
  )

  private fun snapshotWithObservabilityEvent(): Map<String, Any?> = mapOf(
    "contract_version" to "0.1",
    "issue_key" to "SKILL-61",
    "subtask_id" to 1,
    "workflow_phase" to "implement",
    "worker_role" to "phase_subagent",
    "liveness_class" to "durable_progress",
    "activity_summary" to "editing",
    "sequence_number" to 1,
    "timestamp" to "2026-06-01T00:00:00Z",
    "changed_files" to listOf("heavy.kt"),
  )

  private val testGoalObservabilityEventValidator: GoalObservabilityEventValidator =
    object : GoalObservabilityEventValidator {
      override fun validate(event: Map<String, Any?>, sourceLabel: String) {
        GoalObservabilityEventSchemaValidator.validate(event, sourceLabel)
      }
    }
}
