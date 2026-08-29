package skillbill.application.featuretask

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress

internal class FeatureTaskRuntimeGateProgressRecorder(
  private val database: DatabaseSessionFactory,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
) {
  fun loadValidationGateProgress(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeValidationGateProgress? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
    val raw = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY]
    val artifact = JsonSupport.anyToStringAnyMap(raw) ?: return@read null
    FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(artifact)
  }

  fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    dbOverride: String? = null,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: throw InvalidWorkflowStateSchemaError(
          "Cannot persist validation gate progress: workflow '$workflowId' is missing.",
        )
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY to progress.toArtifactMap()),
      )
    }
  }

  fun loadAuditGapProgress(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditGapProgress? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val raw = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY]
      val artifact = JsonSupport.anyToStringAnyMap(raw) ?: return@read null
      FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(artifact)
    }

  fun persistAuditGapProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeAuditGapProgress,
    dbOverride: String? = null,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: throw InvalidWorkflowStateSchemaError(
          "Cannot persist audit gap progress: workflow '$workflowId' is missing.",
        )
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_AUDIT_GAP_PROGRESS_ARTIFACT_KEY to progress.toArtifactMap()),
      )
    }
  }

  fun loadAuditGapPause(workflowId: String, dbOverride: String? = null): FeatureTaskRuntimeAuditGapPause? =
    database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
      val raw = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY]
      val artifact = JsonSupport.anyToStringAnyMap(raw) ?: return@read null
      FeatureTaskRuntimeAuditGapPause.fromArtifactMap(artifact)
    }

  fun persistAuditGapPause(workflowId: String, pause: FeatureTaskRuntimeAuditGapPause, dbOverride: String? = null) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: throw InvalidWorkflowStateSchemaError(
          "Cannot persist audit gap pause: workflow '$workflowId' is missing.",
        )
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_AUDIT_GAP_PAUSE_ARTIFACT_KEY to pause.toArtifactMap()),
      )
    }
  }

  fun loadBuildGateProgress(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeValidationGateProgress? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
    val raw = decodeArtifacts(record.artifactsJson)[FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY]
    val artifact = JsonSupport.anyToStringAnyMap(raw) ?: return@read null
    FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(artifact)
  }

  fun loadGoalContinuationQualityGateSelection(
    workflowId: String,
    dbOverride: String? = null,
  ): FeatureTaskRuntimeQualityGateSelection? = database.read(dbOverride) { unitOfWork ->
    val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId) ?: return@read null
    GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly(decodeArtifacts(record.artifactsJson))
      ?.qualityGateSelection
  }

  fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
    dbOverride: String? = null,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: throw InvalidWorkflowStateSchemaError(
          "Cannot persist build gate progress: workflow '$workflowId' is missing.",
        )
      workflowPersistence.persistPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY to progress.toArtifactMap()),
      )
    }
  }
}
