package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.workflow.model.DecompositionManifestRuntimeUpdate
import skillbill.application.workflow.model.DecompositionManifestWorkflowProjectionInput
import skillbill.ports.db.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import java.nio.file.Path

internal fun WorkflowFamily.withDecompositionRuntime(
  existing: WorkflowStateSnapshot,
  input: WorkflowUpdateInput,
  workflowId: String,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestFileStore,
): DecompositionRuntimeInput = if (this != WorkflowFamily.TASK_RUNTIME) {
  DecompositionRuntimeInput(input = input, updated = false)
} else {
  DecompositionManifestWriter.manifestFromWorkflowUpdate(
    DecompositionManifestWorkflowProjectionInput(
      repoRoot = Path.of("").toAbsolutePath(),
      existingArtifactsJson = existing.artifactsJson,
      validator = validator,
      artifactsPatch = input.artifactsPatch,
      runtimeUpdate = DecompositionManifestRuntimeUpdate(
        workflowId = workflowId,
        workflowStatus = input.workflowStatus,
        currentStepId = input.currentStepId,
        stepUpdates = input.stepUpdates,
      ),
      fileStore = fileStore,
    ),
  )?.let { manifest ->
    DecompositionRuntimeInput(
      input = input.copy(
        artifactsPatch = LinkedHashMap(input.artifactsPatch.orEmpty()).apply {
          put(
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
            encodeDecompositionManifestMap(manifest, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY),
          )
        },
      ),
      updated = true,
    )
  } ?: DecompositionRuntimeInput(input = input, updated = false)
}

internal data class DecompositionRuntimeInput(
  val input: WorkflowUpdateInput,
  val updated: Boolean,
)

internal fun WorkflowEngine.syncDecompositionParentRuntime(
  family: WorkflowFamily,
  updated: WorkflowStateSnapshot,
  workflowId: String,
  unitOfWork: UnitOfWork,
  validator: DecompositionManifestValidator,
) {
  val manifest = updated.decompositionRuntime(validator)
  if (family == WorkflowFamily.TASK_RUNTIME && manifest != null) {
    val parent = unitOfWork.workflowStates.findDecomposedParentWorkflowForRuntime(manifest, validator)
    parent?.toSnapshot()
      ?.takeUnless { it.workflowId == workflowId }
      ?.let { p -> persistParentDecompositionRuntime(p, manifest, unitOfWork, validator) }
  }
}
