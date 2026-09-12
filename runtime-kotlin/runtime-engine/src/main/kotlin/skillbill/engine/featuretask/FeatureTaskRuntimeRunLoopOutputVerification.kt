package skillbill.engine.featuretask

import skillbill.application.reviewevidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.reviewevidence.model.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.engine.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.engine.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPauseKind
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairSnapshot
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.validateDispositionCoverage

object FeatureTaskRuntimeRunLoopOutputVerification {
  internal fun attestAbsentGateValidationReceipt(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val eligible = run.agentRunValidateFallback &&
      run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE &&
      (normalizedOutput.envelope[SharedPayloadKeys.STATUS] as? String)
        .workflowStepStatus() == WorkflowStepStatus.COMPLETED
    if (!eligible) return normalizedOutput
    val produced = JsonCodec.anyToStringAnyMap(normalizedOutput.envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
      ?.toMutableMap()
      ?: return normalizedOutput
    val validationResult = JsonCodec.anyToStringAnyMap(
      produced[ValidationEvidencePayloadKeys.VALIDATION_RESULT],
    )
      ?.toMutableMap()
      ?: return normalizedOutput
    validationResult["gate_run_count"] = 0
    validationResult["gate_runs"] = emptyList<Any?>()
    validationResult.remove("suppression_justifications")
    produced[ValidationEvidencePayloadKeys.VALIDATION_RESULT] = validationResult
    val envelope = normalizedOutput.envelope.toMutableMap()
    envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
    return runLoop.outputValidator.validatePhaseOutput(
      JsonCodec.mapToJsonString(envelope),
      sourceLabel = run.phaseId,
    ).requireAcceptedOutput(run.phaseId).normalizedOutput
  }

  internal fun implementationObligations(run: PhaseRun): FeatureTaskRuntimeImplementationObligations =
    FeatureTaskRuntimeImplementationObligations(
      plannedTaskIds = emptyList(),
      carriedRepairItemIds = emptyList(),
      loopId = run.reentry?.loopId,
      edgeIteration = run.reentry?.edgeIteration,
    )

  internal fun implementationContinuationFor(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): FeatureTaskRuntimeImplementationContinuation? {
    if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return null
    val attempts = runLoop.recorder.loadImplementationAttempts(run.request.workflowId)
      ?: return null
    return featureTaskRuntimeImplementationContinuationFrom(run.phaseId, attempts, implementationObligations(run))
      ?.takeIf { it.priorValueSegments.isNotEmpty() }
  }

  internal fun completionProjectionRejection(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: CompletionProjectionRejectionArgs,
  ): Pair<String, String>? = producerProjectionGateReason(
    args.run.phaseId,
    args.outputMap,
    runLoop.planningProjectionValidator,
  )?.let { "producer-projection" to it }
    ?: immediateConsumerProjectionGateReason(
      runLoop,
      ImmediateConsumerProjectionGateArgs(
        run = args.run,
        iteration = args.iteration,
        normalizedOutput = args.normalizedOutput,
        repairEvidence = args.repairEvidence,
        repositoryFingerprint = args.repositoryFingerprint,
      ),
    )?.let { "consumer-projection" to it }
    ?: FeatureTaskRuntimeRunLoopOutputVerification.outputVerificationGateReason(
      runLoop,
      args.run,
      args.outputMap,
    )?.let { "output-verification" to it }

  fun firstValidatedOutputRejection(phaseId: String, outputMap: Map<String, Any?>): Pair<String, String>? =
    mutatingReconciliationGateReason(
      phaseId,
      outputMap,
    )?.let { "mutating-reconciliation" to it }

  internal fun immediateConsumerProjectionGateReason(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ImmediateConsumerProjectionGateArgs,
  ): String? {
    val run = args.run
    val iteration = args.iteration
    val normalizedOutput = args.normalizedOutput
    val repairEvidence = args.repairEvidence
    val repositoryFingerprint = args.repositoryFingerprint
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) return null
    if (run.validationGateFindings != null) return null
    val producerIndex = runLoop.transitions.forwardPhaseIds.indexOf(run.phaseId)
    if (producerIndex < 0 || producerIndex == runLoop.transitions.forwardPhaseIds.lastIndex) return null
    val consumerPhaseId = runLoop.transitions.forwardPhaseIds[producerIndex + 1]
    val declaration = phaseDeclaration(
      consumerPhaseId,
      run.request.runInvariants.featureSize,
      FeatureTaskRuntimeRunLoopTransitions.qualityGateSelection(runLoop),
    )
    val currentOutput = FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload = normalizedOutput.canonicalJson,
      normalizedOutput = normalizedOutput,
      repairEvidence = repairEvidence,
    )
    val outputs = runLoop.state.outputs().filterNot { it.phaseId == run.phaseId } + currentOutput
    val resolvedFingerprint = repositoryFingerprint?.takeIf(String::isNotBlank)
      ?: runLoop.gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)
    val checkpoint = resolvedFingerprint
      ?.let(::FeatureTaskRuntimeRepositoryCheckpoint)
    val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
      FeatureTaskRuntimeHandoffAssemblyRequest(
        declaration = declaration,
        runInvariants = run.request.runInvariants,
        recordedOutputs = outputs,
        repositoryCheckpoint = checkpoint,
        expectedRepositoryCheckpoint = checkpoint,
        branchIdentity = runLoop.session.resolvedBranch,
        baseBranch = runLoop.recorder.loadResolvedBranch(run.request.workflowId)
          ?.baseBranch
          ?: "main",
      ),
    )
    return try {
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(
        handoff,
        run.request.workflowId,
        runLoop.planningProjectionValidator,
        run.request.agentAddonSelection,
      )
      null
    } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
      "Phase '${run.phaseId}' reported 'completed' but its output cannot satisfy immediate consumer " +
        "'$consumerPhaseId': ${boundedSchemaGateDetail(error.message.orEmpty())}"
    } catch (error: InvalidFeatureTaskRuntimePhaseBriefingFramingError) {
      "Phase '${run.phaseId}' reported 'completed' but its output cannot frame immediate consumer " +
        "'$consumerPhaseId': ${boundedSchemaGateDetail(error.message.orEmpty())}"
    }
  }

  internal fun recordedFindingVerdictsForFixHandoff(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): List<ReviewFindingVerdict> {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX) {
      return emptyList()
    }
    val review = state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) ?: return emptyList()
    val envelope = review.normalizedOutput?.envelope
      ?: JsonCodec.parseObjectOrNull(review.payload)
        ?.let { JsonCodec.jsonElementToValue(it) }
        ?.let(JsonCodec::anyToStringAnyMap)
      ?: return emptyList()
    return runLoop.recorder.recordedFindingVerdicts(envelope)
  }

  internal fun resolveSharedReviewEvidence(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
  ): FeatureTaskRuntimeSharedReviewEvidenceResolved? {
    val declared = run.declaration.projectionDeclarations.any {
      it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
    }
    if (!declared) return null
    return FeatureTaskRuntimeSharedReviewEvidenceResolver(
      runLoop.phaseGates.sharedEvidenceResolver,
      runLoop.phaseGates.diffResolver,
    ).resolve(run.request.repoRoot, run.request.workflowId, checkpoint, run.phaseId)
  }

  internal fun resolveRepositoryCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): FeatureTaskRuntimeRepositoryCheckpoint? = if (run.declaration.projectionDeclarations.none { projection ->
      projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
    }
  ) {
    null
  } else {
    FeatureTaskRuntimeRunLoopOutputVerification.buildRepositoryCheckpoint(runLoop, run)
  }

  internal fun completedPhaseRepositoryFingerprint(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun) = if (
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ||
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
  ) {
    runLoop.gitOperations.repositoryFingerprint(run.request.repoRoot)
  } else {
    null
  }

  internal fun auditGapProgressPause(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
    repositoryFingerprint: String?,
    auditOutputArtifact: String,
  ): FeatureTaskRuntimeAuditGapPause? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return null
    if (auditOutputArtifact.isBlank()) return null
    val verdict = FeatureTaskRuntimeOutputVerification.verdictFor(run.phaseId, outputMap)
    val currentHasGaps = verdict == FeatureTaskRuntimeVerdict.GAPS_FOUND
    val currentCriterionRefs = auditCriterionRefs(auditOutputArtifact)
    val previous = runLoop.recorder.loadAuditGapProgress(runLoop.request.workflowId)
    val decision = if (previous == null || !currentHasGaps) {
      FeatureTaskRuntimeAuditRepairProgressDecision(blocked = false, reason = null)
    } else {
      val previousCriterionRefs = previous.criterionRefs - FeatureTaskRuntimeAuditGapProgress.HAD_GAPS_MARKER
      detectAuditRepairNonProgress(
        previous = FeatureTaskRuntimeAuditRepairSnapshot(
          hasGaps = previous.criterionRefs.isNotEmpty(),
          repositoryFingerprint = previous.repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT,
          criterionRefs = previousCriterionRefs,
        ),
        current = FeatureTaskRuntimeAuditRepairSnapshot(
          hasGaps = true,
          repositoryFingerprint = repositoryFingerprint ?: UNPROVEN_REPOSITORY_FINGERPRINT,
          criterionRefs = currentCriterionRefs.ifEmpty { previousCriterionRefs },
        ),
      )
    }
    if (currentHasGaps) {
      runLoop.recorder.persistAuditGapProgress(
        runLoop.request.workflowId,
        FeatureTaskRuntimeAuditGapProgress(
          criterionRefs = currentCriterionRefs.ifEmpty {
            setOf(FeatureTaskRuntimeAuditGapProgress.HAD_GAPS_MARKER)
          },
          repositoryFingerprint = repositoryFingerprint,
        ),
      )
    } else {
      runLoop.recorder.loadAuditGapPause(runLoop.request.workflowId)?.let { pause ->
        if (!pause.grantConsumed || pause.operatorDecision != null) {
          FeatureTaskRuntimeRunLoopDrive.consumeAuditGapRetryGrant(runLoop, pause)
        }
      }
    }
    if (!decision.blocked) {
      decision.reason?.let { runLoop.diagnostics.warning(it) }
      return null
    }
    return FeatureTaskRuntimeAuditGapPause(
      pauseKind = FeatureTaskRuntimeAuditGapPauseKind.NO_PROGRESS,
      reason = noProgressPauseReason(requireNotNull(decision.reason)),
      edgeIteration = runLoop.state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID) + 1,
    )
  }

  fun noProgressPauseReason(decisionReason: String): String =
    "$decisionReason The subtask is paused for an operator decision: choose retry_fix to allow one " +
      "further remediation attempt, or abandon_subtask to end the subtask."

  internal fun terminalOutputAttempt(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: TerminalOutputAttemptArgs,
  ): AttemptResult {
    val run = args.run
    val iteration = args.iteration
    val reason = args.reason
    val outputMap = args.outputMap
    val normalizedOutput = args.normalizedOutput
    val repairEvidence = args.repairEvidence
    val observability = args.observability
    val fileManifest = args.fileManifest
    val disposition = FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(run.phaseId, outputMap)
    val operatorTerminalQualityGate =
      !disposition.retryOnResume &&
        (
          run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
            run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
          )
    if (operatorTerminalQualityGate) {
      return AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
          runLoop,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = reason,
            observability = runLoop.observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = disposition,
          ),
        ),
      )
    }
    return if (
      disposition.retryOnResume &&
      FeatureTaskRuntimePhaseWorkflowDefinition.retriesOnInvalidOutput(run.phaseId)
    ) {
      AttemptResult.retryableTerminal(reason, fileManifest, disposition)
    } else {
      AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
          runLoop,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = reason,
            observability = runLoop.observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = disposition,
          ),
        ),
      )
    }
  }

  private fun auditCriterionRefs(auditOutputArtifact: String): Set<String> {
    val envelope = JsonCodec.parseObjectOrNull(auditOutputArtifact)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
    val produced = envelope?.let { JsonCodec.anyToStringAnyMap(it[SharedPayloadKeys.PRODUCED_OUTPUTS]) }
    val value = when (val raw = produced?.get(SharedPayloadKeys.VALUE)) {
      is String -> JsonCodec.parseObjectOrNull(raw)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
      is Map<*, *> -> JsonCodec.anyToStringAnyMap(raw)
      else -> null
    }
    return (value?.get("gaps") as? List<*>).orEmpty()
      .mapNotNull { JsonCodec.anyToStringAnyMap(it)?.get("criterion") as? String }
      .map(String::trim)
      .filter(String::isNotEmpty)
      .toSet()
  }

  internal fun outputVerificationGateReason(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): String? = findingVerificationBoundaryDispositionGate(runLoop, run, outputMap)
    ?: FeatureTaskRuntimeVerificationGateReasons.reviewVerificationSignal(run.phaseId, outputMap)
    ?: FeatureTaskRuntimeVerificationGateReasons.findingVerificationDisposition(
      run.phaseId,
      outputMap,
      FeatureTaskRuntimeRunLoopOutputVerification.reviewFindingIdsForVerification(runLoop),
    )

  internal fun findingVerificationBoundarySections(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): List<FeatureTaskRuntimeFindingBoundaryMemorySection> {
    val reviewOutput = runLoop.state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.normalizedOutput?.envelope
    val recordedVerdicts = reviewOutput?.let {
      runLoop.recorder.recordedFindingVerdicts(
        it,
      )
    }.orEmpty()
    val findings = reviewOutput?.let {
      GoalSubtaskReviewStructuredFindingsParse.structuredFindings(it, recordedVerdicts)
    }.orEmpty()
    return runLoop.phaseGates.findingVerificationBoundaryMemory.sectionsForFindings(
      run.request.repoRoot,
      findings.mapNotNull { finding ->
        val findingId = finding.findingId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        FeatureTaskRuntimeFindingBoundaryMemoryRequest(
          findingId = findingId,
          findingPaths = FeatureTaskRuntimeRunLoopLaunch.findingPathsForBoundaryMemory(finding),
        )
      },
    )
  }

  internal fun findingVerificationBoundaryBodyDeliveryDecision(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): BoundaryBodyDeliveryDecision {
    FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsBoundaryContext(
      runLoop,
      run,
      outputMap,
    )?.let { return it }
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    val sections = findingVerificationBoundarySections(runLoop, run)
    FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsBoundaryValidationFailure(
      runLoop,
      sections,
      dispositions,
    )?.let { return it }
    val selections = runLoop.phaseGates.findingVerificationBoundaryMemory.selectionsRequiringBodyDelivery(
      sections,
      dispositions,
    )
    val delivered = if (selections.isEmpty()) {
      true
    } else {
      runLoop.recorder.loadFindingVerificationBoundarySelection(
        run.request.workflowId,
      ) != null
    }
    if (delivered) return BoundaryBodyDeliveryDecision.NotApplicable
    runLoop.recorder.persistFindingVerificationBoundarySelection(
      workflowId = run.request.workflowId,
      selections = selections,
    )
    runLoop.recorder.persistFindingVerificationCheckpoint(
      workflowId = run.request.workflowId,
      dispositions = dispositions,
    )
    return BoundaryBodyDeliveryDecision.ContinueDecision.of(
      "Selected boundary headings recorded; re-read the briefing with resolved entry bodies and re-emit " +
        "finding_dispositions before verify_findings can settle.",
    )
  }

  internal fun findingVerificationBoundaryDispositionGate(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): String? = findingVerificationBoundaryDispositionGateImpl(runLoop, run, outputMap)

  internal fun findingVerificationBoundaryDispositionGateImpl(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): String? {
    val dispositions = FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsDispositionGateContext(
      runLoop,
      run,
      outputMap,
    ) ?: return null
    val sections = findingVerificationBoundarySections(runLoop, run)
    FeatureTaskRuntimeRunLoopOutputVerification.verifyFindingsDispositionGateValidationFailure(
      runLoop,
      sections,
      dispositions,
    )?.let { return it }
    val persisted = runLoop.recorder.loadFindingVerificationBoundarySelection(
      run.request.workflowId,
    )
    val memory = runLoop.phaseGates.findingVerificationBoundaryMemory
    memory.validateBoundarySelectionsDelivered(sections, dispositions, persisted)?.let { return it }
    return if (persisted != null) {
      memory.validateDispositionBoundaryBodies(
        repoRoot = run.request.repoRoot,
        sections = sections,
        dispositions = dispositions,
        persistedSelections = persisted,
      )
    } else {
      null
    }
  }

  internal fun persistVerifyFindingsCheckpointIfPresent(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputText: String,
  ) {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return
    val outputMap = JsonCodec.parseObjectOrNull(outputText)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?: return
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return
    runLoop.recorder.persistFindingVerificationCheckpoint(
      workflowId = run.request.workflowId,
      dispositions = dispositions,
    )
  }

  fun reviewFindingIdsForVerification(runLoop: FeatureTaskRuntimeRunLoop): Set<String> {
    val reviewOutput = runLoop.state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
      ?.normalizedOutput?.envelope
      ?: return emptySet()
    val recordedVerdicts = runLoop.recorder.recordedFindingVerdicts(reviewOutput)
    return GoalSubtaskReviewStructuredFindingsParse.structuredFindings(reviewOutput, recordedVerdicts)
      .mapNotNull { it.findingId }
      .toSet()
  }

  fun structuralRepairEvidenceFromSchemaError(
    error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
  ): FeatureTaskRuntimePhaseOutputRepairEvidence? {
    val originalDigest = error.structuralRepairOriginalDigest
    val repairedDigest = error.structuralRepairRepairedDigest
    val format = error.structuralRepairFormat
    val operation = error.structuralRepairOperation
    val sourceLabel = error.structuralRepairSourceLabel
    val sourceOffset = error.structuralRepairSourceOffset
    val sourceLine = error.structuralRepairSourceLine
    val sourceColumn = error.structuralRepairSourceColumn
    if (
      listOf(
        originalDigest,
        repairedDigest,
        format,
        operation,
        sourceLabel,
        sourceOffset,
        sourceLine,
        sourceColumn,
      ).any { it == null }
    ) {
      return null
    }
    return FeatureTaskRuntimePhaseOutputRepairEvidence(
      format = FeatureTaskRuntimePhaseOutputFormat.fromWire(
        requireNotNull(format),
      ),
      originalDigest = requireNotNull(originalDigest),
      repairedDigest = requireNotNull(repairedDigest),
      operation = FeatureTaskRuntimePhaseOutputRepairOperation.fromWire(
        requireNotNull(operation),
      ),
      sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation(
        sourceLabel = requireNotNull(sourceLabel),
        offset = requireNotNull(sourceOffset),
        line = requireNotNull(sourceLine),
        column = requireNotNull(sourceColumn),
      ),
    )
  }

  internal fun persistAcceptedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PersistAcceptedOutputArgs,
  ): AttemptResult {
    val run = args.run
    val iteration = args.iteration
    val normalizedOutput = args.normalizedOutput
    val repairEvidence = args.repairEvidence
    val observability = args.observability
    val fileManifest = args.fileManifest
    val repositoryFingerprint = args.repositoryFingerprint
    val outputText = normalizedOutput.canonicalJson
    if (run.validationGateFindings != null) {
      return FeatureTaskRuntimeRunLoopOutputVerification.validationGatePersistedAttempt(
        run,
        iteration,
        normalizedOutput,
        repairEvidence,
        outputText,
      )
    }
    val reviewArgs = PhaseReviewPersistenceArgs(run, iteration, runLoop.observability, fileManifest)
    if (FeatureTaskRuntimeRunLoopOutputPersistence.isGoalReviewRun(run)) {
      FeatureTaskRuntimeRunLoopOutputPersistence.persistGoalReviewCompletion(
        runLoop,
        reviewArgs,
        normalizedOutput,
        repairEvidence,
      )?.let { outcome ->
        return AttemptResult.settled(outcome)
      }
    } else {
      FeatureTaskRuntimeRunLoopOutputVerification.persistStandardAcceptedOutput(
        runLoop,
        PersistStandardAcceptedOutputArgs(
          accepted = PersistAcceptedOutputArgs(
            run = run,
            iteration = iteration,
            normalizedOutput = normalizedOutput,
            repairEvidence = repairEvidence,
            observability = runLoop.observability,
            fileManifest = fileManifest,
            repositoryFingerprint = repositoryFingerprint,
          ),
          outputText = outputText,
        ),
      )?.let { return it }
    }
    runLoop.observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return FeatureTaskRuntimeRunLoopOutputVerification.completedAttemptResult(
      run,
      iteration,
      outputText,
      normalizedOutput,
      repairEvidence,
    )
  }

  internal fun buildRepositoryCheckpoint(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): FeatureTaskRuntimeRepositoryCheckpoint? {
    val resolvedBranchRecord = runLoop.recorder.loadResolvedBranch(run.request.workflowId)
    runLoop.session.resolvedBranch = resolvedBranchRecord?.branch
    val goalReviewState = runLoop.goalContinuationRecorder.reviewState(
      run.request.workflowId,
    )
    val revisions = FeatureTaskRuntimeRunLoopOutputVerification.resolveCheckpointRevisions(
      runLoop,
      run = run,
      headRevision = resolvedBranchRecord?.branch?.takeIf(String::isNotBlank) ?: "HEAD",
      baseRevision = goalReviewState?.reviewBaseSha ?: resolvedBranchRecord?.reviewBaseSha,
    ) ?: return null
    val ownedPaths = resolveCheckpointOwnedPaths(
      runLoop,
      run = run,
      persistedOwnedPaths = resolvedBranchRecord?.workflowOwnedPaths,
      baselineOwnedPaths = resolvedBranchRecord?.baselineOwnedPaths
        ?: goalReviewState?.baselineUntrackedPaths
        ?: resolvedBranchRecord?.baselineUntrackedPaths.orEmpty(),
      revisions = revisions,
    ) ?: return null
    val fingerprint = runLoop.gitOperations.repositoryCheckpointFingerprint(
      run.request.repoRoot,
      revisions.base,
      revisions.head,
      ownedPaths,
    ).takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank) ?: return null
    return FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = fingerprint,
      baseRef = revisions.base,
      headRef = revisions.head,
      workingTreeOwnedPaths = ownedPaths,
    )
  }

  internal fun resolveCheckpointOwnedPaths(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    persistedOwnedPaths: List<String>?,
    baselineOwnedPaths: List<String>,
    revisions: CheckpointRevisions,
  ): List<String>? {
    val workingTreePaths = FeatureTaskRuntimeRunLoopOutputVerification.checkpointOwnedPaths(
      runLoop,
      run,
      baselineOwnedPaths,
    ) ?: return null
    val committedPaths = revisions.base?.let { base ->
      runLoop.gitOperations.runtimePhaseChangedPathsBetweenCommits(run.request.repoRoot, base, revisions.head)
        .takeIf { it is WorkflowGitOperationResult.Ok }
        ?.value
        ?.let(FeatureTaskRuntimePhaseSafetyPolicy::lineSeparatedPaths)
        ?: return null
    }.orEmpty()
    val durableInventory = persistedOwnedPaths.orEmpty().filter(String::isNotBlank)
    val discovered = if (runLoop.session.checkpointOwnershipDecided && durableInventory.isNotEmpty()) {
      durableInventory
    } else {
      (durableInventory + workingTreePaths).distinct()
    }
    val inventory = reconcileCheckpointPathInventory(
      repoRoot = run.request.repoRoot,
      issueKey = run.request.issueKey,
      specReference = run.request.runInvariants.specReference,
      paths = (discovered + committedPaths).distinct(),
    ).sorted()
    return inventory.takeIf {
      runLoop.recorder.recordWorkflowOwnedPaths(
        run.request.workflowId,
        inventory,
      )
    }
  }

  internal fun resolveCheckpointRevisions(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    headRevision: String,
    baseRevision: String?,
  ): CheckpointRevisions? {
    val immutableHead = runLoop.gitOperations.resolveCommit(run.request.repoRoot, headRevision)
      .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank)
      ?: runLoop.gitOperations.headCommitSha(run.request.repoRoot)
        .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank)
      ?: return null
    val immutableBase = baseRevision?.let { revision ->
      runLoop.gitOperations.resolveCommit(run.request.repoRoot, revision)
        .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.takeIf(String::isNotBlank)
        ?: revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) }
    }
    if (baseRevision != null && immutableBase == null) return null
    return CheckpointRevisions(base = immutableBase, head = immutableHead)
  }

  internal fun checkpointOwnedPaths(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    baselineOwnedPaths: List<String>,
  ): List<String>? {
    val owned = runLoop.gitOperations.repositoryOwnedPaths(run.request.repoRoot)
    if (owned !is WorkflowGitOperationResult.Ok) return null
    val baseline = baselineOwnedPaths.toSet()
    val paths = owned.value.orEmpty()
      .split(OWNED_PATH_DELIMITER)
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot { it in baseline }
      .filterNot { path -> isFeatureSpecPathForIssue(path, run.request.issueKey) }
      .distinct()
      .sorted()
    if (paths.size > MAX_CHECKPOINT_OWNED_PATHS) {
      val declaration = run.declaration.projectionDeclarations.first { projection ->
        projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
      }
      throw InvalidFeatureTaskRuntimeHandoffProjectionError(
        context = InvalidFeatureTaskRuntimeHandoffProjectionContext(
          workflowId = run.request.workflowId,
          consumerPhaseId = run.phaseId,
          projectionName = declaration.projectionName,
          projectionContractId = declaration.projectionContractId,
          projectionContractVersion = declaration.projectionContractVersion,
          failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.BUDGET_OVERFLOW,
          reason = "the scoped owned-path inventory holds ${paths.size} entries, over the " +
            "$MAX_CHECKPOINT_OWNED_PATHS-entry checkpoint limit; narrow the run scope or commit " +
            "unrelated working-tree changes before relaunching",
        ),
      )
    }
    return paths
  }

  internal fun verifyFindingsBoundaryContext(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): BoundaryBodyDeliveryDecision? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) {
      return BoundaryBodyDeliveryDecision.NotApplicable
    }
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return BoundaryBodyDeliveryDecision.NotApplicable
    if (validateDispositionCoverage(
        dispositions,
        FeatureTaskRuntimeRunLoopOutputVerification.reviewFindingIdsForVerification(runLoop),
      ) != null
    ) {
      return BoundaryBodyDeliveryDecision.NotApplicable
    }
    return null
  }

  internal fun verifyFindingsBoundaryValidationFailure(
    runLoop: FeatureTaskRuntimeRunLoop,
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): BoundaryBodyDeliveryDecision? {
    val memory = runLoop.phaseGates.findingVerificationBoundaryMemory
    memory.validateDispositionBoundaryContext(sections, dispositions)?.let {
      return BoundaryBodyDeliveryDecision.RejectDecision.of(it)
    }
    memory.validateDispositionBoundaryProvenance(sections, dispositions)?.let {
      return BoundaryBodyDeliveryDecision.RejectDecision.of(it)
    }
    return null
  }

  internal fun verifyFindingsDispositionGateContext(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return null
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return null
    if (validateDispositionCoverage(
        dispositions,
        FeatureTaskRuntimeRunLoopOutputVerification.reviewFindingIdsForVerification(runLoop),
      ) != null
    ) {
      return null
    }
    return dispositions
  }

  fun verifyFindingsDispositionGateValidationFailure(
    runLoop: FeatureTaskRuntimeRunLoop,
    sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): String? {
    val memory = runLoop.phaseGates.findingVerificationBoundaryMemory
    memory.validateDispositionBoundaryContext(sections, dispositions)?.let { return it }
    memory.validateDispositionBoundaryProvenance(sections, dispositions)?.let { return it }
    return null
  }

  internal fun validationGatePersistedAttempt(
    run: PhaseRun,
    iteration: Int,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
    outputText: String,
  ): AttemptResult = AttemptResult.settled(
    PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        outputText,
        normalizedOutput,
        repairEvidence,
      ),
    ),
  )

  internal fun persistStandardAcceptedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PersistStandardAcceptedOutputArgs,
  ): AttemptResult? {
    val accepted = args.accepted
    val run = accepted.run
    val iteration = accepted.iteration
    val normalizedOutput = accepted.normalizedOutput
    val repairEvidence = accepted.repairEvidence
    val observability = accepted.observability
    val fileManifest = accepted.fileManifest
    val repositoryFingerprint = accepted.repositoryFingerprint
    val outputText = args.outputText
    if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) {
      FeatureTaskRuntimeRunLoopOutputPersistence.persistRejectedVerificationFindings(
        runLoop,
        run,
        normalizedOutput.envelope,
      )
    }
    val persisted = runLoop.recorder.recordCompletedPhase(
      FeatureTaskRuntimeRunLoopOutputPersistence.phaseStateRequest(
        runLoop,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(
            run = run,
            iteration = iteration,
            status = STATUS_COMPLETED,
            finished = true,
            outputArtifact = outputText,
          ),
          extras = PhaseStateRequestAttachments(
            fileManifest = fileManifest,
            normalizedOutput = normalizedOutput,
            repairEvidence = repairEvidence,
            repositoryFingerprint = repositoryFingerprint,
          ),
        ),
      ),
    )
    if (!persisted) {
      return AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
          runLoop,
          PhaseBlockRequest(
            run = run,
            attemptCount = iteration,
            reason = "Validated phase output could not be persisted to the authoritative workflow record.",
            observability = runLoop.observability,
            payload = BlockAndPersistPayload(fileManifest = fileManifest),
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          ),
        ),
      )
    }
    return null
  }

  internal fun completedAttemptResult(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): AttemptResult = AttemptResult.settled(
    PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        outputText,
        normalizedOutput,
        repairEvidence,
      ),
    ),
  )
}
