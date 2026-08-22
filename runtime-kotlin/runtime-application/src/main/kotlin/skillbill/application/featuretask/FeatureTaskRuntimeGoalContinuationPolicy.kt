package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants

internal fun goalContinuationConflict(
  request: FeatureTaskRuntimeRunRequest,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
): String? = listOfNotNull(
  requestedReviewModeConflict(request, durable),
  requestedParallelReviewAgentConflict(request, durable),
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

private fun requestedParallelReviewAgentConflict(
  request: FeatureTaskRuntimeRunRequest,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
): String? = request.parallelReviewAgent
  ?.takeIf { it != durable.parallelReviewAgent }
  ?.let {
    "Cannot change the parallel-review agent on a goal child resume; its durable continuation policy is " +
      "'${durable.parallelReviewAgent ?: "none"}'."
  }

private fun suppliedGoalContinuationConflict(
  supplied: FeatureTaskRuntimeGoalContinuationContext,
  durable: FeatureTaskRuntimeGoalContinuationArtifact,
  baseline: GoalSubtaskReviewBaseline,
): String? = listOfNotNull(
  suppliedIdentityConflict(supplied, durable),
  // codeReviewMode: durable is required at decode (requireGoalContinuationCodeReviewMode); absence
  // cannot reach the gate. Supplied may omit; ?.takeIf tolerates that without treating omit as a value.
  supplied.codeReviewMode?.takeIf { it != durable.codeReviewMode }?.let {
    "The supplied goal-continuation code-review mode conflicts with its durable child policy."
  },
  // validationDepth: durable null means the row never recorded a depth (key absent). Conflict only
  // when a recorded depth differs from the supplied one; absent durable adopts supplied on resume.
  durable.validationDepth?.takeIf { it != supplied.validationDepth }?.let {
    "The supplied goal-continuation validation depth conflicts with its durable child policy."
  },
  durable.qualityGateSelection?.takeIf { it != supplied.qualityGateSelection }?.let {
    "The supplied goal-continuation quality gate selection conflicts with its durable child policy."
  },
  // parallelReviewAgent: omitted key and explicit no-agent share null by construction of
  // toArtifactMap; absent and none are the same policy value, not a conflation to heal.
  supplied.parallelReviewAgent?.takeIf { it != durable.parallelReviewAgent }?.let {
    "The supplied goal-continuation parallel-review agent conflicts with its durable child policy."
  },
  // reviewBaseline: durable absence already fails in readReviewBaseline before the gate; supplied is
  // requireNotNull-forced here, so neither side can be absent at comparison time.
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
): Boolean = // issueKey/subtaskId/goalBranch/suppressPr: absence loud-fails in fromArtifactMap via require*
  // decoders before the gate runs, so those fields cannot be absent here.
  // parentWorkflowId: stamped by every child-open path since the goal-continuation artifact was
  // introduced; a null durable value is an intentional lineage omission, not a pre-contract key
  // that decoded as a default, so a supplied non-null parent is a genuine identity conflict.
  supplied.parentIssueKey == durable.issueKey &&
    supplied.subtaskId == durable.subtaskId &&
    supplied.goalBranch == durable.goalBranch &&
    supplied.suppressPr == durable.suppressPr &&
    supplied.parentWorkflowId == durable.parentWorkflowId

internal fun newGoalContinuationConflict(
  request: FeatureTaskRuntimeRunRequest,
  selectedReviewMode: CodeReviewExecutionMode,
): String? {
  val context = requireNotNull(request.goalContinuation)
  return listOfNotNull(
    request.requestedCodeReviewMode?.takeIf { it != selectedReviewMode }?.let {
      "The supplied goal-continuation code-review mode conflicts with the requested child review policy."
    },
    request.parallelReviewAgent
      ?.takeIf { context.parallelReviewAgent != null && it != context.parallelReviewAgent }
      ?.let {
        "The supplied goal-continuation parallel-review agent conflicts with the requested child review policy."
      },
  ).firstOrNull()
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
