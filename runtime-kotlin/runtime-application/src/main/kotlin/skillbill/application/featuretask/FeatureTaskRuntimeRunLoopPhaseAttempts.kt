package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus

internal fun FeatureTaskRuntimeRunLoop.settleIncompleteWork(context: FixLoopBranchContext): PhaseOutcome? {
  val run = context.run
  val attempt = context.attempt
  val loop = context.loop
  val observability = context.observability
  val agentId = context.agentId
  loop.continuationSegmentCount += 1
  if (!recordIncompleteAttempt(run, loop.iteration, attempt)) {
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = loop.iteration,
        reason = "Feature-task-runtime phase '${run.phaseId}' could not durably append its incomplete " +
          "implementation attempt (segment ${loop.continuationSegmentCount}). Continuing would lose the " +
          "continuation projection, so the run stops here rather than retrying against state that was " +
          "never persisted.",
        observability = observability,
        payload = BlockAndPersistPayload(fileManifest = attempt.fileManifest),
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      ),
    )
  }
  loop.iteration += 1
  // This attempt was schema-VALID and merely incomplete, so any correction carried from an
  // earlier malformed attempt is now stale. Leaving it set would hand the next segment both the
  // continuation directive and a schema-rejection directive naming a reason from two attempts
  // ago, telling the agent its valid output was rejected by the schema gate.
  loop.priorCorrection = null
  observability.continuation(
    run.phaseId,
    agentId,
    loop.iteration,
    loop.continuationSegmentCount,
    FeatureTaskRuntimeContinuationKind.IMPLEMENTATION_CONTINUATION,
  )
  return null
}

/**
 * Continues verify_findings after a schema-valid heading-selection pass.
 *
 * The output-gate budget is for agent schema/repair failures (including audit-repair receipts), not
 * for this internal handshake. Charging it here blocked the required body-delivery turn under
 * cap=1. Resolved bodies ride the durable selection into the next briefing; no schema-correction
 * directive is appropriate.
 */
internal fun FeatureTaskRuntimeRunLoop.settleBoundaryBodyDelivery(context: FixLoopBranchContext): PhaseOutcome? {
  val run = context.run
  val loop = context.loop
  val observability = context.observability
  val agentId = context.agentId
  loop.continuationSegmentCount += 1
  loop.iteration += 1
  loop.priorCorrection = null
  observability.continuation(
    run.phaseId,
    agentId,
    loop.iteration,
    loop.continuationSegmentCount,
    FeatureTaskRuntimeContinuationKind.VERIFICATION_BODY_DELIVERY,
  )
  return null
}

/**
 * Sends the round back for the findings it still owes, or blocks when the owed set stopped moving.
 *
 * Both budgets are counted in finding references rather than attempts, which is what keeps a round
 * from being blocked while it still has real repair work left. An omitted finding must be accounted
 * for on the next attempt; a finding reported unresolved gets one more fix attempt and then belongs
 * to an operator.
 */
internal fun FeatureTaskRuntimeRunLoop.settleFindingsOwed(context: FixLoopBranchContext): PhaseOutcome? {
  val run = context.run
  val attempt = context.attempt
  val loop = context.loop
  val observability = context.observability
  val agentId = context.agentId
  val refs = requireNotNull(attempt.findingsOwedRefs)
  val blockReason = when (requireNotNull(attempt.findingsOwedKind)) {
    FindingsOwedKind.OMITTED -> FeatureTaskRuntimeAttemptBudgets.findingCoverageBlockReason(
      run.phaseId,
      refs,
      loop.priorUnaccountedFindings,
    )
    FindingsOwedKind.UNRESOLVED -> FeatureTaskRuntimeAttemptBudgets.unresolvedFindingBlockReason(
      run.phaseId,
      refs,
      loop.priorUnresolvedFindings,
      requireNotNull(attempt.findingsOwedDetail),
    )
  }
  blockReason?.let { reason ->
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = loop.iteration,
        reason = reason,
        observability = observability,
        payload = BlockAndPersistPayload(fileManifest = attempt.fileManifest),
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      ),
    )
  }
  when (attempt.findingsOwedKind) {
    FindingsOwedKind.OMITTED -> loop.priorUnaccountedFindings = refs
    FindingsOwedKind.UNRESOLVED -> loop.priorUnresolvedFindings = loop.priorUnresolvedFindings + refs
    null -> Unit
  }
  loop.itemCoverageSegmentCount += 1
  loop.iteration += 1
  loop.priorCorrection = PriorAttemptCorrection.unaccountedFindings(
    requireNotNull(attempt.findingsOwedRetryReason),
  )
  observability.continuation(
    run.phaseId,
    agentId,
    loop.iteration,
    loop.itemCoverageSegmentCount,
    FeatureTaskRuntimeContinuationKind.ITEM_COVERAGE,
  )
  return null
}

internal fun FeatureTaskRuntimeRunLoop.settleMalformedOutput(context: FixLoopBranchContext): PhaseOutcome? {
  val run = context.run
  val attempt = context.attempt
  val loop = context.loop
  val observability = context.observability
  val agentId = context.agentId
  loop.outputGateFailures += 1
  loop.malformedAttemptCount += 1
  val formatBlock = FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(
    run.phaseId,
    loop.outputGateFailures,
  )
  if (formatBlock == null) {
    loop.iteration += 1
    loop.priorCorrection = PriorAttemptCorrection.schemaGate(
      requireNotNull(attempt.schemaInvalidRetryReason),
      correctiveRepairContext = attempt.correctiveRepairContext,
    )
    observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, loop.malformedAttemptCount)
    return null
  }
  return blockInPhase(
    PhaseBlockRequest(
      run = run,
      attemptCount = loop.iteration,
      reason = withSchemaGateDetail(formatBlock, requireNotNull(attempt.schemaInvalidOperatorReason)),
      observability = observability,
      payload = BlockAndPersistPayload(
        fileManifest = attempt.fileManifest,
        rejectedOutput = attempt.rejectedOutput,
      ),
      failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
    ),
  )
}

/**
 * A retryable `blocked` or `failed` envelope re-entering the loop as itself.
 *
 * It shares the semantic budget with schema-invalid retries but nothing else: the prompt gets the
 * terminal-retry directive rather than the schema-correction one, the block reason is not wrapped in
 * the schema-gate preamble, the block carries the envelope's own disposition instead of
 * INVALID_OUTPUT, and the re-entry is stamped PROCESS_RETRY so the AC-009 status and telemetry
 * surfaces do not report a schema correction that never happened.
 */
internal fun FeatureTaskRuntimeRunLoop.settleRetryableTerminal(context: FixLoopBranchContext): PhaseOutcome? {
  val run = context.run
  val attempt = context.attempt
  val loop = context.loop
  val observability = context.observability
  val agentId = context.agentId
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = loop.iteration,
        reason = "${nonRetryingPhaseSchemaBlockReason(run.phaseId)} " +
          requireNotNull(attempt.retryableOperatorReason),
        observability = observability,
        payload = BlockAndPersistPayload(fileManifest = attempt.fileManifest),
        failureDisposition = requireNotNull(attempt.retryableTerminalDisposition),
      ),
    )
  }
  val failedIteration = loop.semanticIteration
  loop.iteration += 1
  loop.semanticIteration += 1
  loop.priorCorrection =
    PriorAttemptCorrection.retryableTerminal(requireNotNull(attempt.retryableTerminalRetryReason))
  observability.continuation(
    run.phaseId,
    agentId,
    loop.iteration,
    failedIteration,
    FeatureTaskRuntimeContinuationKind.PROCESS_RETRY,
  )
  return null
}

internal fun FeatureTaskRuntimeRunLoop.settleSemanticFailure(context: FixLoopBranchContext): PhaseOutcome? {
  val run = context.run
  val attempt = context.attempt
  val loop = context.loop
  val observability = context.observability
  val agentId = context.agentId
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = loop.iteration,
        reason = withSchemaGateDetail(
          nonRetryingPhaseSchemaBlockReason(run.phaseId),
          requireNotNull(attempt.retryableOperatorReason),
        ),
        observability = observability,
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
    return blockInPhase(
      PhaseBlockRequest(
        run = run,
        attemptCount = loop.iteration,
        reason = withSchemaGateDetail(capReason, requireNotNull(attempt.retryableOperatorReason)),
        observability = observability,
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
  observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, failedIteration)
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
internal fun FeatureTaskRuntimeRunLoop.durableNonOutputAttempts(
  run: PhaseRun,
): List<FeatureTaskRuntimeNonOutputAttempt> =
  state.trailingNonOutputAttempts(run.phaseId) { reason -> isProcessFailureBlockReason(run.phaseId, reason) }

/**
 * True while an operator-reopened phase has not yet run. An operator who reopened a blocked phase
 * has substituted their own judgment for every automatic budget, so the reopened phase must
 * actually relaunch — re-surfacing the block they just acted on makes the reopen a no-op.
 */
internal fun FeatureTaskRuntimeRunLoop.operatorReopenedPhase(phaseId: String): Boolean =
  operatorBlockRetry?.phaseId == phaseId && !operatorBlockRetryCompleted

internal fun FeatureTaskRuntimeRunLoop.durableContinuationSegmentCount(run: PhaseRun): Int {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return 0
  val attempts = recorder.loadImplementationAttempts(run.request.workflowId, run.request.dbPathOverride)
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
internal fun FeatureTaskRuntimeRunLoop.recordIncompleteAttempt(
  run: PhaseRun,
  iteration: Int,
  attempt: AttemptResult,
): Boolean {
  val normalized = attempt.incompleteWorkOutput ?: return false
  return recorder.recordIncompleteImplementationAttempt(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = STATUS_RUNNING,
      attemptCount = iteration.coerceAtLeast(1),
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = false,
      normalizedOutput = normalized,
      loopId = run.reentry?.loopId,
      edgeIteration = run.reentry?.edgeIteration,
    ),
    run.request.dbPathOverride,
  )
}
