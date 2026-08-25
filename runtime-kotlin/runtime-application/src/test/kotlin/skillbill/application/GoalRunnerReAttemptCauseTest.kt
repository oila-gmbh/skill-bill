package skillbill.application

import skillbill.application.goalrunner.GoalRunner
import skillbill.goalrunner.model.GoalRunnerStopReason
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerReAttemptCauseTest {
  private fun runner(): GoalRunner = GoalRunner(
    InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1)),
    RecordingSubtaskLauncher { launchFacts() },
    RecordingOutcomeStore(),
    RecordingPullRequestPort(),
  )

  @Test
  fun `retired regenerate_plan loop on a reconciled resumable stop classifies as crash_resume`() {
    assertEquals(
      "crash_resume",
      runner().reAttemptCauseFor(GoalRunnerStopReason.RECONCILED_RESUMABLE, mapOf("regenerate_plan" to 2)),
    )
  }

  @Test
  fun `retired regenerate_implement loop also classifies as crash_resume`() {
    assertEquals(
      "crash_resume",
      runner().reAttemptCauseFor(GoalRunnerStopReason.RECONCILED_RESUMABLE, mapOf("regenerate_implement" to 1)),
    )
  }

  @Test
  fun `non-regeneration loop on a reconciled resumable stop classifies as crash_resume`() {
    assertEquals(
      "crash_resume",
      runner().reAttemptCauseFor(GoalRunnerStopReason.RECONCILED_RESUMABLE, mapOf("review_fix" to 1)),
    )
  }

  @Test
  fun `backward edge on a non-resumable stop classifies as backward_edge`() {
    assertEquals(
      "backward_edge",
      runner().reAttemptCauseFor(GoalRunnerStopReason.BLOCKED, mapOf("audit_gap" to 1)),
    )
  }

  @Test
  fun `stop with no loops yields no re-attempt cause`() {
    assertEquals(null, runner().reAttemptCauseFor(GoalRunnerStopReason.TIMEOUT, emptyMap()))
  }
}
