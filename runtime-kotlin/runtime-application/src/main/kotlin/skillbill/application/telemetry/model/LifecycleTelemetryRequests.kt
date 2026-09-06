package skillbill.application.telemetry.model

import skillbill.agent.model.AgentId
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId

data class FeatureTaskRuntimeStartedRequest(
  val featureSize: String,
  val issueKey: IssueKey,
  val featureName: String,
  val sessionId: SessionId = SessionId(""),
)

data class FeatureTaskRuntimeFinishedRequest(
  val sessionId: SessionId,
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
  // ledger), so finished telemetry reflects how many audit->implement iterations ran (AC7). Zero when the
  // loop never fired. Runtime-owned, never agent-self-reported.
  val auditGapIterationCount: Int = 0,
  val auditFirstPassConvergence: Boolean = false,
  val auditRecurringGapCount: Int = 0,
  val auditNewGapCount: Int = 0,
  val auditAttemptedRepairItemCount: Int = 0,
  val auditResolvedRepairItemCount: Int = 0,
  // SKILL-140: per-run quarantine-and-regenerate counters (AC-006). Counts and outcome classes only,
  // sourced from the durable quarantine store and LOOP_EDGE ledger; never agent-self-reported.
  val regenerationActivationCount: Int = 0,
  val regenerationAttemptCount: Int = 0,
  val regenerationOutcomeCounts: Map<String, Int> = emptyMap(),
  // SKILL-140 subtask 5: per-run crash-reconciliation counters (AC-006). How many orphaned runtime
  // rows the startup pass transitioned to resumable, and the tally by reason class. Counts and class
  // labels only; never carries row contents. Runtime-owned, never agent-self-reported.
  val crashReconciliationCount: Int = 0,
  val crashReconciliationReasonCounts: Map<String, Int> = emptyMap(),
  val estimatedPhaseTokenBreakdownJson: String? = null,
  val estimatedTotalTokens: Int? = null,
  val findingVerificationVerifiedCount: Int = 0,
  val findingVerificationRejectedCount: Int = 0,
  val reviewFixCapExhausted: Boolean = false,
)

/**
 * SKILL-140: per-run quarantine-and-regenerate telemetry, sourced from durable state. Counts and
 * outcome-class labels only; never carries record contents, prompts, plan bodies, or receipt bodies.
 */
data class FeatureTaskRuntimeRegenerationTelemetry(
  val activationCount: Int = 0,
  val attemptCount: Int = 0,
  val outcomeCounts: Map<String, Int> = emptyMap(),
)

data class FeatureTaskRuntimeFindingVerificationTelemetry(
  val verifiedCount: Int = 0,
  val rejectedCount: Int = 0,
  val reviewFixCapExhausted: Boolean = false,
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
  val sessionId: SessionId,
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
  val sessionId: SessionId,
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

data class GoalSubtaskFinishedRequest(
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

data class GoalFinishedRequest(
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

data class GoalIssueFinishedRequest(
  val issueKey: IssueKey,
  val parentWorkflowId: WorkflowId,
  val status: String,
  val subtasksComplete: Int,
  val subtasksBlocked: Int,
  val subtasksSkipped: Int,
  val finishedAt: String,
  val mode: String,
)
