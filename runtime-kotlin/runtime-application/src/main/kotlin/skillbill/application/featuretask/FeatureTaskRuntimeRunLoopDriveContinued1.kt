package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

@Inject
class FeatureTaskRuntimeRunLoopDriveContinued1 {
  fun completeReservedGoalReviewPass(
    runLoop: FeatureTaskRuntimeRunLoop,
    output: String,
    outputMap: Map<String, Any?>,
  ): String? {
    val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(outputMap, runLoop.request.dbPathOverride)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(outputMap, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(outputMap, findings)
    return if (
      runLoop.goalContinuationRecorder.completeGoalReviewPass(
        request = GoalReviewPassCompletionRequest(
          workflowId = runLoop.request.workflowId,
          verdict = outcome.verdict,
          unresolvedFindingCount = outcome.unresolvedFindingCount,
          findings = findings,
          rawReviewResult = output,
          normalizedOutput = outputMap,
          blockerDispositions = GoalSubtaskReviewSummaryReducer.blockerDispositions(
            outputMap,
            runLoop.collaborators.planningBranch.priorBlockerFindingIds(runLoop),
          ),
          commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(outputMap),
        ),
        dbOverride = runLoop.request.dbPathOverride,
      ) == null
    ) {
      "Completed goal-subtask review could not persist its reserved pass."
    } else {
      null
    }
  }

  fun abandonAuditGapSubtask(runLoop: FeatureTaskRuntimeRunLoop, pause: FeatureTaskRuntimeAuditGapPause) {
    runLoop.recorder.persistAuditGapPause(
      runLoop.request.workflowId,
      pause.copy(grantConsumed = true, operatorDecision = null),
      runLoop.request.dbPathOverride,
    )
    runLoop.collaborators.planningBranch.blockAt(
      runLoop,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      "The operator chose abandon_subtask while the subtask was runLoop.session.paused on the audit gap: " +
        pause.reason,
    )
    runLoop.goalContinuationRecorder.recordGoalContinuationState(
      GoalContinuationStateRecordRequest(
        workflowId = runLoop.request.workflowId,
        workflowStatus = STATUS_ABANDONED,
      ),
      dbOverride = runLoop.request.dbPathOverride,
    )
  }

  /**
   * Resume seam for a run parked on an audit-gap pause with an unconsumed retry_fix: settles the
   * paused audit phase from its preserved output (mirroring [carriedForwardGoalReviewSettlement]) so
   * the transition seam can take the audit_gap edge. Returns null when no retry is pending or the
   * grant is stale after a satisfied audit already advanced, letting the normal phase path run.
   */
  internal fun settleCarriedForwardAuditGapAudit(runLoop: FeatureTaskRuntimeRunLoop): PhaseSettlement? = runCatching {
    runLoop.recorder.loadAuditGapPause(runLoop.request.workflowId, runLoop.request.dbPathOverride)
  }.fold(
    onSuccess = { pause ->
      if (pause == null || pause.operatorDecision != AUDIT_GAP_PAUSE_DECISION_RETRY_FIX || pause.grantConsumed) {
        null
      } else {
        settleCarriedForwardAudit(runLoop, pause)
      }
    },
    onFailure = { error ->
      runLoop.collaborators.driveContinued2.blockCarriedForwardAudit(
        runLoop,
        error.message.orEmpty(),
      )
    },
  )

  internal fun settleCarriedForwardAudit(
    runLoop: FeatureTaskRuntimeRunLoop,
    pause: FeatureTaskRuntimeAuditGapPause,
  ): PhaseSettlement {
    val auditPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
    if (
      runLoop.state.isComplete(auditPhaseId) &&
      runLoop.state.verdictFor(auditPhaseId) == FeatureTaskRuntimeVerdict.SATISFIED
    ) {
      consumeAuditGapRetryGrant(runLoop, pause)
      return PhaseSettlement.completed(auditPhaseId, FeatureTaskRuntimeVerdict.SATISFIED)
    }
    val outputArtifact = runLoop.state.recordFor(auditPhaseId)?.outputArtifact
      ?: return runLoop.collaborators.driveContinued2.blockCarriedForwardAudit(runLoop, "missing")
    return runCatching {
      val acceptedOutput = runLoop.outputValidator
        .validatePhaseOutput(outputArtifact, auditPhaseId)
        .requireAcceptedOutput(auditPhaseId)
      val derivedVerdict = FeatureTaskRuntimeOutputVerification.verdictFor(
        auditPhaseId,
        acceptedOutput.normalizedOutput.envelope,
      )
      if (!runLoop.state.isComplete(auditPhaseId)) {
        recordCarriedForwardAudit(runLoop, acceptedOutput.normalizedOutput, acceptedOutput.repairEvidence)
      }
      consumeAuditGapRetryGrant(runLoop, pause)
      PhaseSettlement.completed(auditPhaseId, derivedVerdict)
    }.fold(
      onSuccess = { it },
      onFailure = { error ->
        runLoop.collaborators.driveContinued2.blockCarriedForwardAudit(
          runLoop,
          error.message.orEmpty(),
        )
      },
    )
  }

  fun consumeAuditGapRetryGrant(runLoop: FeatureTaskRuntimeRunLoop, pause: FeatureTaskRuntimeAuditGapPause) {
    runLoop.recorder.persistAuditGapPause(
      runLoop.request.workflowId,
      pause.copy(grantConsumed = true, operatorDecision = null),
      runLoop.request.dbPathOverride,
    )
  }

  fun recordCarriedForwardAudit(
    runLoop: FeatureTaskRuntimeRunLoop,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ) {
    val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
    if (runLoop.state.isComplete(phaseId)) {
      return
    }
    val iteration = runLoop.state.nextIteration(phaseId)
    val priorRecord = runLoop.state.recordFor(phaseId)
    val persisted = runLoop.recorder.recordCompletedPhase(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = runLoop.request.workflowId,
        phaseId = phaseId,
        status = STATUS_COMPLETED,
        attemptCount = iteration,
        resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
        finished = true,
        outputArtifact = normalizedOutput.canonicalJson,
        normalizedOutput = normalizedOutput,
        repairEvidence = repairEvidence,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = priorRecord?.edgeIteration,
      ),
      runLoop.request.dbPathOverride,
    )
    if (!persisted) {
      error("Carried-forward audit could not atomically persist its canonical result.")
    }
    runLoop.state.recordCompleted(
      FeatureTaskRuntimePhaseOutput(
        phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        repairEvidence,
      ),
    )
  }
}
