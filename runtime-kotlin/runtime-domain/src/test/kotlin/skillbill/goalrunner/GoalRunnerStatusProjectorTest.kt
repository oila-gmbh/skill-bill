package skillbill.goalrunner

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
  }

  @Test
  fun `a subtask with no live child keeps its durable blocked status`() {
    val projection = GoalRunnerStatusProjector.project(
      manifest = manifest(currentSubtaskStatus = "blocked"),
      extras = GoalRunnerStatusProjectionExtras(currentWorkflowStatus = "blocked"),
    )

    assertEquals(1, projection.blockedCount)
    assertEquals(0, projection.pendingCount)
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
      ),
    ),
  )
}
