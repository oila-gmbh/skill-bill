package skillbill.application.goalrunner

import skillbill.goalrunner.model.GoalPlanningStatusReasons
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState

/** Copy-pasteable operator remedy for incompatible shared-preplan provenance. */
internal fun goalPlanningIncludeSharedPreplanRemedy(issueKey: String, subtaskId: Int): String =
  "skill-bill goal replan $issueKey --subtask $subtaskId --include-shared-preplan"

internal fun goalPlanningIncompatibleProvenanceStopReason(issueKey: String, subtaskId: Int): String =
  "Goal planning shared preplan provenance is incompatible with the current governed inputs. " +
    "Recover with: ${goalPlanningIncludeSharedPreplanRemedy(issueKey, subtaskId)}"

internal fun goalPlanningNonResumableStatusReason(issueKey: String, subtaskId: Int): String =
  "Saved planning is not resumable until provenance is repaired. Recover with: " +
    goalPlanningIncludeSharedPreplanRemedy(issueKey, subtaskId)

/**
 * Replaces resume-claiming status reasons when launch would refuse the same durable planning state.
 * [GoalPlanningProvenanceRecoverability.StaleValid] is not refused after in-run refresh; only Invalid
 * overlays the reason.
 */
internal fun alignPlanningStatusWithLaunchRecoverability(
  snapshot: GoalPlanningStatusSnapshot,
  recoverability: GoalPlanningProvenanceRecoverability,
  issueKey: String,
  remedySubtaskId: Int,
): GoalPlanningStatusSnapshot {
  if (recoverability !is GoalPlanningProvenanceRecoverability.Invalid) return snapshot
  if (
    snapshot.state != GoalPlanningStatusState.PREPLANNED &&
    snapshot.state != GoalPlanningStatusState.PARTIALLY_PLANNED
  ) {
    return snapshot
  }
  if (!GoalPlanningStatusReasons.claimsResume(snapshot.reason)) return snapshot
  return snapshot.copy(reason = goalPlanningNonResumableStatusReason(issueKey, remedySubtaskId))
}
