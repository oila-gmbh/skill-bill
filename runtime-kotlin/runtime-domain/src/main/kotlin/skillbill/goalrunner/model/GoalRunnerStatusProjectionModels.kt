package skillbill.goalrunner.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.decomposition.model.DecompositionManifest
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
    val context = buildGoalRunnerStatusProjectionContext(manifest, extras)
    return assembleGoalRunnerStatusProjection(manifest, activeAgent, extras, context)
  }

}
