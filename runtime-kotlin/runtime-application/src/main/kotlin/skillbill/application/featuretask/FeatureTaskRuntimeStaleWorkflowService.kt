package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeStaleWorkflow
import skillbill.application.featuretask.model.FeatureTaskRuntimeStaleWorkflowPruneRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeStaleWorkflowPruneResult
import skillbill.application.workflow.toSnapshot
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskRuntimeSnapshot
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
class FeatureTaskRuntimeStaleWorkflowService(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val engine = WorkflowEngine(workflowSnapshotValidator)

  fun prune(request: FeatureTaskRuntimeStaleWorkflowPruneRequest): FeatureTaskRuntimeStaleWorkflowPruneResult {
    val requestedWorkflowIds = request.workflowIds.map(String::trim).filter(String::isNotBlank).distinct()
    return if (request.confirm) {
      database.transaction(request.dbPathOverride) { unitOfWork ->
        reconcileAndDelete(unitOfWork.workflowStates, unitOfWork.dbPath.toString(), requestedWorkflowIds)
      }
    } else {
      database.read(request.dbPathOverride) { unitOfWork ->
        val stale = staleWorkflows(unitOfWork.workflowStates)
        FeatureTaskRuntimeStaleWorkflowPruneResult(
          dbPath = unitOfWork.dbPath.toString(),
          confirmed = false,
          requestedWorkflowIds = requestedWorkflowIds,
          staleWorkflows = selectStaleWorkflows(stale, requestedWorkflowIds),
          deletedWorkflowIds = emptyList(),
          retainedWorkflowIds = emptyList(),
        )
      }
    }
  }

  private fun reconcileAndDelete(
    workflowStates: WorkflowStateRepository,
    dbPath: String,
    requestedWorkflowIds: List<String>,
  ): FeatureTaskRuntimeStaleWorkflowPruneResult {
    val stale = staleWorkflows(workflowStates)
    val selected = selectStaleWorkflows(stale, requestedWorkflowIds)
    val revalidated = selected.mapNotNull { staleCandidate ->
      workflowStates.getFeatureTaskRuntimeSnapshot(staleCandidate.workflowId)?.let { snapshot ->
        staleWorkflow(snapshot)
      }
    }
    val deleted = revalidated.mapNotNull { staleWorkflow ->
      staleWorkflow.workflowId.takeIf { workflowStates.deleteFeatureTaskRuntimeWorkflow(it) }
    }
    val retained = selected.map { it.workflowId }.filterNot { it in deleted }
    return FeatureTaskRuntimeStaleWorkflowPruneResult(
      dbPath = dbPath,
      confirmed = true,
      requestedWorkflowIds = requestedWorkflowIds,
      staleWorkflows = selected,
      deletedWorkflowIds = deleted,
      retainedWorkflowIds = retained,
    )
  }

  private fun staleWorkflows(workflowStates: WorkflowStateRepository): List<FeatureTaskRuntimeStaleWorkflow> =
    workflowStates.listFeatureTaskRuntimeSnapshots(Int.MAX_VALUE).mapNotNull(::staleWorkflow)

  private fun selectStaleWorkflows(
    staleWorkflows: List<FeatureTaskRuntimeStaleWorkflow>,
    requestedWorkflowIds: List<String>,
  ): List<FeatureTaskRuntimeStaleWorkflow> = staleWorkflows.filter {
    requestedWorkflowIds.isEmpty() || it.workflowId in requestedWorkflowIds
  }

  private fun staleWorkflow(snapshot: FeatureTaskRuntimeSnapshot): FeatureTaskRuntimeStaleWorkflow? {
    val row = snapshot.workflow
    if (row.mode != FeatureTaskWorkflowMode.RUNTIME) return null
    return try {
      engine.snapshotView(FeatureTaskRuntimePhaseWorkflowDefinition.definition, row.toSnapshot())
      null
    } catch (error: InvalidWorkflowStateSchemaError) {
      FeatureTaskRuntimeStaleWorkflow(
        workflowId = row.workflowId,
        issueKey = row.issueKey?.trim()?.takeIf(String::isNotBlank)
          ?: snapshot.identity?.normalizedIssueKey?.trim()?.takeIf(String::isNotBlank),
        validationFailure = redactedWorkflowStateFailure(error),
      )
    }
  }
}
