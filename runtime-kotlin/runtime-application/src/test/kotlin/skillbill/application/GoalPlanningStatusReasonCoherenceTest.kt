package skillbill.application

import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.goalrunner.GoalPlanningProvenanceRecoverability
import skillbill.application.goalrunner.alignPlanningStatusWithLaunchRecoverability
import skillbill.application.goalrunner.classifyGoalPlanningProvenanceRecoverability
import skillbill.application.goalrunner.goalPlanningIncludeSharedPreplanRemedy
import skillbill.application.goalrunner.goalPlanningIncompatibleProvenanceStopReason
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.goalrunner.model.GoalPlanningStatusReasons
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bug this catches: status claims "planning can resume at subtask N" for durable planning that launch
 * would refuse (WE-4719: PARTIALLY_PLANNED + invalid provenance still advertised resume).
 */
class GoalPlanningStatusReasonCoherenceTest {
  @Test
  fun `invalid provenance overlays resume claim with the exact replan remedy`() {
    val snapshot = GoalPlanningStatusSnapshot(
      state = GoalPlanningStatusState.PARTIALLY_PLANNED,
      sharedPreplanPrepared = true,
      plannedSubtaskCount = 1,
      totalSubtaskCount = 2,
      currentPlanningSubtaskId = 2,
      reason = GoalPlanningStatusReasons.partiallyPlannedResume(2),
    )

    val aligned = alignPlanningStatusWithLaunchRecoverability(
      snapshot = snapshot,
      recoverability = GoalPlanningProvenanceRecoverability.Invalid,
      issueKey = "WE-4719",
      remedySubtaskId = 2,
    )

    assertFalse(GoalPlanningStatusReasons.claimsResume(aligned.reason), aligned.reason)
    assertTrue(
      aligned.reason!!.contains(goalPlanningIncludeSharedPreplanRemedy("WE-4719", 2)),
      aligned.reason,
    )
  }

  @Test
  fun `reuse keeps the resumable planning reason`() {
    val snapshot = GoalPlanningStatusSnapshot(
      state = GoalPlanningStatusState.PREPLANNED,
      sharedPreplanPrepared = true,
      plannedSubtaskCount = 0,
      totalSubtaskCount = 2,
      currentPlanningSubtaskId = 1,
      reason = GoalPlanningStatusReasons.preplannedResume(1),
    )
    val provenance = GoalPlanningContractProvenance(
      parentSpecHash = "a".repeat(64),
      decompositionManifestHash = "b".repeat(64),
      planningContractId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
    )

    val aligned = alignPlanningStatusWithLaunchRecoverability(
      snapshot = snapshot,
      recoverability = GoalPlanningProvenanceRecoverability.Reuse(provenance),
      issueKey = "SKILL-181",
      remedySubtaskId = 1,
    )

    assertEquals(snapshot.reason, aligned.reason)
  }

  @Test
  fun `stale-valid keeps resume reason because launch refreshes rather than refuses`() {
    val snapshot = GoalPlanningStatusSnapshot(
      state = GoalPlanningStatusState.PARTIALLY_PLANNED,
      sharedPreplanPrepared = true,
      plannedSubtaskCount = 1,
      totalSubtaskCount = 2,
      currentPlanningSubtaskId = 2,
      reason = GoalPlanningStatusReasons.partiallyPlannedResume(2),
    )
    val provenance = GoalPlanningContractProvenance(
      parentSpecHash = "a".repeat(64),
      decompositionManifestHash = "b".repeat(64),
      planningContractId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
    )

    val aligned = alignPlanningStatusWithLaunchRecoverability(
      snapshot = snapshot,
      recoverability = GoalPlanningProvenanceRecoverability.StaleValid(provenance),
      issueKey = "SKILL-181",
      remedySubtaskId = 2,
    )

    assertEquals(snapshot.reason, aligned.reason)
  }

  @Test
  fun `classifier invalid for drifted decomposition aligns with stop remedy text`() {
    val parentSpec = "# Parent"
    val checkpoint = SharedGoalPreplanCheckpoint(
      identity = GoalPlanningIdentity("wfl", "SKILL-181", "repo-root-realpath-v1:/tmp"),
      preparationStatus = GoalPlanningPreparationState.PREPARED,
      provenance = GoalPlanningContractProvenance(
        parentSpecHash = sha256HexUtf8(parentSpec),
        decompositionManifestHash = "stored-manifest",
        planningContractId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
        phaseOutputContractId = FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
      ),
      payloadSha256 = sha256HexUtf8(phasePayload("preplan")),
      preplanPayload = phasePayload("preplan"),
    )
    val current = checkpoint.provenance.copy(decompositionManifestHash = "drifted-manifest")

    val recoverability = classifyGoalPlanningProvenanceRecoverability(
      existing = checkpoint,
      current = current,
      savedParentSpec = parentSpec,
      currentParentSpec = parentSpec,
      freshCatalogHeadingIds = emptySet(),
    )
    assertIs<GoalPlanningProvenanceRecoverability.Invalid>(recoverability)

    val stopReason = goalPlanningIncompatibleProvenanceStopReason("SKILL-181", 1)
    assertTrue(stopReason.contains(goalPlanningIncludeSharedPreplanRemedy("SKILL-181", 1)))
    assertFalse(stopReason.contains("cannot be recovered"))
  }

  private fun phasePayload(phase: String): String = """
    {
      "contract_version": "0.3",
      "phase_id": "$phase",
      "status": "completed",
      "summary": "fixture",
      "produced_outputs": { "ok": true }
    }
  """.trimIndent()
}
