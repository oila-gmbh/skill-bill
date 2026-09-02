package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.validateDispositionCoverage

@Inject
class FeatureTaskRuntimeRunLoopOutputVerificationContinued4 {
  internal fun resolveCheckpointRevisions(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    headRevision: String,
    baseRevision: String?,
  ): CheckpointRevisions? {
    val immutableHead = runLoop.gitOperations.resolveCommit(run.request.repoRoot, headRevision)
      .takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
      ?: runLoop.gitOperations.headCommitSha(run.request.repoRoot).takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
      ?: return null
    val immutableBase = baseRevision?.let { revision ->
      runLoop.gitOperations.resolveCommit(run.request.repoRoot, revision)
        .takeIf { it.ok }?.value?.takeIf(String::isNotBlank)
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
    if (!owned.ok) return null
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
        runLoop.collaborators.outputVerificationContinued3.reviewFindingIdsForVerification(runLoop),
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
        runLoop.collaborators.outputVerificationContinued3.reviewFindingIdsForVerification(runLoop),
      ) != null
    ) {
      return null
    }
    return dispositions
  }
}
