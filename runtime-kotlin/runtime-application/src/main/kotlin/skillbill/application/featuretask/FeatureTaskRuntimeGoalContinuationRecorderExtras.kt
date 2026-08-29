package skillbill.application.featuretask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

internal fun FeatureTaskRuntimeGoalContinuationRecorder.reviewState(
  workflowId: String,
  dbOverride: String?,
): GoalSubtaskReviewState? = reviewStateRecorder.reviewState(workflowId, dbOverride)

internal fun FeatureTaskRuntimeGoalContinuationRecorder.continuation(
  workflowId: String,
  dbOverride: String?,
): FeatureTaskRuntimeGoalContinuationArtifact? = reviewStateRecorder.continuation(workflowId, dbOverride)

internal fun FeatureTaskRuntimeGoalContinuationRecorder.lastGoalReviewResult(
  workflowId: String,
  dbOverride: String?,
): String? = reviewPassRecorder.lastGoalReviewResult(workflowId, dbOverride)

internal fun FeatureTaskRuntimeGoalContinuationRecorder.appendRemediationRollbackDegradationEvidence(
  workflowId: String,
  signal: RemediationDegradationSignal,
  dbOverride: String?,
) = remediationReconciler.appendRemediationRollbackDegradationEvidence(workflowId, signal, dbOverride)

internal fun FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence(
  workflowId: String,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  dbOverride: String?,
): RemediationBaseCoherenceResult =
  remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOperations, repoRoot, dbOverride)
