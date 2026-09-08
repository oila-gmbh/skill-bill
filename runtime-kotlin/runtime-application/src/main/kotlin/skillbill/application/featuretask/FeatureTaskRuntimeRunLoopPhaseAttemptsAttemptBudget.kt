package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus

@Inject
class FeatureTaskRuntimeRunLoopPhaseAttemptsAttemptBudget {
  internal fun settleSemanticFailure(runLoop: FeatureTaskRuntimeRunLoop, context: FixLoopBranchContext): PhaseOutcome? {
    val run = context.run
    val attempt = context.attempt
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
      return runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason = withSchemaGateDetail(
            nonRetryingPhaseSchemaBlockReason(run.phaseId),
            requireNotNull(attempt.retryableOperatorReason),
          ),
          observability = runLoop.observability,
          payload = BlockAndPersistPayload(
            fileManifest = attempt.fileManifest,
            rejectedOutput = attempt.rejectedOutput,
          ),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
        ),
      )
    }
    loop.outputGateFailures += 1
    FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(run.phaseId, loop.outputGateFailures)?.let { capReason ->
      return runLoop.collaborators.phaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason = withSchemaGateDetail(capReason, requireNotNull(attempt.retryableOperatorReason)),
          observability = runLoop.observability,
          payload = BlockAndPersistPayload(
            fileManifest = attempt.fileManifest,
            rejectedOutput = attempt.rejectedOutput,
          ),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
        ),
      )
    }
    val failedIteration = loop.semanticIteration
    loop.iteration += 1
    loop.semanticIteration += 1
    loop.priorCorrection = attempt.semanticRetryReason?.let { retryReason ->
      PriorAttemptCorrection.schemaGate(
        retryReason,
        correctiveRepairContext = attempt.correctiveRepairContext,
      )
    }
    runLoop.observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, failedIteration)
    return null
  }

  /**
   * Continuation segments already spent on this phase, read from the durable attempt history rather
   * than an in-memory counter. Without this a crash resume would silently refill the budget and the
   * bounded continuation loop would not be bounded across process lifetimes.
   *
   * Scoped to this visit — phase, loop AND edge iteration — matching the continuation projection.
   * Counting earlier rounds of the same loop would charge a brand-new repair round for segments spent
   * on work it was never given, and could block it before its first launch.
   */
  /**
   * The attempts this phase has spent in a row without reaching its output gate, read from the
   * durable ledger so the count survives the crash resume that produced it. Without this the outer
   * resume path charges each relaunch to the semantic repair budget, and a phase that never emitted
   * a byte gets blocked for "invalid output".
   */
  internal fun durableNonOutputAttempts(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): List<FeatureTaskRuntimeNonOutputAttempt> =
    runLoop.state.trailingNonOutputAttempts(run.phaseId) { reason -> isProcessFailureBlockReason(run.phaseId, reason) }

  /**
   * True while an operator-reopened phase has not yet run. An operator who reopened a blocked phase
   * has substituted their own judgment for every automatic budget, so the reopened phase must
   * actually relaunch — re-surfacing the block they just acted on makes the reopen a no-op.
   */
  fun operatorReopenedPhase(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): Boolean =
    runLoop.session.operatorBlockRetry?.phaseId == phaseId && !runLoop.session.operatorBlockRetryCompleted

  internal fun durableContinuationSegmentCount(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): Int {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return 0
    val attempts = runLoop.recorder.loadImplementationAttempts(run.request.workflowId, run.request.dbPathOverride)
      ?: return 0
    return attempts.count {
      it.phaseId == run.phaseId &&
        it.loopId == run.reentry?.loopId &&
        it.edgeIteration == run.reentry?.edgeIteration &&
        it.status == FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE
    }
  }

  /**
   * Appends the incomplete attempt to the durable history, reporting whether it actually landed.
   *
   * A false return must never be swallowed. The continuation projection and the durable segment
   * budget are both derived from this history: a silently dropped append leaves the next segment with
   * no prior receipt AND leaves the segment count at zero, so a crash resume would refill the budget
   * from scratch and the bounded continuation loop would stop being bounded across process lifetimes.
   * Blocking is the only safe response. The ordering fix above removed the one reachable trigger
   * (a non-`implementation_receipt` projection_kind reaching this path); this stays as the
   * defense-in-depth guard for any future empty-patch condition.
   */
}
