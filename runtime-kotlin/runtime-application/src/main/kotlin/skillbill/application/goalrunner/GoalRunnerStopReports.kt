package skillbill.application.goalrunner

import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStopReport
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.decomposition.model.DecompositionManifest

internal fun stopped(
  issueKey: String,
  attempted: List<Int>,
  subtaskId: Int,
  reason: GoalRunnerStopReason,
  blockedReason: String,
  workflowId: String?,
  lastResumableStep: String,
): GoalRunnerRunReport.Stopped = GoalRunnerRunReport.Stopped(
  issueKey = issueKey,
  attemptedSubtasks = attempted,
  stop = GoalRunnerStopReport(
    issueKey = issueKey,
    subtaskId = subtaskId,
    reason = reason,
    blockedReason = blockedReason,
    workflowId = workflowId,
    lastResumableStep = lastResumableStep,
  ),
)

internal fun completed(
  manifest: DecompositionManifest,
  attempted: List<Int>,
  pullRequestUrl: String?,
  pullRequestStatus: String,
  ledger: UnaddressedFindingsLedger?,
): GoalRunnerRunReport.Completed {
  return GoalRunnerRunReport.Completed(
    issueKey = manifest.issueKey,
    attemptedSubtasks = attempted,
    featureName = manifest.featureName,
    pullRequestUrl = pullRequestUrl,
    pullRequestStatus = pullRequestStatus,
    subtasksCompleted = manifest.subtasks.count { it.status == "complete" },
    subtasksPending = manifest.subtasks.count { it.status !in setOf("complete", "skipped", "blocked") },
    subtasksBlocked = manifest.subtasks.count { it.status == "blocked" },
    unaddressedFindingCount = ledger?.findings?.size,
    unaddressedSeverityBreakdown = ledger?.severityBreakdown.orEmpty(),
  )
}

internal fun unknownGoal(issueKey: String): GoalRunnerRunReport.Stopped = stopped(
  issueKey = issueKey,
  attempted = emptyList(),
  subtaskId = 0,
  reason = GoalRunnerStopReason.BLOCKED,
  blockedReason = "No decomposed parent workflow was found for $issueKey.",
  workflowId = null,
  lastResumableStep = "preplan",
)

internal fun String.withStopDiagnostics(
  knownWorkflowId: String?,
  progress: GoalRunnerWorkflowProgress?,
  liveness: GoalRunnerLivenessSnapshot?,
): String {
  val details = listOfNotNull(
    knownWorkflowId?.let { workflowId -> "workflow_id=$workflowId" },
    progress?.currentStepId?.takeIf(String::isNotBlank)?.let { step -> "current_step=$step" },
    progress?.latestLivenessSignal?.takeIf(String::isNotBlank)?.let { signal -> "latest_liveness=$signal" },
    progress?.lastSnapshotUpdatedAt?.takeIf(String::isNotBlank)?.let { at -> "last_snapshot_at=$at" },
    liveness?.lastFileActivityAt?.takeIf(String::isNotBlank)?.let { at -> "last_file_activity_at=$at" },
    liveness?.lastOutputAt?.takeIf(String::isNotBlank)?.let { at -> "last_output_at=$at" },
  ).joinToString(", ")
  return if (details.isBlank()) this else "$this [$details]"
}

internal fun GoalRunnerReconciledOutcome.Stop.isRecoverableValidationBlock(): Boolean =
  reason in setOf(GoalRunnerStopReason.BLOCKED, GoalRunnerStopReason.FAILED) &&
    lastResumableStep == "validate"

internal fun supervisionEvent(
  reason: GoalRunnerStopReason,
  knownWorkflowId: String,
  progress: GoalRunnerWorkflowProgress?,
  liveness: GoalRunnerLivenessSnapshot?,
): GoalRunnerSupervisionEvent = GoalRunnerSupervisionEvent(
  phase = "goal_runner_supervision",
  reason = reason.name.lowercase(),
  continuationMode = when (reason) {
    GoalRunnerStopReason.TIMEOUT -> "killed_unresponsive_child"
    GoalRunnerStopReason.INTERRUPTED -> "killed_by_parent_interrupt"
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> "continue_inline"
    GoalRunnerStopReason.FAILED,
    GoalRunnerStopReason.BLOCKED,
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> "none"
  },
  processState = liveness?.processState.orEmpty().ifBlank { "unknown" },
  workflowId = knownWorkflowId,
  stepId = progress?.currentStepId ?: liveness?.workflowStep,
  lastDurableProgress = progress?.latestLivenessSignal ?: liveness?.lastDurableProgressLabel,
  lastWorkflowSnapshotAt = progress?.lastSnapshotUpdatedAt ?: liveness?.lastWorkflowSnapshotAt,
  lastFileActivityAt = liveness?.lastFileActivityAt,
  lastOutputAt = liveness?.lastOutputAt,
)
