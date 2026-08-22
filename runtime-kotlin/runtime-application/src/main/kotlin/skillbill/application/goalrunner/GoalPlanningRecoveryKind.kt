package skillbill.application.goalrunner

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError

internal enum class GoalPlanningRecoveryKind {
  HARD_RESET,
  SCOPED_REPLAN,
}

internal fun goalPlanningHardResetRemedy(issueKey: String): String = "skill-bill goal reset $issueKey --hard --yes"

internal fun classifyGoalPlanningRecovery(reason: String, cause: Throwable? = null): GoalPlanningRecoveryKind {
  if (causeIndicatesContractVersionHardReset(cause) || reasonIndicatesContractVersionHardReset(reason)) {
    return GoalPlanningRecoveryKind.HARD_RESET
  }
  return GoalPlanningRecoveryKind.SCOPED_REPLAN
}

internal fun classifyGoalPlanningRecovery(
  error: IncompatibleGoalPlanningPreparationRecoveryError,
): GoalPlanningRecoveryKind = classifyGoalPlanningRecovery(error.reason, error.cause)

internal fun goalPlanningChildImportConflictBlockedReason(
  issueKey: String,
  subtaskId: Int,
  error: IncompatibleGoalPlanningPreparationRecoveryError,
): String {
  val kind = classifyGoalPlanningRecovery(error)
  val detail = error.reason.ifBlank { error.message.orEmpty() }
  return when (kind) {
    GoalPlanningRecoveryKind.HARD_RESET ->
      "Goal-subtask planning is incompatible with the installed phase-output contract " +
        "('$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'). Workflows created under an earlier version require a " +
        "hard reset. Recover with: '${goalPlanningHardResetRemedy(issueKey)}'. Planning failure: $detail"
    GoalPlanningRecoveryKind.SCOPED_REPLAN ->
      "Goal-subtask planning import conflicts with the stored shared preplan or subtask plan. " +
        "This occurs when a shared preplan was regenerated after the child was hydrated, " +
        "making the previously-imported planning bytes stale. " +
        "Recover this subtask's child without discarding sibling planning or completed commits: " +
        "'${staleChildPlanningRecoveryCommand(issueKey, subtaskId)}'. " +
        "Planning failure: $detail"
  }
}

internal fun contractVersionHardResetStopReason(issueKey: String): String =
  "Goal planning phase-output contract version is incompatible with the installed runtime " +
    "('$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'). Workflows created under an earlier version require a " +
    "hard reset. Recover with: '${goalPlanningHardResetRemedy(issueKey)}'"

private fun causeIndicatesContractVersionHardReset(cause: Throwable?): Boolean {
  var current = cause
  while (current != null) {
    when (current) {
      is InvalidGoalPlanningPreparationSchemaError ->
        if (reasonIndicatesContractVersionHardReset(current.fieldPath) ||
          reasonIndicatesContractVersionHardReset(current.reason)
        ) {
          return true
        }
      is InvalidFeatureTaskRuntimePhaseOutputSchemaError ->
        if (reasonIndicatesContractVersionHardReset(current.reason) ||
          reasonIndicatesContractVersionHardReset(current.payloadFreeReason.orEmpty())
        ) {
          return true
        }
      else -> {
        if (reasonIndicatesContractVersionHardReset(current.message.orEmpty())) return true
      }
    }
    current = current.cause
  }
  return false
}

private fun reasonIndicatesContractVersionHardReset(text: String): Boolean {
  if (text.isBlank()) return false
  val normalized = text.lowercase()
  return normalized.contains("phase_output_contract_version") ||
    normalized.contains("planning_contract_version") ||
    normalized.contains("phase_output_contract_id") ||
    normalized.contains("planning_contract_id") ||
    normalized.contains("hard-reset") ||
    normalized.contains("hard reset") ||
    normalized.contains("must be the constant value") ||
    (normalized.contains("contract_version") && normalized.contains("incompatible")) ||
    (normalized.contains("contract_version") && normalized.contains("must be"))
}
