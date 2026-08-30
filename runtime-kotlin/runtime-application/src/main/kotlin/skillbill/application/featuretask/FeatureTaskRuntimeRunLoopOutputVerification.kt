package skillbill.application.featuretask

import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.workflow.repoRoot
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunLoop.attestAbsentGateValidationReceipt(
  run: PhaseRun,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
): NormalizedFeatureTaskRuntimePhaseOutput {
  val eligible = run.agentRunValidateFallback &&
    run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE &&
    normalizedOutput.envelope["status"] == STATUS_COMPLETED
  if (!eligible) return normalizedOutput
  val produced = JsonSupport.anyToStringAnyMap(normalizedOutput.envelope["produced_outputs"])
    ?.toMutableMap()
    ?: return normalizedOutput
  val validationResult = JsonSupport.anyToStringAnyMap(produced["validation_result"])
    ?.toMutableMap()
    ?: return normalizedOutput
  validationResult["gate_run_count"] = 0
  validationResult["gate_runs"] = emptyList<Any?>()
  validationResult.remove("suppression_justifications")
  produced["validation_result"] = validationResult
  val envelope = normalizedOutput.envelope.toMutableMap()
  envelope["produced_outputs"] = produced
  return outputValidator.validatePhaseOutput(
    JsonSupport.mapToJsonString(envelope),
    sourceLabel = run.phaseId,
  ).requireAcceptedOutput(run.phaseId).normalizedOutput
}

internal fun FeatureTaskRuntimeRunLoop.implementationObligations(
  run: PhaseRun,
): FeatureTaskRuntimeImplementationObligations = FeatureTaskRuntimeImplementationObligations(
  plannedTaskIds = emptyList(),
  carriedRepairItemIds = emptyList(),
  loopId = run.reentry?.loopId,
  edgeIteration = run.reentry?.edgeIteration,
)

internal fun FeatureTaskRuntimeRunLoop.implementationContinuationFor(
  run: PhaseRun,
): FeatureTaskRuntimeImplementationContinuation? {
  if (!FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(run.phaseId)) return null
  val attempts = recorder.loadImplementationAttempts(run.request.workflowId, run.request.dbPathOverride)
    ?: return null
  return featureTaskRuntimeImplementationContinuationFrom(run.phaseId, attempts, implementationObligations(run))
    ?.takeIf { it.priorValueSegments.isNotEmpty() }
}

/**
 * The structural contract a phase claiming completion owes its consumer, as the first failing rule.
 *
 * Grouped so the settle function reads as one structural-gate step: these three share a disposition
 * (all route through the SKILL-153 reject path and its bounded cap) and an ordering constraint (all
 * run before the semantic incompleteness gate, so a repairable contract defect is named to the agent
 * rather than burning continuation segments).
 */
internal fun FeatureTaskRuntimeRunLoop.completionProjectionRejection(
  args: CompletionProjectionRejectionArgs,
): Pair<String, String>? = producerProjectionGateReason(
  args.run.phaseId,
  args.outputMap,
  planningProjectionValidator,
)?.let { "producer-projection" to it }
  ?: immediateConsumerProjectionGateReason(
    args.run,
    args.iteration,
    args.normalizedOutput,
    args.repairEvidence,
    args.repositoryFingerprint,
  )?.let { "consumer-projection" to it }
  ?: outputVerificationGateReason(args.run, args.outputMap)?.let { "output-verification" to it }

internal fun FeatureTaskRuntimeRunLoop.firstValidatedOutputRejection(
  phaseId: String,
  outputMap: Map<String, Any?>,
): Pair<String, String>? = mutatingReconciliationGateReason(phaseId, outputMap)?.let { "mutating-reconciliation" to it }

/**
 * A completed producer must satisfy the exact projection its immediate forward consumer will parse.
 * This shares the launch assembler and validator instead of restating receipt shapes. Rejecting here
 * keeps malformed finalization receipts in the producer's bounded correction loop.
 */
internal fun FeatureTaskRuntimeRunLoop.immediateConsumerProjectionGateReason(
  run: PhaseRun,
  iteration: Int,
  normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  repositoryFingerprint: String?,
): String? {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) return null
  // Gate-repair segments are not the validate→write_history handoff. They must not invent
  // gate_run_count/gate_runs; the coordinator re-runs the gate and settleRuntimeOwnedValidation
  // publishes the measured receipt. Matching persistAcceptedOutput's skip for the same flag.
  if (run.validationGateFindings != null) return null
  val producerIndex = transitions.forwardPhaseIds.indexOf(run.phaseId)
  if (producerIndex < 0 || producerIndex == transitions.forwardPhaseIds.lastIndex) return null
  val consumerPhaseId = transitions.forwardPhaseIds[producerIndex + 1]
  val declaration = phaseDeclaration(consumerPhaseId, run.request.runInvariants.featureSize, qualityGateSelection())
  val currentOutput = FeatureTaskRuntimePhaseOutput(
    phaseId = run.phaseId,
    iteration = iteration,
    payload = normalizedOutput.canonicalJson,
    normalizedOutput = normalizedOutput,
    repairEvidence = repairEvidence,
  )
  val outputs = state.outputs().filterNot { it.phaseId == run.phaseId } + currentOutput
  val resolvedFingerprint = repositoryFingerprint?.takeIf(String::isNotBlank)
    ?: gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)
  val checkpoint = resolvedFingerprint
    ?.let(::FeatureTaskRuntimeRepositoryCheckpoint)
  val handoff = FeatureTaskRuntimeHandoffContract.assembleHandoff(
    FeatureTaskRuntimeHandoffAssemblyRequest(
      declaration = declaration,
      runInvariants = run.request.runInvariants,
      recordedOutputs = outputs,
      repositoryCheckpoint = checkpoint,
      expectedRepositoryCheckpoint = checkpoint,
      branchIdentity = resolvedBranch,
      baseBranch = recorder.loadResolvedBranch(run.request.workflowId, run.request.dbPathOverride)
        ?.baseBranch
        ?: "main",
    ),
  )
  return try {
    FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      handoff,
      run.request.workflowId,
      planningProjectionValidator,
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

internal fun FeatureTaskRuntimeRunLoop.recordedFindingVerdictsForFixHandoff(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
): List<ReviewFindingVerdict> {
  if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX) {
    return emptyList()
  }
  val review = state.outputFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) ?: return emptyList()
  val envelope = review.normalizedOutput?.envelope
    ?: JsonSupport.parseObjectOrNull(review.payload)
      ?.let { JsonSupport.jsonElementToValue(it) }
      ?.let(JsonSupport::anyToStringAnyMap)
    ?: return emptyList()
  return recorder.recordedFindingVerdicts(envelope, request.dbPathOverride)
}

/**
 * The shared review evidence for this launch, or null when the phase declares none or nothing is
 * resolvable. Only the phases that declare the projection pay for the resolution.
 */
internal fun FeatureTaskRuntimeRunLoop.resolveSharedReviewEvidence(
  run: PhaseRun,
  checkpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
): FeatureTaskRuntimeSharedReviewEvidenceResolved? {
  val declared = run.declaration.projectionDeclarations.any {
    it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
  }
  if (!declared) return null
  return FeatureTaskRuntimeSharedReviewEvidenceResolver(
    phaseGates.sharedEvidenceResolver,
    phaseGates.diffResolver,
  ).resolve(run.request.repoRoot, run.request.workflowId, checkpoint, run.phaseId)
}

/**
 * Resolves a repository checkpoint only when some declaration actually needs one, reusing the same
 * `WorkflowGitOperations` fingerprint the audit-repair path already depends on. No new git port is
 * introduced and the domain stays git-agnostic: the checkpoint arrives as a plain value.
 */
internal fun FeatureTaskRuntimeRunLoop.resolveRepositoryCheckpoint(
  run: PhaseRun,
): FeatureTaskRuntimeRepositoryCheckpoint? = if (run.declaration.projectionDeclarations.none { projection ->
    projection.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
  }
) {
  null
} else {
  buildRepositoryCheckpoint(run)
}
