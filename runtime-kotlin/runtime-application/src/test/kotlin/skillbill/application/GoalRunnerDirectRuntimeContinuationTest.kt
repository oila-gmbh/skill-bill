package skillbill.application

import skillbill.application.goalrunner.GoalRunnerLaunchReconciler
import skillbill.application.workflow.repoRoot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerDirectRuntimeContinuationTest {
  @Test
  fun `reconciler threads direct runtime goal-continuation context onto the child launch request`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wfl-child-runtime"),
    )
    val reconciler = GoalRunnerLaunchReconciler(
      manifestStore = store,
      subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
      outcomeStore = RecordingOutcomeStore(),
    )

    val launchRequest = reconciler.subtaskLaunchRequest("SKILL-56", subtaskId = 1, request = wiringRunRequest())
    val context = requireNotNull(launchRequest.skillRunRequest.goalContinuation)

    assertEquals("SKILL-56", context.parentIssueKey)
    assertEquals(1, context.subtaskId)
    assertEquals("feat/SKILL-56-goal", context.goalBranch)
    assertTrue(context.suppressPr)
    assertEquals(".feature-specs/SKILL-56-goal/spec_subtask_1.md", context.specPath)
    assertEquals("wfl-child-runtime", context.childWorkflowId)
    assertNull(launchRequest.skillRunRequest.promptOverride)
  }

  private fun wiringRunRequest(): skillbill.application.model.GoalRunnerRunRequest =
    skillbill.application.model.GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
      dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
    )
}
