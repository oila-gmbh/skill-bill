package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition

@Inject
class FeatureTaskRuntimeRunLoopPhaseAttempts {
  internal fun settleIncompleteWork(runLoop: FeatureTaskRuntimeRunLoop, context: FixLoopBranchContext): PhaseOutcome? {
    val run = context.run
    val attempt = context.attempt
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    loop.continuationSegmentCount += 1
    if (!runLoop.collaborators.phaseAttemptsContinued2.recordIncompleteAttempt(runLoop, run, loop.iteration, attempt)) {
      return blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason = "Feature-task-runtime phase '${run.phaseId}' could not durably append its incomplete " +
            "implementation attempt (segment ${loop.continuationSegmentCount}). Continuing would lose the " +
            "continuation projection, so the run stops here rather than retrying against runLoop.state that was " +
            "never persisted.",
          observability = runLoop.observability,
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
    runLoop.observability.continuation(
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
  internal fun settleBoundaryBodyDelivery(
    runLoop: FeatureTaskRuntimeRunLoop,
    context: FixLoopBranchContext,
  ): PhaseOutcome? {
    val run = context.run
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    loop.continuationSegmentCount += 1
    loop.iteration += 1
    loop.priorCorrection = null
    runLoop.observability.continuation(
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
  internal fun settleFindingsOwed(runLoop: FeatureTaskRuntimeRunLoop, context: FixLoopBranchContext): PhaseOutcome? {
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
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason = reason,
          observability = runLoop.observability,
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
    runLoop.observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      loop.itemCoverageSegmentCount,
      FeatureTaskRuntimeContinuationKind.ITEM_COVERAGE,
    )
    return null
  }

  internal fun settleMalformedOutput(runLoop: FeatureTaskRuntimeRunLoop, context: FixLoopBranchContext): PhaseOutcome? {
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
      runLoop.observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, loop.malformedAttemptCount)
      return null
    }
    return blockInPhase(
      runLoop,
      PhaseBlockRequest(
        run = run,
        attemptCount = loop.iteration,
        reason = withSchemaGateDetail(formatBlock, requireNotNull(attempt.schemaInvalidOperatorReason)),
        observability = runLoop.observability,
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
  internal fun settleRetryableTerminal(
    runLoop: FeatureTaskRuntimeRunLoop,
    context: FixLoopBranchContext,
  ): PhaseOutcome? {
    val run = context.run
    val attempt = context.attempt
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
      return blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason = "${nonRetryingPhaseSchemaBlockReason(run.phaseId)} " +
            requireNotNull(attempt.retryableOperatorReason),
          observability = runLoop.observability,
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
    runLoop.observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      failedIteration,
      FeatureTaskRuntimeContinuationKind.PROCESS_RETRY,
    )
    return null
  }
  internal fun blockInPhase(runLoop: FeatureTaskRuntimeRunLoop, request: PhaseBlockRequest): PhaseOutcome =
    runLoop.collaborators.phaseAttemptsContinued2.blockAndPersistInPhase(
      runLoop,
      phaseBlockArgs(
        request.run,
        request.attemptCount,
        request.reason,
        request.observability,
        request.payload,
      ).withDisposition(request.failureDisposition),
    )
}
