package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-150 subtask 1: the attempt receipt and the workflow advance ride one write. A process killed
 * on either side of that write leaves exactly one resumable attempt and no lost obligation.
 */
class FeatureTaskRuntimeImplementationAttemptAtomicityTest {
  @Test
  fun `a kill during the write persists neither the attempt nor the advance`() {
    val repository = InMemoryRuntimeWorkflowRepository().apply {
      failSaveWhen = { row -> row.artifactsJson.contains("implementation_receipt") }
    }
    val harness = runnerHarness(
      launcher = convergingImplementLauncher(closeAllOnSegment = 1),
      repository = repository,
    )

    runCatching { harness.runner.run(harness.request()) }
    repository.failSaveWhen = null

    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertTrue(
      records["implement"]?.status != "completed",
      "the implement advance must not survive a write that never committed",
    )
    assertTrue(
      harness.recorder.loadImplementationAttempts(WORKFLOW_ID).orEmpty().isEmpty(),
      "no attempt may survive a write that never committed",
    )
  }

  @Test
  fun `a completed write leaves the attempt and the advance agreeing`() {
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = 1))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    val attempts = harness.recorder.loadImplementationAttempts(WORKFLOW_ID).orEmpty()
      .filter { it.phaseId == "implement" }

    assertEquals("completed", implementRecord.status)
    assertEquals(1, attempts.size, "exactly one resumable attempt, never a duplicate")
    assertEquals(FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED, attempts.single().status)
    assertEquals(
      listOf("task-1", "task-2", "task-3"),
      attempts.single().completedTaskIds,
      "the durable attempt carries every obligation the advance was granted for",
    )
  }
}
