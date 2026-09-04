package skillbill.application.goalrunner

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.encodeDecompositionManifestMap
import skillbill.application.workflow.decompositionRuntime
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput

class GoalParentProjectionWriter(
  private val engine: WorkflowEngine,
  private val validator: DecompositionManifestValidator,
) {
  @OpenBoundaryMap("Goal parent decomposition runtime artifact patch")
  fun artifacts(manifest: DecompositionManifest, existingArtifactsJson: String? = null): Map<String, Any?> =
    LinkedHashMap(
      existingArtifactsJson
        ?.takeIf(String::isNotBlank)
        ?.let(::decodeArtifacts)
        .orEmpty(),
    ).apply {
      remove(GOAL_REVIEW_POLICY_ARTIFACT_KEY)
      remove(GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY)
      put(
        DECOMPOSITION_RUNTIME_ARTIFACT_KEY,
        encodeDecompositionManifestMap(manifest, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY),
      )
    }

  fun rewrite(unitOfWork: UnitOfWork, existing: WorkflowStateSnapshot) {
    val manifest = existing.decompositionRuntime(validator)
      ?: error("Goal parent workflow '${existing.workflowId}' has no decomposition manifest.")
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      existing,
      WorkflowUpdateInput(
        workflowStatus = existing.workflowStatus,
        currentStepId = existing.currentStepId,
        stepUpdates = null,
        artifactsPatch = artifacts(manifest, existing.artifactsJson),
        sessionId = existing.sessionId,
        replaceArtifacts = true,
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      updated.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
    )
  }
}
