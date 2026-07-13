package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.WorkListItem
import skillbill.application.model.WorkListItemKind
import skillbill.application.model.WorkListResult
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidWorkListRowError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.WorkItem
import skillbill.ports.persistence.model.WorkItemKind
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator

@Inject
class WorkListService(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val workflowEngine = WorkflowEngine(workflowSnapshotValidator)

  fun list(limit: Int? = null, dbOverride: String? = null): WorkListResult {
    require(limit == null || limit > 0) { "--limit must be a positive integer." }
    return database.read(dbOverride) { unitOfWork ->
      val persistedWork = unitOfWork.workList.list(limit)
      validateWorkflowSnapshots(unitOfWork, persistedWork)
      WorkListResult(
        dbPath = unitOfWork.dbPath.toString(),
        work = persistedWork.map(WorkItem::toApplicationItem),
      )
    }
  }

  private fun validateWorkflowSnapshots(unitOfWork: UnitOfWork, work: List<WorkItem>) {
    work.groupBy(::workflowFamily).forEach { (family, items) ->
      family ?: return@forEach
      val snapshots = family.getAll(unitOfWork.workflowStates, items.mapTo(linkedSetOf(), WorkItem::workflowId))
      items.forEach { item ->
        val snapshot = snapshots[item.workflowId]
          ?: throw InvalidWorkListRowError(
            "Work-list row '${item.workflowId}' has no matching ${item.workflowKind.wireValue} workflow snapshot.",
          )
        workflowEngine.summaryView(family.definition, snapshot)
      }
    }
  }
}

private fun workflowFamily(item: WorkItem): WorkflowFamily? = when (item.workflowKind) {
  WorkItemKind.FEATURE_TASK_PROSE -> WorkflowFamily.IMPLEMENT
  WorkItemKind.FEATURE_TASK_RUNTIME -> WorkflowFamily.TASK_RUNTIME
  WorkItemKind.FEATURE_VERIFY -> WorkflowFamily.VERIFY
  WorkItemKind.FEATURE_GOAL -> null
}

private fun WorkItem.toApplicationItem(): WorkListItem = WorkListItem(
  issueKey = issueKey,
  workflowKind = when (workflowKind) {
    WorkItemKind.FEATURE_TASK_PROSE -> WorkListItemKind.FEATURE_TASK_PROSE
    WorkItemKind.FEATURE_TASK_RUNTIME -> WorkListItemKind.FEATURE_TASK_RUNTIME
    WorkItemKind.FEATURE_VERIFY -> WorkListItemKind.FEATURE_VERIFY
    WorkItemKind.FEATURE_GOAL -> WorkListItemKind.FEATURE_GOAL
  },
  workflowId = workflowId,
  startedAt = startedAt,
  currentState = currentState,
  stateEnteredAt = stateEnteredAt,
  stateEnteredAtEstimated = stateEnteredAtEstimated,
)
