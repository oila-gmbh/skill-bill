package skillbill.goalrunner.model

import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask

internal data class GoalRunnerStatusProjectionContext(
  val currentSubtask: DecompositionSubtask?,
  val statusOf: (DecompositionSubtask) -> String,
  val staleSignal: Boolean,
)

internal fun buildGoalRunnerStatusProjectionContext(
  manifest: DecompositionManifest,
  extras: GoalRunnerStatusProjectionExtras,
): GoalRunnerStatusProjectionContext {
  val currentSubtask = manifest.subtasks.firstOrNull { it.id == manifest.currentSubtaskIntent.subtaskId }
  val statusOf: (DecompositionSubtask) -> String = { subtask ->
    if (subtask.id == currentSubtask?.id && extras.currentWorkflowStatus in LIVE_WORKFLOW_STATUSES) {
      "in_progress"
    } else {
      subtask.status
    }
  }
  val liveChild = extras.currentWorkflowStatus in LIVE_WORKFLOW_STATUSES
  val liveStep = extras.currentStepOverride?.takeIf(String::isNotBlank)
  val eventPhase = extras.latestObservabilityEvent?.get("workflow_phase")?.toString()?.takeIf(String::isNotBlank)
  val blockEvent = extras.latestObservabilityEvent?.get("liveness_class") == "block"
  val supersededPhaseEvent = liveStep != null && eventPhase != null && eventPhase != liveStep
  val staleSignal = liveChild && (blockEvent || supersededPhaseEvent)
  return GoalRunnerStatusProjectionContext(currentSubtask, statusOf, staleSignal)
}

internal fun assembleGoalRunnerStatusProjection(
  manifest: DecompositionManifest,
  activeAgent: String?,
  extras: GoalRunnerStatusProjectionExtras,
  context: GoalRunnerStatusProjectionContext,
): GoalRunnerStatusProjection {
  val currentSubtask = context.currentSubtask
  val statusOf = context.statusOf
  return GoalRunnerStatusProjection(
    issueKey = manifest.issueKey,
    completeCount = manifest.subtasks.count { statusOf(it) == "complete" || statusOf(it) == "skipped" },
    pendingCount = manifest.subtasks.count { statusOf(it) !in setOf("complete", "skipped", "blocked") },
    blockedCount = manifest.subtasks.count { statusOf(it) == "blocked" },
    currentSubtaskId = currentSubtask?.id,
    currentChildWorkflowId = currentSubtask?.workflowId?.takeIf(String::isNotBlank),
    currentSubtaskStatus = currentSubtask?.let(statusOf)?.takeIf(String::isNotBlank),
    currentSubtaskBlockedReason = currentSubtask?.blockedReason?.takeIf(String::isNotBlank),
    currentStep = extras.currentStepOverride?.takeIf(String::isNotBlank)
      ?: currentSubtask?.lastResumableStep
      ?: currentSubtask?.let { subtask ->
        if (subtask.workflowId.isNullOrBlank()) "pending_launch" else "initializing"
      },
    activeAgent = activeAgent?.takeIf(String::isNotBlank),
    executionLiveness = extras.executionLiveness,
    planning = extras.planning,
    latestLivenessSignal = extras.latestLivenessSignal?.takeIf { it.isNotBlank() && !context.staleSignal },
    latestObservabilityEvent = extras.latestObservabilityEvent?.takeUnless { context.staleSignal },
    requestedDiffStat = extras.requestedDiffStat,
    selectedDiffHunks = extras.selectedDiffHunks,
    blockedAttemptCount = extras.blockedAttemptCount,
    supervisorKillCount = extras.supervisorKillCount,
    phaseAttemptCounts = extras.phaseAttemptCounts,
    cumulativeFixIterations = extras.cumulativeFixIterations,
    reAttemptCauseCounts = extras.reAttemptCauseCounts,
    findingsInScope = extras.findingsInScope,
    outOfBandAcceptances = extras.outOfBandAcceptances,
    paused = extras.paused,
    pauseRequested = extras.pauseRequested,
    pauseReason = extras.pauseReason,
    pausedAt = extras.pausedAt,
    stopAfterSubtaskId = extras.stopAfterSubtaskId,
    activeDurationMs = extras.activeDurationMs,
    activeDurationAsOf = extras.activeDurationAsOf,
    subtaskActiveDurationMs = extras.subtaskActiveDurationMs,
    subtaskActiveDurationAsOf = extras.subtaskActiveDurationAsOf,
  )
}

private val LIVE_WORKFLOW_STATUSES = setOf("running", "pending")
