package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

internal fun FeatureTaskRuntimeRunLoop.blockInvalidAuditGapRecovery(reentry: PendingReentry, reason: String) {
  val phaseId = reentry.phaseId
  val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = phaseId,
    assignment = request.agentAssignment,
    invokedAgentId = request.invokedAgentId,
  ).resolvedAgentId
  val attempt = state.nextIteration(phaseId)
  val previous = state.recordFor(phaseId)
  recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = request.workflowId,
      phaseId = phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attempt,
      resolvedAgentId = resolvedAgentId,
      finished = false,
      outputArtifact = previous?.outputArtifact,
      rejectedOutput = previous?.rejectedOutput,
      blockedReason = reason,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      loopId = reentry.loopId,
      edgeIteration = reentry.edgeIteration,
    ),
    request.dbPathOverride,
  )
  observability.blocked(phaseId, resolvedAgentId, attempt, reason)
  blockAt(phaseId, reason)
}

internal fun FeatureTaskRuntimeRunLoop.applyPlanningStop(
  phaseId: String,
  planOutput: FeatureTaskRuntimePhaseOutput,
): String? {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
    return null
  }
  return when (val decision = resolvePlanningStop(planOutput)) {
    is FeatureTaskRuntimePlanningStopDecision.Proceed -> null
    is FeatureTaskRuntimePlanningStopDecision.Decomposed -> {
      decomposed = decision.report
      null
    }
    is FeatureTaskRuntimePlanningStopDecision.Blocked -> {
      persistPlanningStopBlock(phaseId, decision.reason)
      decision.reason
    }
  }
}

internal fun FeatureTaskRuntimeRunLoop.resolvePlanningStop(
  planOutput: FeatureTaskRuntimePhaseOutput,
): FeatureTaskRuntimePlanningStopDecision = planningStopper.resolve(
  request = request,
  completedOutput = planOutput,
  completedPhaseIds = state.completedPhaseIds(),
  resolvedBranch = resolvedBranch,
  specSource = specSource,
)

internal fun FeatureTaskRuntimeRunLoop.persistPlanningStopBlock(phaseId: String, reason: String) {
  val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = phaseId,
    assignment = request.agentAssignment,
    invokedAgentId = request.invokedAgentId,
  ).resolvedAgentId
  recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = request.workflowId,
      phaseId = phaseId,
      status = STATUS_BLOCKED,
      attemptCount = 1,
      resolvedAgentId = resolvedAgentId,
      finished = false,
      outputArtifact = null,
      blockedReason = reason,
    ),
    request.dbPathOverride,
  )
  observability.blocked(phaseId, resolvedAgentId, 1, reason)
}

internal fun FeatureTaskRuntimeRunLoop.establishBranchIfNeeded(phaseId: String): String? {
  if (!isFileMutating(phaseId)) {
    return null
  }
  val setup = branchSetupRunner.ensureFeatureBranch(request, observability)
  return setup.blockedReason?.also { reason -> persistBranchSetupBlock(phaseId, reason) } ?: run {
    resolvedBranch = requireNotNull(setup.establishedBranch)
    clearRecoveredBranchSetupBlock(phaseId)
    null
  }
}
