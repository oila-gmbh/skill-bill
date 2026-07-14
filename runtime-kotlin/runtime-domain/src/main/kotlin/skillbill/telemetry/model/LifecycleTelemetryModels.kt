package skillbill.telemetry.model

import skillbill.boundary.OpenBoundaryMap

data class FeatureImplementStartedRecord(
  val sessionId: String,
  val source: String = "production",
  val issueKeyProvided: Boolean,
  val issueKeyType: String,
  val specInputTypes: List<String>,
  val specWordCount: Int,
  val featureSize: String,
  val featureName: String,
  val rolloutNeeded: Boolean,
  val acceptanceCriteriaCount: Int,
  val openQuestionsCount: Int,
  val specSummary: String,
)

data class FeatureImplementFinishedRecord(
  val sessionId: String,
  val source: String = "production",
  val completionStatus: String,
  val planCorrectionCount: Int,
  val planTaskCount: Int,
  val planPhaseCount: Int,
  val featureFlagUsed: Boolean,
  val featureFlagPattern: String,
  val filesCreated: Int,
  val filesModified: Int,
  val tasksCompleted: Int,
  val reviewIterations: Int,
  val auditResult: String,
  val auditIterations: Int,
  val validationResult: String,
  val boundaryHistoryWritten: Boolean,
  val boundaryHistoryValue: String,
  val prCreated: Boolean,
  val planDeviationNotes: String,
  @OpenBoundaryMap("Caller-supplied JSON child-step telemetry payload")
  val childSteps: List<Map<String, Any?>>,
  val estimatedPhaseTokenBreakdownJson: String? = null,
  val estimatedTotalTokens: Int? = null,
)

data class FeatureTaskRuntimeStartedRecord(
  val sessionId: String,
  val featureSize: String,
  val issueKey: String,
  val featureName: String,
)

data class FeatureTaskRuntimeFinishedRecord(
  val sessionId: String,
  val completionStatus: String,
  val completedPhaseIds: List<String>,
  val phaseOutcomes: Map<String, String>,
  val lastIncompletePhase: String,
  val blockedReason: String,
  val resolvedBranch: String,
  // The durable review-fix loop iteration count, so finished telemetry reflects the review->fix
  // iteration count (AC6). Zero when the loop never fired.
  val reviewFixIterationCount: Int = 0,
  // The durable audit-gap loop iteration count, so finished telemetry reflects the audit->implement
  // iteration count (AC7). Zero when the loop never fired.
  val auditGapIterationCount: Int = 0,
  val estimatedPhaseTokenBreakdownJson: String? = null,
  val estimatedTotalTokens: Int? = null,
)

data class QualityCheckStartedRecord(
  val sessionId: String,
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean,
  val fallbackReason: String?,
  val scopeType: String,
  val initialFailureCount: Int,
)

data class QualityCheckFinishedRecord(
  val sessionId: String,
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean,
  val fallbackReason: String?,
  val scopeType: String,
  val initialFailureCount: Int,
  val finalFailureCount: Int,
  val iterations: Int,
  val result: String,
  val failingCheckNames: List<String>,
  val unsupportedReason: String,
)

data class FeatureVerifyStartedRecord(
  val sessionId: String,
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
)

data class FeatureVerifyFinishedRecord(
  val sessionId: String,
  val featureFlagAuditPerformed: Boolean,
  val reviewIterations: Int,
  val auditResult: String,
  val completionStatus: String,
  val historyRelevance: String,
  val historyHelpfulness: String,
  val gapsFound: List<String>,
)

data class PrDescriptionGeneratedRecord(
  val sessionId: String,
  val commitCount: Int,
  val filesChangedCount: Int,
  val wasEditedByUser: Boolean,
  val prCreated: Boolean,
  val prTitle: String,
)

data class GoalStartedRecord(
  val issueKey: String,
  val featureName: String,
  val workflowId: String,
  val subtaskTotal: Int,
  val resumed: Boolean,
  val startedAt: String,
  val status: String = "running",
  val mode: String,
  val parentWorkflowId: String? = null,
)

data class GoalSubtaskFinishedRecord(
  val issueKey: String,
  val workflowId: String,
  val subtaskId: Int,
  val subtaskName: String,
  val status: String,
  val startedAt: String,
  val finishedAt: String,
  val durationMs: Long,
  val attemptCount: Int,
  val blockedReason: String?,
  val finalizingAgentId: String? = null,
  val participatingAgentIds: List<String> = emptyList(),
)

data class GoalFinishedRecord(
  val issueKey: String,
  val workflowId: String,
  val status: String,
  val startedAt: String,
  val finishedAt: String,
  val durationMs: Long,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val mode: String,
  val stopReason: String? = null,
  val parentWorkflowId: String? = null,
)

data class GoalIssueFinishedRecord(
  val issueKey: String,
  val parentWorkflowId: String,
  val status: String,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val finishedAt: String,
  val mode: String,
)
