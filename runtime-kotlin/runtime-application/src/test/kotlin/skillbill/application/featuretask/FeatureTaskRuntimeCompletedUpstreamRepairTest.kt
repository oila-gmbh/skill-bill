package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeCompletedUpstreamRepairTest {
  @Test
  fun `diagnose returns review when implement_fix is blocked on missing settled output`() {
    val phaseRecords = mapOf(
      "review" to phaseRecord(
        phaseId = "review",
        status = "completed",
        outputArtifact = null,
      ),
      "implement_fix" to phaseRecord(
        phaseId = "implement_fix",
        status = "blocked",
        blockedReason = "missing review output",
      ),
    )

    assertEquals(
      "review",
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
      ),
    )
  }

  @Test
  fun `diagnose is null when completed upstream has settled output`() {
    val phaseRecords = mapOf(
      "review" to phaseRecord(
        phaseId = "review",
        status = "completed",
        outputArtifact = """{"contract_version":"0.1","findings":[]}""",
      ),
      "implement_fix" to phaseRecord(
        phaseId = "implement_fix",
        status = "blocked",
        blockedReason = "other",
      ),
    )

    assertNull(
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
      ),
    )
  }

  @Test
  fun `diagnose returns build when build-stamped write_history is blocked on missing build output`() {
    val phaseRecords = mapOf(
      "review" to completedPhaseRecord("review"),
      "build" to phaseRecord(
        phaseId = "build",
        status = "completed",
        outputArtifact = null,
      ),
      "write_history" to phaseRecord(
        phaseId = "write_history",
        status = "blocked",
        blockedReason = "Phase 'write_history' requires upstream output(s) build that are not present",
      ),
    )

    assertEquals(
      "build",
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
      ),
    )
  }

  @Test
  fun `diagnose does not return validate when build-stamped child lacks settled build output`() {
    val phaseRecords = mapOf(
      "review" to completedPhaseRecord("review"),
      "build" to phaseRecord(
        phaseId = "build",
        status = "completed",
        outputArtifact = null,
      ),
      "validate" to phaseRecord(
        phaseId = "validate",
        status = "completed",
        outputArtifact = """{"contract_version":"0.1"}""",
      ),
      "write_history" to phaseRecord(
        phaseId = "write_history",
        status = "blocked",
        blockedReason = "Phase 'write_history' requires upstream output(s) build that are not present",
      ),
    )

    assertEquals(
      "build",
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
      ),
    )
  }

  @Test
  fun `diagnose returns blocked consumer when upstream block reason is stale`() {
    val phaseRecords = mapOf(
      "review" to phaseRecord(
        phaseId = "review",
        status = "completed",
        outputArtifact = """{"contract_version":"0.1","findings":[]}""",
      ),
      "implement_fix" to phaseRecord(
        phaseId = "implement_fix",
        status = "blocked",
        blockedReason = "Phase 'implement_fix' requires upstream output(s) review that are not present",
      ),
    )

    assertEquals(
      "implement_fix",
      diagnoseUnsettledCompletedUpstreamPhaseId(phaseRecords, FeatureTaskRuntimeFeatureSize.MEDIUM),
    )
  }

  private fun phaseRecord(
    phaseId: String,
    status: String,
    outputArtifact: String? = null,
    blockedReason: String? = null,
  ): FeatureTaskRuntimePhaseRecord = FeatureTaskRuntimePhaseRecord(
    phaseId = phaseId,
    status = status,
    attemptCount = 1,
    startedAt = "2026-08-19T10:00:00Z",
    resolvedAgentId = "cursor",
    outputArtifact = outputArtifact,
    blockedReason = blockedReason,
    loopId = "review_fix",
    edgeIteration = 1,
  )
}
