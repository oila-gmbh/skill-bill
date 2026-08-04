package skillbill.application

import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `an attempt that cannot be persisted reports false rather than a silent no-op`() {
    // AC-007: the run loop blocks the phase with PROCESS_FAILURE on this false. If the append could
    // report success it never had, the continuation projection and the durable segment budget would
    // both be lost and the continuation loop would stop being bounded across process lifetimes.
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = 1))

    val persisted = harness.recorder.recordIncompleteImplementationAttempt(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = "wftr-no-such-workflow",
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
      ),
    )

    assertFalse(persisted, "an append against an absent workflow row must report failure, not success")
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
