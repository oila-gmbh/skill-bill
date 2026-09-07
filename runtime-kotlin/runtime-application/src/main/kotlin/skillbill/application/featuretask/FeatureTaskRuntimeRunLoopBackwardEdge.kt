package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

object FeatureTaskRuntimeRunLoopBackwardEdge {
  fun resumeInFlightReviewFix(runLoop: FeatureTaskRuntimeRunLoop, edge: FeatureTaskRuntimeBackwardEdge): String? {
    if (
      edge.loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID ||
      runLoop.state.isLoopLiveClaimed(edge.loopId) ||
      runLoop.state.isComplete(edge.destinationPhaseId)
    ) {
      return null
    }
    val destinationRecord = runLoop.state.recordFor(edge.destinationPhaseId)
      ?.takeIf { it.loopId == edge.loopId && it.edgeIteration == runLoop.state.edgeIterationCount(edge.loopId) }
      ?: return null
    val edgeIteration = requireNotNull(destinationRecord.edgeIteration)
    runLoop.state.reopenForReentry(edge.fromPhaseId)
    runLoop.state.recordEdgeIteration(edge.loopId, edgeIteration)
    runLoop.session.pendingReentry = PendingReentry(
      phaseId = edge.destinationPhaseId,
      loopId = edge.loopId,
      edgeIteration = edgeIteration,
      drivingVerdict = edge.triggeringVerdict,
      expectedRepositoryCheckpoint = FeatureTaskRuntimeRunLoopDrive.reviewedCheckpointFingerprint(runLoop),
    )
    runLoop.session.activeReentry = runLoop.session.pendingReentry
    return edge.destinationPhaseId
  }

  internal fun recordBackwardEdge(runLoop: FeatureTaskRuntimeRunLoop, args: BackwardEdgeRecordArgs) {
    val edge = args.edge
    val destinationPhaseId = args.destinationPhaseId
    val loopId = args.loopId
    val edgeIteration = args.edgeIteration
    val verdict = args.verdict
    val reopenedSpan = FeatureTaskRuntimeRunLoopTransitions.spanBetween(runLoop, destinationPhaseId, edge.fromPhaseId)
    reopenedSpan.forEach(runLoop.state::reopenForReentry)
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      runLoop.state.invalidateProducerOutput(destinationPhaseId)
      runLoop.recorder.invalidateQuarantinedProducerRecord(
        runLoop.request.workflowId,
        destinationPhaseId,
        loopId,
        edgeIteration,
        runLoop.request.dbPathOverride,
      )
    }
    runLoop.state.recordEdgeIteration(loopId, edgeIteration)
    runLoop.session.pendingReentry = PendingReentry(
      destinationPhaseId,
      loopId,
      edgeIteration,
      verdict,
      emptyList(),
      if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) {
        FeatureTaskRuntimeRunLoopDrive.reviewedCheckpointFingerprint(runLoop)
      } else {
        null
      },
    )
    runLoop.session.activeReentry = runLoop.session.pendingReentry
    runLoop.observability.loopEdge(destinationPhaseId, loopId, edgeIteration, verdict)
    warnOnThresholdCrossing(runLoop, edge, edgeIteration)
  }

  fun warnOnThresholdCrossing(
    runLoop: FeatureTaskRuntimeRunLoop,
    edge: FeatureTaskRuntimeBackwardEdge,
    edgeIteration: Int,
  ) {
    val threshold = edge.warnAfterIterations ?: return
    if (edgeIteration != threshold + 1) return
    runCatching {
      runLoop.diagnostics.warning(
        thresholdCrossingWarning(
          runLoop,
          edge.loopId,
          threshold,
          edgeIteration,
        ),
      )
    }
  }

  fun thresholdCrossingWarning(
    runLoop: FeatureTaskRuntimeRunLoop,
    loopId: String,
    threshold: Int,
    edgeIteration: Int,
  ): String = "Remediation loop '$loopId' exceeded its warning threshold of $threshold: entering iteration " +
    "$edgeIteration for issue ${runLoop.request.issueKey}, workflow ${runLoop.request.workflowId}, subtask " +
    "${runLoop.request.goalContinuation?.subtaskId ?: runLoop.request.issueKey}, spec " +
    "${runLoop.request.runInvariants.specReference}."

  fun capExhaustedOnResume(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? {
    if (FeatureTaskRuntimeRunLoopPhaseAttempts.operatorReopenedPhase(runLoop, phaseId)) return null
    val record = runLoop.state.recordFor(phaseId) ?: return null
    return capExhaustionForRecord(runLoop, phaseId, record)
  }

  fun capExhaustionForRecord(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    record: FeatureTaskRuntimePhaseRecord,
  ): String? {
    val loopId = record.loopId
    val iteration = record.edgeIteration
    if (loopId == null || iteration == null || runLoop.state.isLoopLiveClaimed(loopId)) {
      return null
    }
    val edge = runLoop.transitions.backwardEdges.firstOrNull { candidate ->
      candidate.loopId == loopId &&
        (candidate.destinationPhaseId == phaseId || candidate.fromPhaseId == phaseId)
    }
    if (edge?.destinationPhaseId == phaseId) {
      val sourceRecord = runLoop.state.recordFor(edge.fromPhaseId)
      if (
        sourceRecord?.status == STATUS_BLOCKED && sourceRecord.loopId == loopId &&
        sourceRecord.edgeIteration == iteration
      ) {
        return null
      }
    }
    return edge
      ?.takeIf { candidate -> blocksWhenCapExhausted(candidate, iteration) }
      ?.let {
        FeatureTaskRuntimeRunLoopPlanningBranch.capExhaustionReason(
          runLoop,
          it.loopId,
          iteration,
          it.triggeringVerdict,
        )
      }
  }

  fun blocksWhenCapExhausted(edge: FeatureTaskRuntimeBackwardEdge, iteration: Int): Boolean =
    edge.capExhaustionBehavior == FeatureTaskRuntimeCapExhaustionBehavior.BLOCK &&
      edge.perEdgeCap?.let { iteration >= it } == true

  fun runPhaseFor(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? {
    val briefingReentry = runLoop.session.pendingReentry?.takeIf { it.phaseId == phaseId }
    if (briefingReentry != null) runLoop.session.pendingReentry = null
    val reentry = briefingReentry ?: runLoop.session.activeReentry?.takeIf { active ->
      runLoop.transitions.backwardEdges
        .firstOrNull { it.loopId == active.loopId }
        ?.let { edge ->
          phaseId in FeatureTaskRuntimeRunLoopTransitions.spanBetween(
            runLoop,
            edge.destinationPhaseId,
            edge.fromPhaseId,
          )
        } == true
    }?.copy(phaseId = phaseId, reentryGapCriteria = emptyList())
    val outcome = FeatureTaskRuntimeRunLoopPlanningBranch.runPhase(
      runLoop,
      RunPhaseArgs(
        phaseId = phaseId,
        request = runLoop.request,
        state = runLoop.state,
        observability = runLoop.observability,
        specSource = runLoop.specSource,
        reentry = reentry,
        phaseTokenAccumulator = runLoop.phaseTokenAccumulator,
      ),
    )
    outcome.regenerationTargetPhaseId?.let {
      runLoop.session.recordRejectionSettlementPending = true
      return null
    }
    outcome.pausedReason?.let { return it }
    return outcome.blockedReason ?: run {
      val completedOutput = requireNotNull(outcome.completedOutput)
      runLoop.state.recordCompleted(completedOutput)
      if (runLoop.session.operatorBlockRetry?.phaseId == phaseId) runLoop.session.operatorBlockRetryCompleted = true
      applyPlanningStop(runLoop, phaseId, completedOutput)
    }
  }

  internal fun isLoopDestination(runLoop: FeatureTaskRuntimeRunLoop, reentry: PendingReentry): Boolean =
    runLoop.transitions.backwardEdges.firstOrNull { it.loopId == reentry.loopId }?.destinationPhaseId == reentry.phaseId

  internal fun blockInvalidAuditGapRecovery(
    runLoop: FeatureTaskRuntimeRunLoop,
    reentry: PendingReentry,
    reason: String,
  ) {
    val phaseId = reentry.phaseId
    val resolvedAgentId = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = phaseId,
      assignment = runLoop.request.agentAssignment,
      invokedAgentId = runLoop.request.invokedAgentId,
    ).resolvedAgentId
    val attempt = runLoop.state.nextIteration(phaseId)
    val previous = runLoop.state.recordFor(phaseId)
    runLoop.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = runLoop.request.workflowId,
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
      runLoop.request.dbPathOverride,
    )
    runLoop.observability.blocked(phaseId, resolvedAgentId, attempt, reason)
    FeatureTaskRuntimeRunLoopPlanningBranch.blockAt(runLoop, phaseId, reason)
  }

  fun applyPlanningStop(
    runLoop: FeatureTaskRuntimeRunLoop,
    phaseId: String,
    planOutput: FeatureTaskRuntimePhaseOutput,
  ): String? {
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
      return null
    }
    return when (val decision = resolvePlanningStop(runLoop, planOutput)) {
      is FeatureTaskRuntimePlanningStopDecision.Proceed -> null
      is FeatureTaskRuntimePlanningStopDecision.Decomposed -> {
        runLoop.session.decomposed = decision.report
        null
      }
      is FeatureTaskRuntimePlanningStopDecision.Blocked -> {
        persistPlanningStopBlock(runLoop, phaseId, decision.reason)
        decision.reason
      }
    }
  }

  fun resolvePlanningStop(
    runLoop: FeatureTaskRuntimeRunLoop,
    planOutput: FeatureTaskRuntimePhaseOutput,
  ): FeatureTaskRuntimePlanningStopDecision = runLoop.planningStopper.resolve(
    request = runLoop.request,
    completedOutput = planOutput,
    completedPhaseIds = runLoop.state.completedPhaseIds(),
    resolvedBranch = runLoop.session.resolvedBranch,
    specSource = runLoop.specSource,
  )

  fun persistPlanningStopBlock(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String, reason: String) {
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

  fun establishBranchIfNeeded(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): String? {
    if (!isFileMutating(phaseId)) {
      return null
    }
    val setup = runLoop.branchSetupRunner.ensureFeatureBranch(runLoop.request, runLoop.observability)
    return setup.blockedReason?.also { reason ->
      FeatureTaskRuntimeRunLoopPlanningBranch.persistBranchSetupBlock(
        runLoop,
        phaseId,
        reason,
      )
    } ?: run {
      runLoop.session.resolvedBranch = requireNotNull(setup.establishedBranch)
      FeatureTaskRuntimeRunLoopPlanningBranch.clearRecoveredBranchSetupBlock(runLoop, phaseId)
      null
    }
  }
}
