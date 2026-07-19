package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.workflow.WorkflowFamily
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap
import skillbill.workflow.taskruntime.model.toArtifactMap

/** Run-scoped invariant store for feature-task-runtime resume stability. */
@Inject
class FeatureTaskRuntimeRunInvariantsStore(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val engine = WorkflowEngine(workflowSnapshotValidator)

  /**
   * Strict read of durable run-invariants, optionally persisting [proposed] exactly once first.
   * Passing null is read-only; passing a value at run creation freezes the invariant for resume.
   */
  fun resolve(
    workflowId: String,
    dbOverride: String? = null,
    proposed: FeatureTaskRuntimeRunInvariants? = null,
  ): FeatureTaskRuntimeRunInvariants? {
    proposed?.let { persistOrUpdateAgentAddons(workflowId, dbOverride, it) }
    return database.read(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@read null
      runInvariantsFrom(decodeArtifacts(record.artifactsJson))
    }
  }

  private fun persistOrUpdateAgentAddons(
    workflowId: String,
    dbOverride: String?,
    proposed: FeatureTaskRuntimeRunInvariants,
  ) {
    database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowFamily.TASK_RUNTIME.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction
      val artifacts = decodeArtifacts(record.artifactsJson)
      val existing = runInvariantsFrom(artifacts)
      when {
        existing == null -> persistPatch(unitOfWork.workflowStates, record, proposed)
        existing.agentAddonSelection != proposed.agentAddonSelection -> persistPatch(
          unitOfWork.workflowStates,
          record,
          existing.copy(agentAddonSelection = proposed.agentAddonSelection),
        )
      }
    }
  }

  private fun persistPatch(
    workflowStates: WorkflowStateRepository,
    record: WorkflowStateSnapshot,
    runInvariants: FeatureTaskRuntimeRunInvariants,
  ) {
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = mapOf(FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY to runInvariants.toArtifactMap()),
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
  }
}

private fun runInvariantsFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeRunInvariants? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY] ?: return null
  val entryMap = JsonSupport.anyToStringAnyMap(raw)
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_RUN_INVARIANTS_ARTIFACT_KEY' must decode to a map.",
    )
  return featureTaskRuntimeRunInvariantsFromArtifactMap(entryMap)
}
