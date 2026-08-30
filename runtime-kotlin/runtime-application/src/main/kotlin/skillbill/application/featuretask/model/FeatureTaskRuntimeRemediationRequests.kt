package skillbill.application.featuretask.model

import skillbill.application.featuretask.RemediationReconcileSnapshot
import skillbill.application.featuretask.RemediationReconciliationDecision
import skillbill.application.featuretask.ResolvedReviewFixCheckpoint
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

internal data class RemediationReconciliationApplyRequest(
  val reconciliation: RemediationReconciliationDecision,
  val snapshot: RemediationReconcileSnapshot,
  val workflowId: String,
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val dbOverride: String?,
  val latestRemediationResolved: ResolvedReviewFixCheckpoint?,
)

internal data class RemediationBaseHealRequest(
  val target: String,
  val stored: String?,
  val storedResolves: Boolean,
  val state: GoalSubtaskReviewState,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val workflowId: String,
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val dbOverride: String?,
  val latestRemediationResolved: ResolvedReviewFixCheckpoint?,
)

internal data class PersistHealedRemediationBaseRequest(
  val workflowId: String,
  val target: String,
  val stored: String?,
  val reason: String,
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val dbOverride: String?,
)
