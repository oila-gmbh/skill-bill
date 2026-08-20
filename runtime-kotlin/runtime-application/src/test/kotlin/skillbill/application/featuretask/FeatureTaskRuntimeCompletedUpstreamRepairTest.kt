package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeCompletedUpstreamRepairTest {
  @Test
  fun `diagnose returns plan_fix when implement_fix is blocked on missing settled output`() {
    val phaseRecords = mapOf(
      "review" to completedPhaseRecord("review"),
      "plan_fix" to phaseRecord(
        phaseId = "plan_fix",
        status = "completed",
        outputArtifact = null,
      ),
      "implement_fix" to phaseRecord(
        phaseId = "implement_fix",
        status = "blocked",
        blockedReason = "missing plan_fix output",
      ),
    )

    assertEquals(
      "plan_fix",
      diagnoseUnsettledCompletedUpstreamPhaseId(
        phaseRecords,
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize.MEDIUM,
      ),
    )
  }

  @Test
  fun `diagnose is null when completed upstream has settled output`() {
    val phaseRecords = mapOf(
      "review" to completedPhaseRecord("review"),
      "plan_fix" to phaseRecord(
        phaseId = "plan_fix",
        status = "completed",
        outputArtifact = """{"repair_plan":{"contract_version":"0.1","round_number":1,"entries":[]}}""",
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
        skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize.MEDIUM,
      ),
    )
  }

  @Test
  fun `diagnose returns blocked consumer when upstream block reason is stale`() {
    val phaseRecords = mapOf(
      "review" to completedPhaseRecord("review"),
      "plan_fix" to phaseRecord(
        phaseId = "plan_fix",
        status = "completed",
        outputArtifact = """{"repair_plan":{"contract_version":"0.1","round_number":1,"entries":[]}}""",
      ),
      "implement_fix" to phaseRecord(
        phaseId = "implement_fix",
        status = "blocked",
        blockedReason = "Phase 'implement_fix' requires upstream output(s) plan_fix that are not present",
      ),
    )

    assertEquals(
      "implement_fix",
      diagnoseUnsettledCompletedUpstreamPhaseId(phaseRecords, skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize.MEDIUM),
    )
  }

  private fun completedPhaseRecord(phaseId: String): FeatureTaskRuntimePhaseRecord = phaseRecord(
    phaseId = phaseId,
    status = "completed",
    outputArtifact = """{"contract_version":"0.1"}""",
  )

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
