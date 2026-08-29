package skillbill.application.featuretask

import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.structuredFindings
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation

internal fun FeatureTaskRuntimeRunLoop.findingVerificationBoundaryBodyDeliveryDecision(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
): BoundaryBodyDeliveryDecision {
  verifyFindingsBoundaryContext(run, outputMap)?.let { return it }
  val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
  val sections = findingVerificationBoundarySections(run)
  verifyFindingsBoundaryValidationFailure(sections, dispositions)?.let { return it }
  val selections = phaseGates.findingVerificationBoundaryMemory.selectionsRequiringBodyDelivery(sections, dispositions)
  val delivered = if (selections.isEmpty()) {
    true
  } else {
    recorder.loadFindingVerificationBoundarySelection(
      run.request.workflowId,
      run.request.dbPathOverride,
    ) != null
  }
  if (delivered) return BoundaryBodyDeliveryDecision.NotApplicable
  recorder.persistFindingVerificationBoundarySelection(
    workflowId = run.request.workflowId,
    selections = selections,
    dbOverride = run.request.dbPathOverride,
  )
  recorder.persistFindingVerificationCheckpoint(
    workflowId = run.request.workflowId,
    dispositions = dispositions,
    dbOverride = run.request.dbPathOverride,
  )
  return BoundaryBodyDeliveryDecision.ContinueDecision.of(
    "Selected boundary headings recorded; re-read the briefing with resolved entry bodies and re-emit " +
      "finding_dispositions before verify_findings can settle.",
  )
}

internal fun FeatureTaskRuntimeRunLoop.findingVerificationBoundaryDispositionGate(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
): String? = findingVerificationBoundaryDispositionGateImpl(run, outputMap)

internal fun FeatureTaskRuntimeRunLoop.findingVerificationBoundaryDispositionGateImpl(
  run: PhaseRun,
  outputMap: Map<String, Any?>,
): String? {
  val dispositions = verifyFindingsDispositionGateContext(run, outputMap) ?: return null
  val sections = findingVerificationBoundarySections(run)
  verifyFindingsDispositionGateValidationFailure(sections, dispositions)?.let { return it }
  val persisted = recorder.loadFindingVerificationBoundarySelection(
    run.request.workflowId,
    run.request.dbPathOverride,
  )
  val memory = phaseGates.findingVerificationBoundaryMemory
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

internal fun FeatureTaskRuntimeRunLoop.persistVerifyFindingsCheckpointIfPresent(run: PhaseRun, outputText: String) {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return
  val outputMap = JsonSupport.parseObjectOrNull(outputText)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: return
  val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
  if (dispositions.isEmpty()) return
  recorder.persistFindingVerificationCheckpoint(
    workflowId = run.request.workflowId,
    dispositions = dispositions,
    dbOverride = run.request.dbPathOverride,
  )
}

internal fun FeatureTaskRuntimeRunLoop.reviewFindingIdsForVerification(): Set<String> {
  val reviewOutput = state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    ?.normalizedOutput?.envelope
    ?: return emptySet()
  val recordedVerdicts = recorder.recordedFindingVerdicts(reviewOutput, request.dbPathOverride)
  return GoalSubtaskReviewSummaryReducer.structuredFindings(reviewOutput, recordedVerdicts)
    .mapNotNull { it.findingId }
    .toSet()
}

/**
 * Rebuilds payload-free structural-repair evidence from digest/location fields carried on the
 * schema exception. Returns null when the throw had no correlated prior syntax repair.
 */
internal fun FeatureTaskRuntimeRunLoop.structuralRepairEvidenceFromSchemaError(
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

internal fun FeatureTaskRuntimeRunLoop.persistAcceptedOutput(args: PersistAcceptedOutputArgs): AttemptResult {
  val run = args.run
  val iteration = args.iteration
  val normalizedOutput = args.normalizedOutput
  val repairEvidence = args.repairEvidence
  val observability = args.observability
  val fileManifest = args.fileManifest
  val repositoryFingerprint = args.repositoryFingerprint
  val outputText = normalizedOutput.canonicalJson
  if (run.validationGateFindings != null) {
    return validationGatePersistedAttempt(run, iteration, normalizedOutput, repairEvidence, outputText)
  }
  val reviewArgs = PhaseReviewPersistenceArgs(run, iteration, observability, fileManifest)
  if (isGoalReviewRun(run)) {
    persistGoalReviewCompletion(
      reviewArgs,
      normalizedOutput,
      repairEvidence,
    )?.let { outcome ->
      return AttemptResult.settled(outcome)
    }
  } else {
    persistStandardAcceptedOutput(
      PersistStandardAcceptedOutputArgs(
        accepted = PersistAcceptedOutputArgs(
          run = run,
          iteration = iteration,
          normalizedOutput = normalizedOutput,
          repairEvidence = repairEvidence,
          observability = observability,
          fileManifest = fileManifest,
          repositoryFingerprint = repositoryFingerprint,
        ),
        outputText = outputText,
      ),
    )?.let { return it }
  }
  observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
  return completedAttemptResult(run, iteration, outputText, normalizedOutput, repairEvidence)
}
