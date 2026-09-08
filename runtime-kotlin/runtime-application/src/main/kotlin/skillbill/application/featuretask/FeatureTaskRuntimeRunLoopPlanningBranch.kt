package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
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

@Inject
class FeatureTaskRuntimeRunLoopPlanningBranch {
  fun clearRecoveredBranchSetupBlock(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String) {
    if (!runLoop.state.hasBranchSetupBlock(phaseId)) {
      return
    }
    runLoop.state.clearBranchSetupBlock(phaseId)
  }

  fun persistBranchSetupBlock(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String, reason: String) {
    runLoop.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = runLoop.request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = 1,
        resolvedAgentId = BRANCH_SETUP_AGENT_ID,
        finished = false,
        outputArtifact = null,
        blockedReason = reason,
      ),
      runLoop.request.dbPathOverride,
    )
    runLoop.observability.branchSetupBlocked(phaseId, BRANCH_SETUP_AGENT_ID, reason)
  }

  fun blockAt(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String, reason: String) {
    runLoop.session.blocked = FeatureTaskRuntimeRunReport.Blocked(
      issueKey = runLoop.request.issueKey,
      workflowId = runLoop.request.workflowId,
      featureSize = runLoop.request.runInvariants.featureSize.name,
      lastIncompletePhase = phaseId,
      blockedReason = reason,
      completedPhaseIds = runLoop.state.completedPhaseIds(),
      resolvedBranch = runLoop.session.resolvedBranch,
    )
  }

  fun blockOnCapExhaustion(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    transition: FeatureTaskRuntimeNextPhase.TerminalBlock,
  ) {
    val unresolvedFindings = if (transition.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) {
      emptyList()
    } else {
      runLoop.state.unresolvedReviewFindings(phaseId)
    }
    val reason = capExhaustionReason(
      runLoop,
      transition.loopId,
      transition.edgeIteration,
      transition.unresolvedVerdict,
      unresolvedFindings,
    )
    val resolvedAgent = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = runLoop.request.agentAssignment,
      invokedAgentId = runLoop.request.invokedAgentId,
    )
    val run = PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclaration(
        phaseId,
        runLoop.request.runInvariants.featureSize,
        runLoop.collaborators.transitions.qualityGateSelection(runLoop),
      ),
      resolvedAgent = resolvedAgent,
      modelDirective = FeatureTaskRuntimeModelResolver.resolve(
        phaseId,
        resolvedAgent.resolvedAgentId,
        runLoop.request.modelAssignment,
      ),
      compaction = runLoop.request.compactionSettings.directiveFor(phaseId),
      request = runLoop.request,
      specSource = runLoop.specSource,
    )
    runLoop.collaborators.phaseAttemptsContinued2.blockAndPersist(
      runLoop,
      BlockAndPersistArgs(
        run = run,
        attemptCount = runLoop.state.nextIteration(phaseId),
        reason = reason,
        observability = runLoop.observability,
        loopId = transition.loopId,
        edgeIteration = transition.edgeIteration,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        payload = BlockAndPersistPayload(outputArtifact = runLoop.state.outputFor(phaseId)?.payload),
      ),
    )
    blockAt(runLoop, phaseId, reason)
  }

  fun effectiveEdgeIterationCount(runLoop: FeatureTaskRuntimeRunLoop, edge: FeatureTaskRuntimeBackwardEdge): Int =
    runLoop.state.edgeIterationCount(edge.loopId)

  internal fun persistResolvedReviewTier(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    resolution: ReviewPassResolution,
  ) {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ||
      !isGoalContinuationRun(runLoop.request)
    ) {
      return
    }
    runLoop.goalContinuationRecorder.updateReviewState(
      runLoop.request.workflowId,
      runLoop.request.dbPathOverride,
    ) { state ->
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
  fun priorBlockerFindingIds(runLoop: FeatureTaskRuntimeRunLoop): List<String> {
    val priorPass = goalReviewStateOrNull(runLoop)?.passResults?.lastOrNull() ?: return emptyList()
    return priorPass.findings
      .filter { it.severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY }
      // Prefer the id the prior pass's output actually carried, so the ids the prompt asks the agent
      // to disposition against are the ids it saw. The positional id is only a fallback for records
      // written before the review output's own id was captured.
      .mapIndexed { index, finding -> finding.findingId ?: "pass${priorPass.passNumber}-blocker-${index + 1}" }
  }

  fun goalReviewStateOrNull(runLoop: FeatureTaskRuntimeRunLoop): GoalSubtaskReviewState? =
    if (!isGoalContinuationRun(runLoop.request)) {
      null
    } else {
      runLoop.goalContinuationRecorder.reviewState(runLoop.request.workflowId, runLoop.request.dbPathOverride)
    }
}
