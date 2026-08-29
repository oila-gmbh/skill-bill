package skillbill.application.goalrunner.planning

import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.model.GoalPlanningStatusReasons
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.workflow.decomposition.model.DecompositionSubtask

/** Copy-pasteable operator remedy for incompatible shared-preplan provenance. */
internal fun goalPlanningIncludeSharedPreplanRemedy(issueKey: String, subtaskId: Int): String =
  "skill-bill goal replan $issueKey --subtask $subtaskId --include-shared-preplan"

/**
 * The subtask the advertised replan can actually target. `replan` refuses a `complete` or `skipped`
 * subtask, so picking the lowest id — or any non-skipped one — hands the operator a command the
 * runtime then rejects, which is what happens on every goal whose early subtasks already finished.
 * Null means no subtask is replannable and no command should be advertised.
 */
internal fun goalPlanningRemedySubtaskId(subtasks: List<DecompositionSubtask>): Int? =
  subtasks.firstOrNull { it.status != "complete" && it.status != "skipped" }?.id

private fun recoverySuffix(issueKey: String, subtaskId: Int?, kind: GoalPlanningRecoveryKind): String = when (kind) {
  GoalPlanningRecoveryKind.HARD_RESET ->
    "Recover with: ${goalPlanningHardResetRemedy(issueKey)}"
  GoalPlanningRecoveryKind.SCOPED_REPLAN -> if (subtaskId == null) {
    "No subtask is replannable, so reset the goal before planning can be repaired."
  } else {
    "Recover with: ${goalPlanningIncludeSharedPreplanRemedy(issueKey, subtaskId)}"
  }
}

internal fun goalPlanningIncompatibleProvenanceStopReason(
  issueKey: String,
  subtaskId: Int?,
  kind: GoalPlanningRecoveryKind,
): String = when (kind) {
  GoalPlanningRecoveryKind.HARD_RESET -> contractVersionHardResetStopReason(issueKey)
  GoalPlanningRecoveryKind.SCOPED_REPLAN ->
    "Goal planning shared preplan provenance is incompatible with the current governed inputs. " +
      recoverySuffix(issueKey, subtaskId, kind)
}

internal fun goalPlanningMissingSharedContextPacketStopReason(issueKey: String, subtaskId: Int?): String =
  "Goal planning shared preplan does not contain a valid shared context packet. " +
    recoverySuffix(issueKey, subtaskId, GoalPlanningRecoveryKind.SCOPED_REPLAN)

/**
 * Surviving preparation-state hard stop. Uses [IncompatibleGoalPlanningPreparationRecoveryError.reason]
 * rather than [Throwable.message] so the stop does not claim the state "cannot be recovered" when
 * `--include-shared-preplan` is the documented recovery path.
 */
internal fun goalPlanningPreparationStateReadStopReason(error: Throwable, issueKey: String, subtaskId: Int?): String {
  val recovery = error as? IncompatibleGoalPlanningPreparationRecoveryError
    ?: return "Goal planning preparation state could not be read: ${error.message.orEmpty()}"
  val remedySubtaskId = subtaskId?.takeIf { it > 0 } ?: recovery.subtaskId.takeIf { it > 0 }
  val kind = classifyGoalPlanningRecovery(recovery)
  return "Goal planning preparation state could not be read: ${recovery.reason}. " +
    recoverySuffix(issueKey, remedySubtaskId, kind)
}

internal fun goalPlanningNonResumableStatusReason(
  issueKey: String,
  subtaskId: Int?,
  kind: GoalPlanningRecoveryKind,
): String = "Saved planning is not resumable until provenance is repaired. " +
  recoverySuffix(issueKey, subtaskId, kind)

/**
 * Launch refuses when shared-preplan classification cannot complete (identity mismatch, schema
 * drift). Status must treat that failure as [GoalPlanningProvenanceRecoverability.Irrecoverable] so a
 * resume-claiming `planning_reason` cannot survive.
 */
internal fun statusRecoverabilityOrRefuse(
  classify: () -> GoalPlanningProvenanceRecoverability,
): GoalPlanningProvenanceRecoverability = runCatching(classify).getOrElse { error ->
  GoalPlanningProvenanceRecoverability.Irrecoverable(
    classifyGoalPlanningRecovery(error.message.orEmpty(), error),
  )
}

/**
 * Replaces resume-claiming status reasons when launch would refuse the same durable planning state.
 * [GoalPlanningProvenanceRecoverability.StaleValid] is not refused after in-run refresh; only Invalid
 * overlays the reason.
 */
internal fun alignPlanningStatusWithLaunchRecoverability(
  snapshot: GoalPlanningStatusSnapshot,
  recoverability: GoalPlanningProvenanceRecoverability,
  issueKey: String,
  remedySubtaskId: Int?,
): GoalPlanningStatusSnapshot {
  if (recoverability !is GoalPlanningProvenanceRecoverability.Irrecoverable) return snapshot
  if (
    snapshot.state != GoalPlanningStatusState.PREPLANNED &&
    snapshot.state != GoalPlanningStatusState.PARTIALLY_PLANNED
  ) {
    return snapshot
  }
  if (!GoalPlanningStatusReasons.claimsResume(snapshot.reason)) return snapshot
  return snapshot.copy(
    reason = goalPlanningNonResumableStatusReason(issueKey, remedySubtaskId, recoverability.recoveryKind),
  )
}
