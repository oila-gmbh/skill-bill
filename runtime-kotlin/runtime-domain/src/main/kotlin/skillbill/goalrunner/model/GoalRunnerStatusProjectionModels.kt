package skillbill.goalrunner.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks

enum class GoalPlanningStatusState(val wireValue: String) {
  NOT_STARTED("not_started"),
  PREPLANNED("preplanned"),
  PARTIALLY_PLANNED("partially_planned"),
  BLOCKED("blocked"),
  PREPARED("prepared"),
}

/** Shared planning-status reason phrases so store projection and launch-aligned overlays stay in lockstep. */
object GoalPlanningStatusReasons {
  const val RESUME_MARKER: String = "planning can resume"

  const val NOT_STARTED: String = "Goal planning has not started."

  fun preplannedResume(firstMissingSubtaskId: Int): String =
    "Shared preplan is saved; planning can resume at subtask $firstMissingSubtaskId."

  fun partiallyPlannedResume(firstMissingSubtaskId: Int): String =
    "Saved plans will be reused; planning can resume at subtask $firstMissingSubtaskId."

  fun claimsResume(reason: String?): Boolean = reason?.contains(RESUME_MARKER) == true
}

enum class ExecutionLiveness(val wireValue: String) {
  LIVE("live"),
  IDLE("idle"),
  UNKNOWN("unknown"),
}

data class GoalPlanningStatusSnapshot(
  val state: GoalPlanningStatusState,
  val sharedPreplanPrepared: Boolean,
  val plannedSubtaskCount: Int,
  val totalSubtaskCount: Int,
  val currentPlanningSubtaskId: Int?,
  val reason: String?,
)

data class GoalRunnerStatusProjection(
  val issueKey: String,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val currentSubtaskId: Int?,
  /** Launched child workflow id for [currentSubtaskId], when the subtask has one. */
  val currentChildWorkflowId: String? = null,
  val currentSubtaskStatus: String? = null,
  val currentSubtaskBlockedReason: String? = null,
  val currentStep: String?,
  val activeAgent: String?,
  val executionLiveness: ExecutionLiveness = ExecutionLiveness.UNKNOWN,
  val planning: GoalPlanningStatusSnapshot? = null,
  val latestLivenessSignal: String? = null,
  @OpenBoundaryMap("Compact latest goal observability event passthrough for goal status rendering")
  val latestObservabilityEvent: Map<String, Any?>? = null,
  val requestedDiffStat: GoalObservabilityDiffStat? = null,
  val selectedDiffHunks: GoalObservabilitySelectedDiffHunks? = null,
  val blockedAttemptCount: Int = 0,
  val supervisorKillCount: Int = 0,
  val phaseAttemptCounts: Map<String, Int> = emptyMap(),
  val cumulativeFixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauseCounts: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
  val outOfBandAcceptances: List<GoalRunnerAcceptedSubtask> = emptyList(),
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val stopAfterSubtaskId: Int? = null,
  val activeDurationMs: Long = 0,
  val activeDurationAsOf: String? = null,
  val subtaskActiveDurationMs: Long = 0,
  val subtaskActiveDurationAsOf: String? = null,
)

/**
 * A subtask an operator recorded as landed outside the runtime. The git-tracked manifest projection
 * deliberately omits commit SHAs to keep that file churn-free, so this read-only status surface is
 * where a human sees which commit an accepted subtask actually points at.
 */
data class GoalRunnerAcceptedSubtask(
  val subtaskId: Int,
  val commitSha: String,
  val reason: String,
  val acceptedAt: String,
)

data class GoalRunnerStatusProjectionExtras(
  val executionLiveness: ExecutionLiveness = ExecutionLiveness.UNKNOWN,
  val planning: GoalPlanningStatusSnapshot? = null,
  val currentStepOverride: String? = null,
  /**
   * Live workflow status of the current subtask's child. The manifest projection is only rewritten at
   * reconciliation points, so a subtask relaunched from a durable block still reads `blocked` there for
   * the whole run; this reports what the child is actually doing.
   */
  val currentWorkflowStatus: String? = null,
  val latestLivenessSignal: String? = null,
  @OpenBoundaryMap("Compact latest goal observability event passthrough for goal status rendering")
  val latestObservabilityEvent: Map<String, Any?>? = null,
  val requestedDiffStat: GoalObservabilityDiffStat? = null,
  val selectedDiffHunks: GoalObservabilitySelectedDiffHunks? = null,
  val blockedAttemptCount: Int = 0,
  val supervisorKillCount: Int = 0,
  val phaseAttemptCounts: Map<String, Int> = emptyMap(),
  val cumulativeFixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauseCounts: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
  val outOfBandAcceptances: List<GoalRunnerAcceptedSubtask> = emptyList(),
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val stopAfterSubtaskId: Int? = null,
  val activeDurationMs: Long = 0,
  val activeDurationAsOf: String? = null,
  val subtaskActiveDurationMs: Long = 0,
  val subtaskActiveDurationAsOf: String? = null,
)

object GoalRunnerStatusProjector {
  @OpenBoundaryMap("Goal status projection accepts compact latest goal observability event passthrough")
  fun project(
    manifest: DecompositionManifest,
    activeAgent: String? = null,
    extras: GoalRunnerStatusProjectionExtras = GoalRunnerStatusProjectionExtras(),
  ): GoalRunnerStatusProjection {
    val currentSubtask = manifest.subtasks.firstOrNull { it.id == manifest.currentSubtaskIntent.subtaskId }
    val statusOf: (DecompositionSubtask) -> String = { subtask ->
      if (subtask.id == currentSubtask?.id && extras.currentWorkflowStatus in LIVE_WORKFLOW_STATUSES) {
        "in_progress"
      } else {
        subtask.status
      }
    }
    // Only goal_runner_supervisor events are persisted; the per-tick foreground heartbeats are console-only.
    // So the newest stored event still describes the process that produced it while a relaunched child runs:
    // a block recorded when a prior run stopped, or a worker_output_summary carrying the exit status and
    // stderr of a worker that already exited. Rendering either contradicts the live workflow status once the
    // live child has moved past that event's phase.
    val liveChild = extras.currentWorkflowStatus in LIVE_WORKFLOW_STATUSES
    val liveStep = extras.currentStepOverride?.takeIf(String::isNotBlank)
    val eventPhase = extras.latestObservabilityEvent?.get("workflow_phase")?.toString()?.takeIf(String::isNotBlank)
    val blockEvent = extras.latestObservabilityEvent?.get("liveness_class") == "block"
    val supersededPhaseEvent = liveStep != null && eventPhase != null && eventPhase != liveStep
    val staleSignal = liveChild && (blockEvent || supersededPhaseEvent)
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
        ?: currentSubtask?.let { s -> if (s.workflowId.isNullOrBlank()) "pending_launch" else "initializing" },
      activeAgent = activeAgent?.takeIf(String::isNotBlank),
      executionLiveness = extras.executionLiveness,
      planning = extras.planning,
      latestLivenessSignal = extras.latestLivenessSignal?.takeIf { it.isNotBlank() && !staleSignal },
      latestObservabilityEvent = extras.latestObservabilityEvent?.takeUnless { staleSignal },
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
}
