package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants

internal fun goalContinuationConflict(
  request: FeatureTaskRuntimeRunRequest,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
): String? = listOfNotNull(
  requestedReviewModeConflict(request, durable),
  request.goalContinuation?.let { supplied -> suppliedGoalContinuationConflict(supplied, durable, baseline) },
).firstOrNull()

private fun requestedReviewModeConflict(
  request: FeatureTaskRuntimeRunRequest,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
): String? = request.requestedCodeReviewMode
  ?.takeIf { it != durable.codeReviewMode }
  ?.let {
    "Cannot change code-review mode on a goal child resume; its durable continuation policy is " +
      "'${durable.codeReviewMode.wireValue}'."
  }

private fun suppliedGoalContinuationConflict(
  supplied: FeatureTaskRuntimeGoalContinuationContext,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
): String? = listOfNotNull(
  suppliedIdentityConflict(supplied, durable),
  supplied.codeReviewMode?.takeIf { it != durable.codeReviewMode }?.let {
    "The supplied goal-continuation code-review mode conflicts with its durable child policy."
  },
  durable.validationDepth?.takeIf { it != supplied.validationDepth }?.let {
    "The supplied goal-continuation validation depth conflicts with its durable child policy."
  },
  durable.qualityGateSelection?.takeIf { it != supplied.qualityGateSelection }?.let {
    "The supplied goal-continuation quality gate selection conflicts with its durable child policy."
  },
  requireNotNull(supplied.reviewBaseline).takeIf { it != baseline }?.let {
    "The supplied goal-continuation review baseline conflicts with its durable child policy."
  },
).firstOrNull()

private fun suppliedIdentityConflict(
  supplied: FeatureTaskRuntimeGoalContinuationContext,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
): String? = if (suppliedIdentityMatchesDurable(supplied, durable)) {
  null
} else {
  "The supplied goal-continuation identity conflicts with its durable child policy."
}

private fun suppliedIdentityMatchesDurable(
  supplied: FeatureTaskRuntimeGoalContinuationContext,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
): Boolean = supplied.parentIssueKey == durable.issueKey &&
  supplied.subtaskId == durable.subtaskId &&
  supplied.goalBranch == durable.goalBranch &&
  supplied.suppressPr == durable.suppressPr &&
  supplied.parentWorkflowId == durable.parentWorkflowId

internal fun newGoalContinuationConflict(
  request: FeatureTaskRuntimeRunRequest,
  selectedReviewMode: CodeReviewExecutionMode,
): String? {
  requireNotNull(request.goalContinuation)
  return request.requestedCodeReviewMode?.takeIf { it != selectedReviewMode }?.let {
    "The supplied goal-continuation code-review mode conflicts with the requested child review policy."
  }
}

internal fun goalContinuationPolicyBlockedReport(
  request: FeatureTaskRuntimeRunRequest,
  runInvariants: FeatureTaskRuntimeRunInvariants,
  reason: String,
): FeatureTaskRuntimeRunReport.Blocked = FeatureTaskRuntimeRunReport.Blocked(
  issueKey = request.issueKey,
  workflowId = request.workflowId,
  featureSize = runInvariants.featureSize.name,
  lastIncompletePhase = FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultInitialStepId,
  blockedReason = reason,
  completedPhaseIds = emptyList(),
  resolvedBranch = null,
)
