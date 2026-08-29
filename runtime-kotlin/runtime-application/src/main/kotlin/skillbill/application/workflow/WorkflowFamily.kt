package skillbill.application.workflow

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.boundary.OpenBoundaryMap
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition

internal fun WorkflowFamilyKind.workflowFamily(): WorkflowFamily = when (this) {
  WorkflowFamilyKind.VERIFY -> WorkflowFamily.VERIFY
  WorkflowFamilyKind.TASK_RUNTIME -> WorkflowFamily.TASK_RUNTIME
}

internal enum class WorkflowFamily(
  val definition: WorkflowDefinition,
  val humanName: String,
  val loopOnlyStepIds: Set<String> = emptySet(),
) {
  VERIFY(FeatureVerifyWorkflowDefinition.definition, "feature-verify"),
  TASK_RUNTIME(
    FeatureTaskRuntimePhaseWorkflowDefinition.definition,
    "feature-task-runtime",
    FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds,
  ),
  ;

  init {
    require(loopOnlyStepIds.all { it in definition.stepIds }) {
      "WorkflowFamily $humanName declares loop-only steps absent from its definition: " +
        "${loopOnlyStepIds - definition.stepIds.toSet()}"
    }
  }

  fun save(repository: WorkflowStateRepository, record: WorkflowStateSnapshot) {
    saveRecord(repository, record.toRecord())
  }

  fun saveRecord(repository: WorkflowStateRepository, record: WorkflowStateRecord) {
    when (this) {
      VERIFY -> repository.saveFeatureVerifyWorkflow(record)
      TASK_RUNTIME -> repository.saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.RUNTIME)
    }
  }

  fun get(repository: WorkflowStateRepository, workflowId: String): WorkflowStateSnapshot? = when (this) {
    VERIFY -> repository.getFeatureVerifyWorkflow(workflowId)
    TASK_RUNTIME -> repository.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
  }?.toSnapshot()

  fun getAll(repository: WorkflowStateRepository, workflowIds: Set<String>): Map<String, WorkflowStateSnapshot> =
    buildMap {
      workflowIds.chunked(WORKFLOW_SNAPSHOT_BATCH_SIZE).forEach { batch ->
        val records = when (this@WorkflowFamily) {
          VERIFY -> repository.getFeatureVerifyWorkflows(batch.toSet())
          TASK_RUNTIME -> repository.getFeatureTaskRuntimeWorkflows(batch.toSet())
        }
        records.forEach { (workflowId, record) -> put(workflowId, record.toSnapshot()) }
      }
    }

  fun list(repository: WorkflowStateRepository, limit: Int): List<WorkflowStateSnapshot> = when (this) {
    VERIFY -> repository.listFeatureVerifyWorkflows(limit)
    TASK_RUNTIME -> repository.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, limit)
  }.map(WorkflowStateRecord::toSnapshot)

  fun latest(repository: WorkflowStateRepository): WorkflowStateSnapshot? = when (this) {
    VERIFY -> repository.latestFeatureVerifyWorkflow()
    TASK_RUNTIME -> repository.latestFeatureTaskWorkflow(FeatureTaskWorkflowMode.RUNTIME)
  }?.toSnapshot()

  @OpenBoundaryMap("Durable workflow session summary passthrough")
  fun sessionSummary(repository: WorkflowStateRepository, sessionId: String): Map<String, Any?> {
    if (sessionId.isBlank()) {
      return emptyMap()
    }
    return when (this) {
      VERIFY -> repository.getFeatureVerifySessionSummary(sessionId)?.toPayload().orEmpty()
      TASK_RUNTIME -> emptyMap()
    }
  }
}

internal const val WORKFLOW_SNAPSHOT_BATCH_SIZE = 900
