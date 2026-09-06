package skillbill.telemetry.model

import skillbill.agent.model.AgentId

import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId

data class FeatureTaskRuntimeStartedRecord(
  val sessionId: SessionId,
  val featureSize: String,
  val issueKey: IssueKey,
  val featureName: String,
)

data class FeatureTaskRuntimeFinishedRecord(
  val sessionId: SessionId,
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
  val auditFirstPassConvergence: Boolean = false,
  val auditRecurringGapCount: Int = 0,
  val auditNewGapCount: Int = 0,
  val auditAttemptedRepairItemCount: Int = 0,
  val auditResolvedRepairItemCount: Int = 0,
  // SKILL-140: per-run quarantine-and-regenerate counters (AC-006). Counts only: how many times the
  // launch seam quarantined an upstream record, how many regeneration attempts fired across all
  // regeneration loops, and the outcome class tally. Never carries record contents.
  val regenerationActivationCount: Int = 0,
  val regenerationAttemptCount: Int = 0,
  val regenerationOutcomeCounts: Map<String, Int> = emptyMap(),
  // SKILL-140 subtask 5: per-run crash-reconciliation counters (AC-006). Reconciled-row count and the
  // tally by reason class. Counts and class labels only; never carries row contents.
  val crashReconciliationCount: Int = 0,
  val crashReconciliationReasonCounts: Map<String, Int> = emptyMap(),
  val estimatedPhaseTokenBreakdownJson: String? = null,
  val estimatedTotalTokens: Int? = null,
  val findingVerificationVerifiedCount: Int = 0,
  val findingVerificationRejectedCount: Int = 0,
  val reviewFixCapExhausted: Boolean = false,
)

data class QualityCheckStartedRecord(
  val sessionId: SessionId,
  val routedSkill: String,
  val detectedStack: String,
  val fallback: Boolean,
  val fallbackReason: String?,
  val scopeType: String,
  val initialFailureCount: Int,
)

data class QualityCheckFinishedRecord(
  val sessionId: SessionId,
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
  val sessionId: SessionId,
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
)

data class FeatureVerifyFinishedRecord(
  val sessionId: SessionId,
  val featureFlagAuditPerformed: Boolean,
  val reviewIterations: Int,
  val auditResult: String,
  val completionStatus: String,
  val historyRelevance: String,
  val historyHelpfulness: String,
  val gapsFound: List<String>,
)

data class PrDescriptionGeneratedRecord(
  val sessionId: SessionId,
  val commitCount: Int,
  val filesChangedCount: Int,
  val wasEditedByUser: Boolean,
  val prCreated: Boolean,
  val prTitle: String,
)

data class GoalStartedRecord(
  val issueKey: IssueKey,
  val featureName: String,
  val workflowId: WorkflowId,
  val subtaskTotal: Int,
  val resumed: Boolean,
  val startedAt: String,
  val status: String = "running",
  val mode: String,
  val parentWorkflowId: WorkflowId? = null,
)

data class GoalSubtaskFinishedRecord(
  val issueKey: IssueKey,
  val workflowId: WorkflowId,
  val subtaskId: SubtaskId,
  val subtaskName: String,
  val status: String,
  val startedAt: String,
  val finishedAt: String,
  val durationMs: Long,
  val attemptCount: Int,
  val blockedReason: String?,
  val finalizingAgentId: AgentId? = null,
  val participatingAgentIds: List<AgentId> = emptyList(),
)

data class GoalFinishedRecord(
  val issueKey: IssueKey,
  val workflowId: WorkflowId,
  val status: String,
  val startedAt: String,
  val finishedAt: String,
  val durationMs: Long,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val mode: String,
  val stopReason: String? = null,
  val parentWorkflowId: WorkflowId? = null,
)

data class GoalIssueFinishedRecord(
  val issueKey: IssueKey,
  val parentWorkflowId: WorkflowId,
  val status: String,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val finishedAt: String,
  val mode: String,
)
