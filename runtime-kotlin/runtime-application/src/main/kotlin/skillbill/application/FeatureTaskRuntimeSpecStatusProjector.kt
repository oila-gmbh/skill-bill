package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeSpecStatusProjector(
  private val fileStore: DecompositionManifestFileStore,
) {
  fun projectCompleteBeforeCommitPhase(phaseId: String, request: FeatureTaskRuntimeRunRequest) {
    if (!shouldProjectSpecStatus(phaseId, request)) return
    val target = resolvedParentSpecPath(request.repoRoot, Path.of(request.runInvariants.specReference))
    projectDecompositionSpecStatus(target, STATUS_COMPLETE, fileStore)
  }

  private fun shouldProjectSpecStatus(phaseId: String, request: FeatureTaskRuntimeRunRequest): Boolean =
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH && request.goalContinuation == null

  private companion object {
    const val STATUS_COMPLETE = "complete"
  }
}
