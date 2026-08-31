package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.workflow.model.DecompositionManifestRuntimeUpdate
import skillbill.application.workflow.model.DecompositionManifestWorkflowProjectionInput
import skillbill.application.workflow.model.DecompositionRuntimeWriteArgs
import skillbill.ports.db.UnitOfWork
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput

internal fun WorkflowFamily.withDecompositionRuntime(args: DecompositionRuntimeWriteArgs): DecompositionRuntimeInput =
  if (this != WorkflowFamily.TASK_RUNTIME) {
    DecompositionRuntimeInput(input = args.input, updated = false)
  } else {
    args.manifestWriter.manifestFromWorkflowUpdate(
      DecompositionManifestWorkflowProjectionInput(
        repoRoot = args.repoRoot,
        existingArtifactsJson = args.existing.artifactsJson,
        validator = args.validator,
        artifactsPatch = args.input.artifactsPatch,
        runtimeUpdate = DecompositionManifestRuntimeUpdate(
          workflowId = args.workflowId,
          workflowStatus = args.input.workflowStatus,
          currentStepId = args.input.currentStepId,
          stepUpdates = args.input.stepUpdates,
        ),
        fileStore = args.fileStore,
      ),
    )?.let { manifest ->
      DecompositionRuntimeInput(
        input = args.input.copy(
          artifactsPatch = LinkedHashMap(args.input.artifactsPatch.orEmpty()).apply {
            put(
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
              encodeDecompositionManifestMap(manifest, args.validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY),
            )
          },
        ),
        updated = true,
      )
    } ?: DecompositionRuntimeInput(input = args.input, updated = false)
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
