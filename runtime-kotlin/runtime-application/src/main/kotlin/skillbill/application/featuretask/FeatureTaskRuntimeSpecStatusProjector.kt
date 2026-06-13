package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.projectDecompositionSpecStatus
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.workflow.repoRoot
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeSpecStatusProjector(
  private val fileStore: DecompositionManifestFileStore,
) {
  fun projectCompleteBeforeCommitPhase(
    phaseId: String,
    request: FeatureTaskRuntimeRunRequest,
    specSource: SpecSource,
  ) {
    if (!shouldProjectSpecStatus(phaseId, request, specSource)) return
    val target = resolvedParentSpecPath(request.repoRoot, Path.of(request.runInvariants.specReference))
    projectDecompositionSpecStatus(target, STATUS_COMPLETE, fileStore)
  }

  // Local mode projects "complete" into the spec so the commit stages it with the feature work. In
  // linear mode the spec is excluded from the commit and deleted on success, so projecting a status
  // would only create a needless uncommitted scratch delta — no-op instead.
  private fun shouldProjectSpecStatus(
    phaseId: String,
    request: FeatureTaskRuntimeRunRequest,
    specSource: SpecSource,
  ): Boolean = phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH &&
    request.goalContinuation == null &&
    specSource == SpecSource.LOCAL

  private companion object {
    const val STATUS_COMPLETE = "complete"
  }
}
