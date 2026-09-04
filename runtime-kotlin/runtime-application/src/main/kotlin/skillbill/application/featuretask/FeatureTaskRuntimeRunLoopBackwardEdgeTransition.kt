package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest

fun FeatureTaskRuntimeRunLoopBackwardEdge.persistPlanningStopBlock(
  runLoop: FeatureTaskRuntimeRunLoop,
  phaseId: String,
  reason: String,
) {
  val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = phaseId,
    assignment = runLoop.request.agentAssignment,
    invokedAgentId = runLoop.request.invokedAgentId,
  ).resolvedAgentId
  runLoop.recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = runLoop.request.workflowId,
      phaseId = phaseId,
      status = STATUS_BLOCKED,
      attemptCount = 1,
      resolvedAgentId = resolvedAgentId,
      finished = false,
      outputArtifact = null,
      blockedReason = reason,
    ),
    runLoop.request.dbPathOverride,
  )
  runLoop.observability.blocked(phaseId, resolvedAgentId, 1, reason)
}

fun FeatureTaskRuntimeRunLoopBackwardEdge.establishBranchIfNeeded(
  runLoop: FeatureTaskRuntimeRunLoop,
  phaseId: String,
): String? {
  if (!isFileMutating(phaseId)) {
    return null
  }
  val setup = runLoop.branchSetupRunner.ensureFeatureBranch(runLoop.request, runLoop.observability)
  return setup.blockedReason?.also { reason ->
    runLoop.collaborators.planningBranch.persistBranchSetupBlock(
      runLoop,
      phaseId,
      reason,
    )
  } ?: run {
    runLoop.session.resolvedBranch = requireNotNull(setup.establishedBranch)
    runLoop.collaborators.planningBranch.clearRecoveredBranchSetupBlock(runLoop, phaseId)
    null
  }
}
