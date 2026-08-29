package skillbill.application.goalrunner

import skillbill.application.normalizeRequiredIssueKey
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.decompositionRuntime
import skillbill.application.workflow.findDecomposedParentWorkflow
import skillbill.application.workflow.requireRuntimeModeForEngineWrite
import skillbill.application.workflow.toRecord
import skillbill.application.workflow.toSnapshot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput

internal data class SavedManifestProjection(
  val state: GoalRunnerManifestState,
  val projectionArtifactsJson: String,
)

internal class WorkflowGoalRunnerManifestProjectionPersistence(
  private val database: DatabaseSessionFactory,
  private val engine: WorkflowEngine,
  private val parentProjection: GoalParentProjectionWriter,
  private val decompositionManifestValidator: DecompositionManifestValidator,
) {
  fun save(state: GoalRunnerManifestState, dbPathOverride: String?): SavedManifestProjection =
    database.transaction(dbPathOverride) { unitOfWork -> saveInTransaction(unitOfWork, state) }

  fun saveInTransaction(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    clearOutOfBandAcceptances: Boolean = false,
    mergeConcurrentProgress: Boolean = true,
  ): SavedManifestProjection {
    val existingRecord = unitOfWork.workflowStates.getFeatureTaskWorkflow(state.parentWorkflowId)
      ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
        state.manifest.issueKey,
        decompositionManifestValidator,
      )
      ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
    existingRecord.requireRuntimeModeForEngineWrite()
    val existingSnapshot = existingRecord.toSnapshot()
    migrateLegacyGoalRunnerControls(unitOfWork, existingSnapshot)
    if (clearOutOfBandAcceptances) {
      unitOfWork.goalRunnerControls.clearOutOfBandAcceptances(existingSnapshot.workflowId)
      unitOfWork.goalRunnerControls.clearControlState(existingSnapshot.workflowId)
    }
    val manifest = if (mergeConcurrentProgress) {
      mergeConcurrentGoalProgress(
        existingSnapshot.decompositionRuntime(decompositionManifestValidator) ?: state.manifest,
        state.manifest,
      )
    } else {
      state.manifest
    }
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      existingSnapshot,
      WorkflowUpdateInput(
        workflowStatus = existingSnapshot.workflowStatus,
        currentStepId = existingSnapshot.currentStepId,
        stepUpdates = null,
        artifactsPatch = parentProjection.artifacts(manifest, existingSnapshot.artifactsJson),
        sessionId = existingSnapshot.sessionId.orEmpty(),
        replaceArtifacts = true,
      ),
    )
    WorkflowFamily.TASK_RUNTIME.saveRecord(
      unitOfWork.workflowStates,
      updated.toRecord().copy(issueKey = normalizeRequiredIssueKey(manifest.issueKey)),
    )
    val refreshed = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, updated.workflowId) ?: updated
    reconcileControlStateForManifest(unitOfWork, refreshed.workflowId, decompositionManifestValidator)
    return SavedManifestProjection(
      state = GoalRunnerManifestState(
        parentWorkflowId = refreshed.workflowId,
        dbPath = unitOfWork.dbPath.toString(),
        manifest = refreshed.decompositionRuntime(decompositionManifestValidator) ?: manifest,
        controlState = unitOfWork.goalRunnerControls.controlState(refreshed.workflowId),
        repoRoot = state.repoRoot,
      ),
      projectionArtifactsJson = refreshed.artifactsJson,
    )
  }
}
