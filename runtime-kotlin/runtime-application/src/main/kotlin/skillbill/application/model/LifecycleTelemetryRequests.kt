package skillbill.application.model

import skillbill.boundary.OpenBoundaryMap

data class FeatureImplementStartedRequest(
  val featureSize: String,
  val source: String = "production",
  val acceptanceCriteriaCount: Int,
  val openQuestionsCount: Int,
  val specInputTypes: List<String>,
  val specWordCount: Int,
  val rolloutNeeded: Boolean,
  val featureName: String,
  val issueKey: String,
  val issueKeyType: String,
  val specSummary: String,
)

data class FeatureImplementFinishedRequest(
  val sessionId: String,
  val source: String = "production",
  val completionStatus: String,
  val planCorrectionCount: Int,
  val planTaskCount: Int,
  val planPhaseCount: Int,
  val featureFlagUsed: Boolean,
  val filesCreated: Int,
  val filesModified: Int,
  val tasksCompleted: Int,
  val reviewIterations: Int,
  val auditResult: String,
  val auditIterations: Int,
  val validationResult: String,
  val boundaryHistoryWritten: Boolean,
  val prCreated: Boolean,
  val featureFlagPattern: String,
  val boundaryHistoryValue: String,
  val planDeviationNotes: String,
  @OpenBoundaryMap("Caller-supplied JSON child-step telemetry payload")
  val childSteps: List<Map<String, Any?>>,
  val estimatedPhaseTokenBreakdownJson: String? = null,
  val estimatedTotalTokens: Int? = null,
)

data class FeatureTaskRuntimeStartedRequest(
  val featureSize: String,
  val issueKey: String,
  val featureName: String,
  val sessionId: String = "",
)

data class FeatureTaskRuntimeFinishedRequest(
  val sessionId: String,
  val completionStatus: String,
  val completedPhaseIds: List<String>,
  val phaseOutcomes: Map<String, String>,
  val lastIncompletePhase: String,
  val blockedReason: String,
  val resolvedBranch: String,
  // The durable review-fix loop iteration count (the per-edge `review_fix` watermark from the
  // LOOP_EDGE ledger), so finished telemetry reflects how many review->fix iterations ran (AC6).
  // Zero when the loop never fired. Runtime-owned, never agent-self-reported.
  val reviewFixIterationCount: Int = 0,
  // The durable audit-gap loop iteration count (the per-edge `audit_gap` watermark from the LOOP_EDGE
  // ledger), so finished telemetry reflects how many audit->plan iterations ran (AC7). Zero when the
  // loop never fired. Runtime-owned, never agent-self-reported.
  val auditGapIterationCount: Int = 0,
  val estimatedPhaseTokenBreakdownJson: String? = null,
  val estimatedTotalTokens: Int? = null,
)

data class QualityCheckStartedRequest(
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean = false,
  val fallbackReason: String? = null,
  val scopeType: String,
  val initialFailureCount: Int,
  val orchestrated: Boolean,
)

data class QualityCheckFinishedRequest(
  val finalFailureCount: Int,
  val iterations: Int,
  val result: String,
  val sessionId: String,
  val failingCheckNames: List<String>,
  val unsupportedReason: String,
  val orchestrated: Boolean,
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean = false,
  val fallbackReason: String? = null,
  val scopeType: String,
  val initialFailureCount: Int,
  val durationSeconds: Int,
)

data class FeatureVerifyStartedRequest(
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
  val orchestrated: Boolean,
)

data class FeatureVerifyFinishedRequest(
  val featureFlagAuditPerformed: Boolean,
  val reviewIterations: Int,
  val auditResult: String,
  val completionStatus: String,
  val historyRelevance: String,
  val historyHelpfulness: String,
  val sessionId: String,
  val gapsFound: List<String>,
  val orchestrated: Boolean,
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
  val durationSeconds: Int,
)

data class PrDescriptionGeneratedRequest(
  val commitCount: Int,
  val filesChangedCount: Int,
  val wasEditedByUser: Boolean,
  val prCreated: Boolean,
  val prTitle: String,
  val orchestrated: Boolean,
  val generatedDescription: String? = null,
  val finalPrBody: String? = null,
)

data class GoalStartedRequest(
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

data class GoalSubtaskFinishedRequest(
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

data class GoalFinishedRequest(
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

data class GoalIssueFinishedRequest(
  val issueKey: String,
  val parentWorkflowId: String,
  val status: String,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val finishedAt: String,
  val mode: String,
)
