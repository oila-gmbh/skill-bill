package skillbill.application

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationAttemptAtomicityTest {
  @Test
  fun `a kill during the write persists neither the attempt nor the advance`() {
    val repository = InMemoryRuntimeWorkflowRepository().apply {
      failSaveWhen = { row -> row.artifactsJson.contains(FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_ARTIFACT_KEY) }
    }
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = convergingImplementLauncher(closeAllOnSegment = 1),
      repository = repository,)))

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
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = convergingImplementLauncher(closeAllOnSegment = 1))))

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
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = convergingImplementLauncher(closeAllOnSegment = 1))))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    val attempts = harness.recorder.loadImplementationAttempts(WORKFLOW_ID).orEmpty()
      .filter { it.phaseId == "implement" }

    assertEquals("completed", implementRecord.status)
    assertEquals(1, attempts.size, "exactly one resumable attempt, never a duplicate")
    assertEquals(FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED, attempts.single().status)
    assertTrue(
      attempts.single().value.isNotBlank(),
      "the durable attempt carries the non-blank value the advance was granted for",
    )
  }
}
