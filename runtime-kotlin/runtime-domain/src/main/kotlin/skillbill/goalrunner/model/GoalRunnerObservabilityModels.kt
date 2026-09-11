package skillbill.goalrunner.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalProgressEventKind
import skillbill.workflow.goal.model.GoalProgressOutcome

data class GoalRunnerObservabilityRecordRequest(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val sequenceNumber: Int,
  val timestamp: String,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(workflowPhase.isNotBlank()) { "workflowPhase is required." }
    require(workerRole.isNotBlank()) { "workerRole is required." }
    require(livenessClass.isNotBlank()) { "livenessClass is required." }
    require(activitySummary.isNotBlank()) { "activitySummary is required." }
    require(sequenceNumber >= 0) { "sequenceNumber must be non-negative." }
    require(timestamp.isNotBlank()) { "timestamp is required." }
  }
}

data class GoalRunnerProgressEvent(
  val stepId: String,
  val attemptCount: Int,
  val kind: String,
  val message: String,
  val sequence: Int,
  val timestamp: String,
)

data class GoalObservabilityProgressEvent(
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val sequenceNumber: Int,
  val timestamp: String,
)

data class GoalRunnerAttemptLedgerSummary(
  val blockedAttemptCount: Int = 0,
  val supervisorKillCount: Int = 0,
  val phaseAttemptCounts: Map<String, Int> = emptyMap(),
  val cumulativeFixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauseCounts: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
)

data class BuildDeclaredGoalProgressEventArgs(
  val sourceLabel: String,
  val eventKind: GoalProgressEventKind,
  val workflowId: String,
  val workflowPhase: String,
  val sequenceNumber: Int,
  val timestamp: String,
  val outcome: GoalProgressOutcome,
)

data class GoalContinuation(
  val issueKey: String,
  val subtaskId: Int,
  val suppressPr: Boolean,
  val goalBranch: String?,
)

data class GoalObservabilityWorktreeActivity(
  val changedFileSummary: GoalObservabilityChangedFileSummary?,
  val diffStat: GoalObservabilityDiffStat?,
)

data class GoalObservabilityProgressInput(
  @OpenBoundaryMap("Existing durable workflow artifacts when projecting goal observability from progress")
  val artifacts: Map<String, Any?>,
  val workflowId: String,
  val workflowStatus: String,
  val currentStepId: String,
  val worktreeActivity: GoalObservabilityWorktreeActivity? = null,
)

data class GoalObservabilityRuntimeEventInput(
  @OpenBoundaryMap("Existing durable workflow artifacts when recording a goal observability runtime event")
  val artifacts: Map<String, Any?>,
  val request: GoalRunnerObservabilityRecordRequest,
)
