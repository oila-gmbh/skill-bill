package skillbill.application.goalrunner

import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.model.GoalPlanningStatusReasons
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState

/** Copy-pasteable operator remedy for incompatible shared-preplan provenance. */
internal fun goalPlanningIncludeSharedPreplanRemedy(issueKey: String, subtaskId: Int): String =
  "skill-bill goal replan $issueKey --subtask $subtaskId --include-shared-preplan"

internal fun goalPlanningIncompatibleProvenanceStopReason(issueKey: String, subtaskId: Int): String =
  "Goal planning shared preplan provenance is incompatible with the current governed inputs. " +
    "Recover with: ${goalPlanningIncludeSharedPreplanRemedy(issueKey, subtaskId)}"

internal fun goalPlanningMissingSharedContextPacketStopReason(issueKey: String, subtaskId: Int): String =
  "Goal planning shared preplan does not contain a valid shared context packet. " +
    "Recover with: ${goalPlanningIncludeSharedPreplanRemedy(issueKey, subtaskId)}"

/**
 * Surviving preparation-state hard stop. Uses [IncompatibleGoalPlanningPreparationRecoveryError.reason]
 * rather than [Throwable.message] so the stop does not claim the state "cannot be recovered" when
 * `--include-shared-preplan` is the documented recovery path.
 */
internal fun goalPlanningPreparationStateReadStopReason(error: Throwable, issueKey: String, subtaskId: Int): String {
  val recovery = error as? IncompatibleGoalPlanningPreparationRecoveryError
    ?: return "Goal planning preparation state could not be read: ${error.message.orEmpty()}"
  val remedySubtaskId = when {
    subtaskId > 0 -> subtaskId
    recovery.subtaskId > 0 -> recovery.subtaskId
    else -> 1
  }
  return "Goal planning preparation state could not be read: ${recovery.reason}. " +
    "Recover with: ${goalPlanningIncludeSharedPreplanRemedy(issueKey, remedySubtaskId)}"
}

internal fun goalPlanningNonResumableStatusReason(issueKey: String, subtaskId: Int): String =
  "Saved planning is not resumable until provenance is repaired. Recover with: " +
    goalPlanningIncludeSharedPreplanRemedy(issueKey, subtaskId)

/**
 * Launch refuses when shared-preplan classification cannot complete (identity mismatch, schema
 * drift). Status must treat that failure as [GoalPlanningProvenanceRecoverability.Invalid] so a
 * resume-claiming `planning_reason` cannot survive.
 */
internal fun statusRecoverabilityOrRefuse(
  classify: () -> GoalPlanningProvenanceRecoverability,
): GoalPlanningProvenanceRecoverability =
  runCatching(classify).getOrElse { GoalPlanningProvenanceRecoverability.Invalid }

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
