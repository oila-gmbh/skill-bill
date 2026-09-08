package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.application.subtaskreview.GoalSubtaskReviewStructuredFindingsParse
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
class FeatureTaskRuntimeRunLoopOutputVerificationEnvelopeWalk {
  internal fun outputVerificationGateReason(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): String? = findingVerificationBoundaryDispositionGate(runLoop, run, outputMap)
    ?: FeatureTaskRuntimeVerificationGateReasons.reviewVerificationSignal(run.phaseId, outputMap)
    ?: FeatureTaskRuntimeVerificationGateReasons.findingVerificationDisposition(
      run.phaseId,
      outputMap,
      runLoop.collaborators.outputVerificationContinued3.reviewFindingIdsForVerification(runLoop),
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
        run.request.dbPathOverride,
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
          findingPaths = runLoop.collaborators.launch.findingPathsForBoundaryMemory(finding),
        )
      },
    )
  }

  // Every return is a distinct delivery decision — NotApplicable, Reject, or Continue — and the
  // sibling disposition gate suppresses this rule for the same reason.

  internal fun findingVerificationBoundaryBodyDeliveryDecision(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputMap: Map<String, Any?>,
  ): BoundaryBodyDeliveryDecision {
    runLoop.collaborators.outputVerificationContinued4.verifyFindingsBoundaryContext(
      runLoop,
      run,
      outputMap,
    )?.let { return it }
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    val sections = findingVerificationBoundarySections(runLoop, run)
    runLoop.collaborators.outputVerificationContinued4.verifyFindingsBoundaryValidationFailure(
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
        run.request.dbPathOverride,
      ) != null
    }
    if (delivered) return BoundaryBodyDeliveryDecision.NotApplicable
    runLoop.recorder.persistFindingVerificationBoundarySelection(
      workflowId = run.request.workflowId,
      selections = selections,
      dbOverride = run.request.dbPathOverride,
    )
    runLoop.recorder.persistFindingVerificationCheckpoint(
      workflowId = run.request.workflowId,
      dispositions = dispositions,
      dbOverride = run.request.dbPathOverride,
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
    val dispositions = runLoop.collaborators.outputVerificationContinued4.verifyFindingsDispositionGateContext(
      runLoop,
      run,
      outputMap,
    ) ?: return null
    val sections = findingVerificationBoundarySections(runLoop, run)
    runLoop.collaborators.outputVerificationContinued5.verifyFindingsDispositionGateValidationFailure(
      runLoop,
      sections,
      dispositions,
    )?.let { return it }
    val persisted = runLoop.recorder.loadFindingVerificationBoundarySelection(
      run.request.workflowId,
      run.request.dbPathOverride,
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
    val outputMap = JsonSupport.parseObjectOrNull(outputText)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return
    val dispositions = FeatureTaskRuntimeOutputVerification.dispositionsFrom(outputMap)
    if (dispositions.isEmpty()) return
    runLoop.recorder.persistFindingVerificationCheckpoint(
      workflowId = run.request.workflowId,
      dispositions = dispositions,
      dbOverride = run.request.dbPathOverride,
    )
  }
}
