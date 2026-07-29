package skillbill.goalrunner

import skillbill.goalrunner.model.GoalRunnerStatusEvent
import skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras
import skillbill.goalrunner.model.GoalRunnerStatusProjector
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalRunnerStatusProjectorTest {
  @Test
  fun `a relaunched subtask is not counted as blocked while its child workflow runs`() {
    val projection = GoalRunnerStatusProjector.project(
      manifest = manifest(currentSubtaskStatus = "blocked"),
      extras = GoalRunnerStatusProjectionExtras(currentWorkflowStatus = "running"),
    )

    assertEquals(0, projection.blockedCount)
    assertEquals(1, projection.pendingCount)
    assertNull(projection.blockedReason)
  }

  @Test
  fun `a subtask with no live child keeps its durable blocked status`() {
    val projection = GoalRunnerStatusProjector.project(
      manifest = manifest(currentSubtaskStatus = "blocked"),
      extras = GoalRunnerStatusProjectionExtras(currentWorkflowStatus = "blocked"),
    )

    assertEquals(1, projection.blockedCount)
    assertEquals(0, projection.pendingCount)
    assertEquals("branch checkout failed", projection.blockedReason)
  }

  // Only supervisor events are persisted, so a block recorded when a prior run stopped is still the newest
  // stored event while a relaunched child runs. Rendering it would contradict the live workflow status.
  @Test
  fun `a block liveness signal is withheld while the child workflow runs`() {
    val projection = GoalRunnerStatusProjector.project(
      manifest = manifest(currentSubtaskStatus = "blocked"),
      extras = GoalRunnerStatusProjectionExtras(
        currentWorkflowStatus = "running",
        latestLivenessSignal = "liveness=block phase=review role=goal_runner_supervisor",
        latestObservabilityEvent = mapOf("liveness_class" to "block"),
      ),
    )

    assertNull(projection.latestLivenessSignal)
    assertNull(projection.latestObservabilityEvent)
  }

  @Test
  fun `a non-block liveness signal is preserved while the child workflow runs`() {
    val projection = GoalRunnerStatusProjector.project(
      manifest = manifest(currentSubtaskStatus = "in_progress"),
      extras = GoalRunnerStatusProjectionExtras(
        currentWorkflowStatus = "running",
        latestLivenessSignal = "liveness=durable_progress phase=implement",
        latestObservabilityEvent = mapOf("liveness_class" to "durable_progress"),
      ),
    )

    assertEquals("liveness=durable_progress phase=implement", projection.latestLivenessSignal)
    assertEquals(mapOf("liveness_class" to "durable_progress"), projection.latestObservabilityEvent)
  }

  @Test
  fun `a stored block signal is reported once the child workflow is no longer running`() {
    val projection = GoalRunnerStatusProjector.project(
      manifest = manifest(currentSubtaskStatus = "blocked"),
      extras = GoalRunnerStatusProjectionExtras(
        currentWorkflowStatus = "blocked",
        latestLivenessSignal = "liveness=block phase=review role=goal_runner_supervisor",
        latestObservabilityEvent = mapOf("liveness_class" to "block"),
      ),
    )

    assertEquals("liveness=block phase=review role=goal_runner_supervisor", projection.latestLivenessSignal)
  }

  @Test
  fun `an audit failure becomes historical after the workflow advances to review`() {
    val projection = GoalRunnerStatusProjector.project(
      manifest = manifest(currentSubtaskStatus = "in_progress"),
      extras = GoalRunnerStatusProjectionExtras(
        currentStepOverride = "review",
        currentWorkflowStatus = "running",
        latestLivenessSignal = "liveness=phase_change phase=review",
        latestObservabilityEvent = mapOf(
          "workflow_phase" to "review",
          "liveness_class" to "phase_change",
          "sequence_number" to 12,
        ),
        latestFailureEvent = GoalRunnerStatusEvent(
          workflowPhase = "audit",
          activitySummary = "stdout_chars=1387312; stderr_chars=42; exit_status=1",
          timestamp = "2026-07-29T17:25:47.263781494Z",
          sequenceNumber = 10,
          attemptCount = 3,
        ),
        latestFailureAttempt = 3,
        currentAttempt = 4,
      ),
    )

    assertEquals("liveness=phase_change phase=review", projection.latestLivenessSignal)
    assertEquals("review", projection.latestObservabilityEvent?.get("workflow_phase"))
    assertEquals("audit", projection.lastFailure?.phase)
    assertEquals(3, projection.lastFailure?.attempt)
    assertEquals("2026-07-29T17:25:47.263781494Z", projection.lastFailure?.timestamp)
    assertEquals(false, projection.lastFailure?.current)
  }

  @Test
  fun `a retained failure stays visible after a newer healthy event without becoming current`() {
    val projection = GoalRunnerStatusProjector.project(
      manifest = manifest(currentSubtaskStatus = "in_progress"),
      extras = GoalRunnerStatusProjectionExtras(
        currentStepOverride = "review",
        currentWorkflowStatus = "running",
        latestObservabilityEvent = mapOf(
          "workflow_phase" to "review",
          "liveness_class" to "heartbeat",
          "sequence_number" to 15,
        ),
        latestFailureEvent = GoalRunnerStatusEvent(
          workflowPhase = "review",
          activitySummary = "review worker exited",
          timestamp = "2026-07-29T18:00:00Z",
          sequenceNumber = 14,
          attemptCount = 1,
        ),
        latestFailureAttempt = 1,
        currentAttempt = 2,
      ),
    )

    assertEquals("review", projection.lastFailure?.phase)
    assertEquals(1, projection.lastFailure?.attempt)
    assertEquals(false, projection.lastFailure?.current)
  }

  private fun manifest(currentSubtaskStatus: String): DecompositionManifest = DecompositionManifest(
    issueKey = "SKILL-135",
    featureName = "audit-first-review-gate",
    parentSpecPath = ".feature-specs/SKILL-135/spec.md",
    baseBranch = "main",
    featureBranch = "feat/SKILL-135-audit-first-review-gate",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
    subtasks = listOf(
      DecompositionSubtask(
        id = 1,
        name = "Only subtask",
        specPath = ".feature-specs/SKILL-135/spec_subtask_1.md",
        status = currentSubtaskStatus,
        workflowId = "wftr-20260720-192238-iwxj",
        blockedReason = "branch checkout failed",
      ),
    ),
  )
}
