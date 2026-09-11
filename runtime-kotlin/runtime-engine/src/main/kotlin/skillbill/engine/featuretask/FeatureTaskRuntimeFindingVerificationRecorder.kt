package skillbill.engine.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance

class FeatureTaskRuntimeFindingVerificationRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
) : FeatureTaskRuntimePhaseFindingVerificationApi {
  override fun loadFindingVerificationCheckpoint(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? = database.transaction { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    findingVerificationCheckpointFrom(artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY])
  }

  override fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = database.transaction { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    findingVerificationBoundarySelectionFrom(
      artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY],
    )
  }

  override fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean {
    if (selections.isEmpty()) return false
    return database.transaction { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction false
      val artifacts = decodeArtifacts(record.artifactsJson)
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY to
            selections.mapValues { (_, headings) -> headings.map { it.toArtifactMap() } },
        ),
        WorkflowRowAdvance.keepFrom(record),
      )
      true
    }
  }

  override fun loadFindingVerificationDispositions(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>? = database.transaction { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction null
    val artifacts = decodeArtifacts(record.artifactsJson)
    findingVerificationCheckpointFrom(artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY])
  }

  override fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean {
    if (dispositions.isEmpty()) return false
    return database.transaction { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction false
      val serialized = dispositions.map { it.toArtifactMap() }
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY to serialized,
          FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY to serialized,
        ),
        WorkflowRowAdvance.keepFrom(record),
      )
      true
    }
  }

  override fun clearFindingVerificationCheckpoint(workflowId: String): Boolean = database.transaction { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@transaction false
    val artifacts = decodeArtifacts(record.artifactsJson)
    if (artifacts[FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY] == null) return@transaction true
    workflowPersistence.persistPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY to null),
      WorkflowRowAdvance.keepFrom(record),
    )
    true
  }

  private fun findingVerificationBoundarySelectionFrom(
    raw: Any?,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? {
    val entries = raw as? Map<*, *> ?: return null
    return entries.mapNotNull { (findingIdRaw, headingsRaw) ->
      val findingId = findingIdRaw as? String ?: return@mapNotNull null
      val headings = FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.parseList(
        headingsRaw,
        "$FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_BOUNDARY_SELECTION_ARTIFACT_KEY.$findingId",
      )
      findingId to headings
    }.toMap()
  }

  private fun findingVerificationCheckpointFrom(raw: Any?): List<FeatureTaskRuntimeFindingVerificationDisposition>? {
    if (raw == null) return null
    return FeatureTaskRuntimeFindingVerificationDisposition.parseList(
      raw,
      "finding_verification_checkpoint",
    )
  }
}
