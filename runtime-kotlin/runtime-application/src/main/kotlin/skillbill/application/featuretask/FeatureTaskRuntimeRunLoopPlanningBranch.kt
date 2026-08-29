package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.ReviewPassResolution

internal fun FeatureTaskRuntimeRunLoop.clearRecoveredBranchSetupBlock(phaseId: String) {
  if (!state.hasBranchSetupBlock(phaseId)) {
    return
  }
  state.clearBranchSetupBlock(phaseId)
}

internal fun FeatureTaskRuntimeRunLoop.persistBranchSetupBlock(phaseId: String, reason: String) {
  recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = request.workflowId,
      phaseId = phaseId,
      status = STATUS_BLOCKED,
      attemptCount = 1,
      resolvedAgentId = BRANCH_SETUP_AGENT_ID,
      finished = false,
      outputArtifact = null,
      blockedReason = reason,
    ),
    request.dbPathOverride,
  )
  observability.branchSetupBlocked(phaseId, BRANCH_SETUP_AGENT_ID, reason)
}

internal fun FeatureTaskRuntimeRunLoop.blockAt(phaseId: String, reason: String) {
  blocked = FeatureTaskRuntimeRunReport.Blocked(
    issueKey = request.issueKey,
    workflowId = request.workflowId,
    featureSize = request.runInvariants.featureSize.name,
    lastIncompletePhase = phaseId,
    blockedReason = reason,
    completedPhaseIds = state.completedPhaseIds(),
    resolvedBranch = resolvedBranch,
  )
}

internal fun FeatureTaskRuntimeRunLoop.blockOnCapExhaustion(
  phaseId: String,
  transition: FeatureTaskRuntimeNextPhase.TerminalBlock,
) {
  val unresolvedFindings = if (transition.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
    emptyList()
  } else {
    state.unresolvedReviewFindings(phaseId)
  }
  val reason = capExhaustionReason(
    transition.loopId,
    transition.edgeIteration,
    transition.unresolvedVerdict,
    unresolvedFindings,
  )
  val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
    phaseId = phaseId,
    assignment = request.agentAssignment,
    invokedAgentId = request.invokedAgentId,
  )
  val run = PhaseRun(
    phaseId = phaseId,
    declaration = phaseDeclaration(phaseId, request.runInvariants.featureSize, qualityGateSelection()),
    resolvedAgent = resolvedAgent,
    modelDirective = FeatureTaskRuntimeModelResolver.resolve(
      phaseId,
      resolvedAgent.resolvedAgentId,
      request.modelAssignment,
    ),
    compaction = request.compactionSettings.directiveFor(phaseId),
    request = request,
    specSource = specSource,
  )
  blockAndPersist(
    BlockAndPersistArgs(
      run = run,
      attemptCount = state.nextIteration(phaseId),
      reason = reason,
      observability = observability,
      loopId = transition.loopId,
      edgeIteration = transition.edgeIteration,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      payload = BlockAndPersistPayload(outputArtifact = state.outputFor(phaseId)?.payload),
    ),
  )
  blockAt(phaseId, reason)
}

internal fun FeatureTaskRuntimeRunLoop.effectiveEdgeIterationCount(edge: FeatureTaskRuntimeBackwardEdge): Int =
  state.edgeIterationCount(edge.loopId)

internal fun FeatureTaskRuntimeRunLoop.persistResolvedReviewTier(run: PhaseRun, resolution: ReviewPassResolution) {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW || !isGoalContinuationRun(request)) {
    return
  }
  goalContinuationRecorder.updateReviewState(request.workflowId, request.dbPathOverride) { state ->
    state.copy(
      resolvedTier = RuntimeOwnedReviewMode.execute(resolution.resolvedTier),
      decidingRule = resolution.decidingRule,
    )
  }
}

/**
 * Every remediation pass must key one disposition per Blocker its immediately preceding completed
 * pass emitted — including a Blocker that pass introduced itself — so the ids are minted here from
 * that durable pass result rather than invented by the agent. Empty for pass one, which has no
 * prior pass to dispose.
 */
internal fun FeatureTaskRuntimeRunLoop.priorBlockerFindingIds(): List<String> {
  val priorPass = goalReviewStateOrNull()?.passResults?.lastOrNull() ?: return emptyList()
  return priorPass.findings
    .filter { it.severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY }
    // Prefer the id the prior pass's output actually carried, so the ids the prompt asks the agent
    // to disposition against are the ids it saw. The positional id is only a fallback for records
    // written before the review output's own id was captured.
    .mapIndexed { index, finding -> finding.findingId ?: "pass${priorPass.passNumber}-blocker-${index + 1}" }
}

internal fun FeatureTaskRuntimeRunLoop.goalReviewStateOrNull(): GoalSubtaskReviewState? = if (!isGoalContinuationRun(
    request,
  )
) {
  null
} else {
  goalContinuationRecorder.reviewState(request.workflowId, request.dbPathOverride)
}

internal fun FeatureTaskRuntimeRunLoop.pauseAt(phaseId: String, reason: String, resumableStep: String) {
  paused = FeatureTaskRuntimeRunReport.Paused(
    issueKey = request.issueKey,
    workflowId = request.workflowId,
    featureSize = request.runInvariants.featureSize.name,
    pausedPhase = phaseId,
    pauseReason = reason,
    resumableStep = resumableStep,
    completedPhaseIds = state.completedPhaseIds(),
    resolvedBranch = resolvedBranch,
  )
}
