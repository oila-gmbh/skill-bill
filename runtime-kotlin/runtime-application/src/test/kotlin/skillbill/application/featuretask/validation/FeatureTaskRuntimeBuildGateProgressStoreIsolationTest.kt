package skillbill.application.featuretask.validation

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeatureTaskRuntimeBuildGateProgressStoreIsolationTest {
  @Test
  fun `build gate progress store persists to build artifact without overwriting validation progress`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val database = RuntimeFakeDatabaseSessionFactory(repository)
    val recorder = FeatureTaskRuntimePhaseRecorder(
      database,
      testWorkflowSnapshotValidator,
      AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
      AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
    )
    val workflowId = "wf-build-gate-isolation"
    recorder.ensureWorkflowOpen(workflowId, "session-1")

    val validationProgress = FeatureTaskRuntimeValidationGateProgress(
      gateRunCount = 2,
      gateRuns = listOf(
        gateRunRecord(outcome = "failed"),
        gateRunRecord(outcome = "failed"),
      ),
    )
    val buildProgress = FeatureTaskRuntimeValidationGateProgress(
      gateRunCount = 1,
      gateRuns = listOf(gateRunRecord(outcome = "passed")),
    )

    FeatureTaskRuntimeValidationGateProgressStore(recorder).persist(workflowId, validationProgress, dbOverride = null)
    FeatureTaskRuntimeBuildGateProgressStore(recorder).persist(workflowId, buildProgress, dbOverride = null)

    val reloadedValidation = assertNotNull(recorder.loadValidationGateProgress(workflowId))
    val reloadedBuild = assertNotNull(recorder.loadBuildGateProgress(workflowId))
    assertEquals(2, reloadedValidation.gateRunCount)
    assertEquals("failed", reloadedValidation.gateRuns.last().outcome)
    assertEquals(1, reloadedBuild.gateRunCount)
    assertEquals("passed", reloadedBuild.gateRuns.single().outcome)
  }

  private fun gateRunRecord(outcome: String): FeatureTaskRuntimeValidationGateRunRecord =
    FeatureTaskRuntimeValidationGateRunRecord(
      durationMs = 1,
      outcome = outcome,
      cacheMode = "warm",
      executedWorkUnits = 1,
    )
}
