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

  internal fun settleSemanticFailure(runLoop: FeatureTaskRuntimeRunLoop, context: FixLoopBranchContext): PhaseOutcome? {
    val run = context.run
    val attempt = context.attempt
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)) {
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
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
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
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

  internal fun durableNonOutputAttempts(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): List<FeatureTaskRuntimeNonOutputAttempt> =
    runLoop.state.trailingNonOutputAttempts(run.phaseId) { reason -> isProcessFailureBlockReason(run.phaseId, reason) }

  fun operatorReopenedPhase(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String): Boolean =
    runLoop.session.operatorBlockRetry?.phaseId == phaseId && !runLoop.session.operatorBlockRetryCompleted

  internal fun durableContinuationSegmentCount(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): Int {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return 0
    val attempts = runLoop.recorder.loadImplementationAttempts(run.request.workflowId)
      ?: return 0
    return attempts.count {
      it.phaseId == run.phaseId &&
        it.loopId == run.reentry?.loopId &&
        it.edgeIteration == run.reentry?.edgeIteration &&
        it.status == FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE
    }
  }

  internal fun recordIncompleteAttempt(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    iteration: Int,
    attempt: AttemptResult,
  ): Boolean {
    val normalized = attempt.incompleteWorkOutput ?: return false
    return runLoop.recorder.recordIncompleteImplementationAttempt(
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
    )
  }

  internal fun blockAndPersist(runLoop: FeatureTaskRuntimeRunLoop, args: BlockAndPersistArgs): PhaseOutcome {
    val run = args.run
    val attemptCount = args.attemptCount
    val reason = args.reason
    val observability = args.observability
    val loopId = args.loopId
    val edgeIteration = args.edgeIteration
    val failureDisposition = args.failureDisposition
    val fileManifest = args.payload.fileManifest
    val outputArtifact = args.payload.outputArtifact
    val normalizedOutput = args.payload.normalizedOutput
    val repairEvidence = args.payload.repairEvidence
    val rejectedOutput = args.payload.rejectedOutput
    val childNeverLaunched = args.payload.childNeverLaunched
    val phaseState = FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = STATUS_BLOCKED,
      attemptCount = attemptCount.coerceAtLeast(1),
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = false,
      outputArtifact = normalizedOutput?.canonicalJson
        ?: outputArtifact
        ?: runLoop.state.outputFor(run.phaseId)?.payload,
      rejectedOutput = rejectedOutput,
      normalizedOutput = normalizedOutput,
      repairEvidence = repairEvidence,
      blockedReason = reason,
      failureDisposition = failureDisposition,
      fileManifestBefore = fileManifest?.before.orEmpty(),
      fileManifestAfter = fileManifest?.after.orEmpty(),
      fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
      loopId = loopId,
      edgeIteration = edgeIteration,
      reviewPassNumber = FeatureTaskRuntimeRunLoopOutputPersistence.reviewPassNumber(runLoop, run, runLoop.state),
      launchOutcomeKnown = childNeverLaunched,
    )
    runLoop.state.reserveReviewPass(phaseState.reviewPassNumber)
    runLoop.recorder.recordPhaseState(
      phaseState,
    )
    runLoop.observability.blocked(run.phaseId, run.resolvedAgent.resolvedAgentId, attemptCount.coerceAtLeast(1), reason)
    return PhaseOutcome.blocked(reason)
  }

  internal fun pauseAndPersistInPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PauseAndPersistInPhaseArgs,
  ): PhaseOutcome {
    val run = args.run
    val attemptCount = args.attemptCount
    val reason = args.reason
    val observability = args.observability
    val fileManifest = args.fileManifest
    val attempt = attemptCount.coerceAtLeast(1)
    if (isGoalContinuationRun(runLoop.request)) {
      runLoop.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = runLoop.request.workflowId,
          workflowStatus = STATUS_PAUSED,
        ),
      )
    }
    runLoop.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = runLoop.request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_PAUSED,
        attemptCount = attempt,
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        outputArtifact = runLoop.state.outputFor(run.phaseId)?.payload,
        blockedReason = reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.RETRYABLE,
        fileManifestBefore = fileManifest?.before.orEmpty(),
        fileManifestAfter = fileManifest?.after.orEmpty(),
        fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
        launchOutcomeKnown = false,
      ),
    )
    observability.paused(run.phaseId, run.resolvedAgent.resolvedAgentId, attempt, reason)
    FeatureTaskRuntimeRunLoopPlanningBranch.pauseAt(runLoop, run.phaseId, reason, run.phaseId)
    return PhaseOutcome.paused(reason)
  }

  internal fun blockAndPersistInPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: BlockAndPersistInPhaseArgs,
  ): PhaseOutcome = blockAndPersist(
    runLoop,
    BlockAndPersistArgs(
      run = args.run,
      attemptCount = args.attemptCount,
      reason = args.reason,
      observability = args.observability,
      loopId = args.run.reentry?.loopId,
      edgeIteration = args.run.reentry?.edgeIteration,
      failureDisposition = args.failureDisposition,
      payload = args.payload,
    ),
  )

  internal fun settleRecordRejection(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: SettleRecordRejectionArgs,
  ): PhaseOutcome {
    val run = args.run
    val state = args.state
    val iteration = args.iteration
    val observability = args.observability
    val rejection = args.rejection
    val regeneration = FeatureTaskRuntimeRunLoopPhaseAttempts.recordRejectionRegenerationEdge(
      runLoop,
      run.phaseId,
    )
    if (regeneration == null) {
      return FeatureTaskRuntimeRunLoopRecordRejection.blockUnattributableRecordRejection(
        runLoop,
        UnattributableRecordRejectionArgs(
          context = PhaseAttemptContext(run, state, iteration, observability),
          rejection = rejection,
          producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER[run.phaseId],
        ),
      )
    }
    val attemptContext = PhaseAttemptContext(run, state, iteration, observability)
    val evidenceResolution = FeatureTaskRuntimeRunLoopPhaseAttempts
      .readProducerEvidenceForRecordRejection(
        runLoop,
        ProducerEvidenceRecordRejectionArgs(
          context = attemptContext,
          producer = regeneration.producer,
          consumer = run.phaseId,
        ),
      )
    return when (evidenceResolution) {
      is FeatureTaskRuntimeRunLoopPhaseAttempts
        .RecordRejectionEvidenceResolution.Settled,
      -> evidenceResolution.outcome
      is FeatureTaskRuntimeRunLoopPhaseAttempts.RecordRejectionEvidenceResolution.Ready ->
        FeatureTaskRuntimeRunLoopPhaseAttempts.quarantineRecordRejection(
          runLoop,
          QuarantineRecordRejectionArgs(
            context = attemptContext,
            rejection = rejection,
            regeneration = regeneration,
            producerEvidence = evidenceResolution.evidence,
          ),
        )
    }
  }

  internal data class RecordRejectionRegenerationEdge(
    val producer: String,
    val edge: FeatureTaskRuntimeBackwardEdge,
  )

  internal fun recordRejectionRegenerationEdge(
    runLoop: FeatureTaskRuntimeRunLoop,
    consumer: String,
  ): FeatureTaskRuntimeRunLoopPhaseAttempts.RecordRejectionRegenerationEdge? {
    val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER[consumer] ?: return null
    val edge = runLoop.transitions.backwardEdges.firstOrNull {
      it.fromPhaseId == consumer && it.destinationPhaseId == producer &&
        it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED
    } ?: return null
    if (producer !in runLoop.transitions.forwardPhaseIds) return null
    return FeatureTaskRuntimeRunLoopPhaseAttempts.RecordRejectionRegenerationEdge(producer, edge)
  }

  internal sealed interface RecordRejectionEvidenceResolution {
    data class Ready(val evidence: ProducerOutputEvidence) : RecordRejectionEvidenceResolution
    data class Settled(val outcome: PhaseOutcome) : RecordRejectionEvidenceResolution
  }

  internal fun readProducerEvidenceForRecordRejection(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ProducerEvidenceRecordRejectionArgs,
  ): RecordRejectionEvidenceResolution {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val observability = args.context.observability
    val producer = args.producer
    val consumer = args.consumer
    val producingIteration =
      (runLoop.state.outputFor(producer)?.iteration ?: runLoop.state.recordFor(producer)?.attemptCount ?: 1)
        .coerceAtLeast(1)
    val producerAgentId = runLoop.state.recordFor(producer)?.resolvedAgentId
      ?: return RecordRejectionEvidenceResolution.Settled(
        missingProducerAgentBlock(
          runLoop,
          MissingProducerAgentBlockArgs(run, iteration, consumer, producer, runLoop.observability),
        ),
      )
    return when (
      val producerRead = runLoop.recorder.producerOutput(
        ProducerOutputQueryArgs(
          workflowId = runLoop.request.workflowId,
          phaseId = producer,
          attempt = producingIteration,
          agentId = producerAgentId,
          generation = runLoop.state.evidenceGeneration(producer),
        ),
      )
    ) {
      is FeatureTaskRuntimeProducerOutputRead.Found ->
        RecordRejectionEvidenceResolution.Ready(producerRead.evidence)
      is FeatureTaskRuntimeProducerOutputRead.Absent,
      is FeatureTaskRuntimeProducerOutputRead.Unreadable,
      -> RecordRejectionEvidenceResolution.Settled(
        missingProducerEvidenceBlock(
          runLoop,
          MissingProducerEvidenceBlockArgs(
            run = run,
            iteration = iteration,
            consumer = consumer,
            producer = producer,
            producingIteration = producingIteration,
            producerRead = producerRead,
            observability = runLoop.observability,
          ),
        ),
      )
    }
  }

  private fun missingProducerAgentBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: MissingProducerAgentBlockArgs,
  ): PhaseOutcome = FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
    runLoop,
    PhaseBlockRequest(
      run = args.run,
      attemptCount = args.iteration,
      reason = "Feature-task-runtime phase '${args.consumer}' rejected the durable record produced by " +
        "'${args.producer}', but the producing phase's resolved agent is unavailable, so exact raw " +
        "evidence cannot be scoped to a producer. The run blocks instead of fabricating a " +
        "rejected-output diagnostic.",
      observability = args.observability,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    ),
  )

  private data class MissingProducerEvidenceBlockArgs(
    val run: PhaseRun,
    val iteration: Int,
    val consumer: String,
    val producer: String,
    val producingIteration: Int,
    val producerRead: FeatureTaskRuntimeProducerOutputRead,
    val observability: FeatureTaskRuntimeRunObservability,
  )

  private fun missingProducerEvidenceBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: MissingProducerEvidenceBlockArgs,
  ): PhaseOutcome {
    val evidenceClause = if (args.producerRead is FeatureTaskRuntimeProducerOutputRead.Unreadable) {
      "retained evidence for attempt ${args.producingIteration} exists and the diagnostic store " +
        "refused it (${args.producerRead.failureClass.wireValue}). The run blocks instead of " +
        "fabricating a rejected-output diagnostic from normalized workflow runLoop.state."
    } else {
      "no retained evidence exists for attempt ${args.producingIteration}. The run blocks instead " +
        "of fabricating a rejected-output diagnostic from normalized workflow runLoop.state."
    }
    return FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
      runLoop,
      PhaseBlockRequest(
        run = args.run,
        attemptCount = args.iteration,
        reason = "Feature-task-runtime phase '${args.consumer}' rejected the durable record " +
          "produced by '${args.producer}', but $evidenceClause",
        observability = args.observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      ),
    )
  }

  internal fun quarantineRecordRejection(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: QuarantineRecordRejectionArgs,
  ): PhaseOutcome = quarantineRecordRejectionBody(runLoop, args)

  internal fun quarantineRecordRejectionBody(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: QuarantineRecordRejectionArgs,
  ): PhaseOutcome {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val rejection = args.rejection
    val regeneration = args.regeneration
    val producerEvidence = args.producerEvidence
    val consumer = run.phaseId
    val producer = regeneration.producer
    val producingIteration =
      (runLoop.state.outputFor(producer)?.iteration ?: runLoop.state.recordFor(producer)?.attemptCount ?: 1)
        .coerceAtLeast(1)
    val diagnosticWrite = writeQuarantineRejectedOutput(
      runLoop,
      WriteQuarantineRejectedOutputArgs(run, producingIteration, rejection, producer, producerEvidence),
    )
    appendQuarantineEntryForRejection(
      runLoop,
      QuarantineEntryWriteArgs(
        consumer = consumer,
        producer = producer,
        producingIteration = producingIteration,
        rejection = rejection,
        regenerationAttempt = (runLoop.state.edgeIterationCount(regeneration.edge.loopId) + 1).coerceAtLeast(1),
        iteration = iteration,
        diagnosticWrite = diagnosticWrite,
        producerEvidence = producerEvidence,
      ),
    )
    return PhaseOutcome.regenerateProducer(producer)
  }

  private fun writeQuarantineRejectedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: WriteQuarantineRejectedOutputArgs,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val run = args.run
    val producingIteration = args.producingIteration
    val rejection = args.rejection
    val producer = args.producer
    val producerEvidence = args.producerEvidence
    val rejectedPayload = producerEvidence.payload ?: byteArrayOf()
    return FeatureTaskRuntimeRunLoopAttemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = run,
        iteration = producingIteration,
        rule = "reconciliation-${rejection.rejectionClass}",
        reason = FeatureTaskRuntimeRunLoopRecordRejection.retryRejectionReason(
          FeatureTaskRuntimeRunLoopRecordRejection.payloadFreeRejectionReason(
            "reconciliation-${rejection.rejectionClass}",
            FeatureTaskRuntimeRunLoopRecordRejection.rejectionPath(rejection.rejectionDetail),
          ),
          rejection.rejectionDetail,
        ),
        captured = CapturedPhaseOutput(
          text = rejectedPayload.decodeToString(),
          bytes = rejectedPayload,
          truncated = producerEvidence.payload == null,
          byteSize = producerEvidence.byteSize,
          sha256 = producerEvidence.sha256,
        ),
        targeting = FeatureTaskRuntimeRunLoopAttemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(
            run,
            RejectedOutputTargetingOverrides(
              phaseId = producer,
              agentId = producerEvidence.agentId,
              model = producerEvidence.model,
              path = FeatureTaskRuntimeRunLoopRecordRejection.rejectionPath(rejection.rejectionDetail),
              repairTurn = producerEvidence.repairTurn,
            ),
          ),
        ),
      ),
    )
  }

  private data class QuarantineEntryWriteArgs(
    val consumer: String,
    val producer: String,
    val producingIteration: Int,
    val rejection: RecordRejection,
    val regenerationAttempt: Int,
    val iteration: Int,
    val diagnosticWrite: FeatureTaskRuntimeRejectedOutputWrite,
    val producerEvidence: ProducerOutputEvidence,
  )

  private fun appendQuarantineEntryForRejection(runLoop: FeatureTaskRuntimeRunLoop, args: QuarantineEntryWriteArgs) {
    runLoop.recorder.appendQuarantineEntry(
      runLoop.request.workflowId,
      FeatureTaskRuntimeQuarantineEntry(
        producingPhaseId = args.producer,
        consumingPhaseId = args.consumer,
        producingIteration = args.producingIteration,
        rejectionClass = args.rejection.rejectionClass,
        rejectionDetail = FeatureTaskRuntimeRunLoopRecordRejection.payloadFreeRejectionReason(
          "reconciliation-${args.rejection.rejectionClass}",
          FeatureTaskRuntimeRunLoopRecordRejection.rejectionPath(args.rejection.rejectionDetail),
        ),
        regenerationAttempt = args.regenerationAttempt,
        quarantinedAtIteration = args.iteration.coerceAtLeast(1),
        diagnosticIdentity =
        (args.diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.identity,
        rejectedRecordByteSize = args.producerEvidence.byteSize,
        rejectedRecordSha256 = args.producerEvidence.sha256,
        diagnosticDegraded = args.diagnosticWrite is FeatureTaskRuntimeRejectedOutputWrite.Degraded,
      ),
    )
  }}
