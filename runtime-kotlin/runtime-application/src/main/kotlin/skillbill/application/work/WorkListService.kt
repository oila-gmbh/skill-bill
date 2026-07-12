package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.InvalidWorkListRowError
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.WorkItem
import skillbill.ports.persistence.model.WorkItemKind
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator

data class WorkListResult(
  val dbPath: String,
  val work: List<WorkItem>,
)

@Inject
class WorkListService(
  private val database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  private val workflowEngine = WorkflowEngine(workflowSnapshotValidator)

  fun list(limit: Int? = null, dbOverride: String? = null): WorkListResult {
    require(limit == null || limit > 0) { "--limit must be a positive integer." }
    return database.read(dbOverride) { unitOfWork ->
      val work = unitOfWork.workList.list(limit)
      validateWorkflowSnapshots(unitOfWork, work)
      WorkListResult(
        dbPath = unitOfWork.dbPath.toString(),
        work = work,
      )
    }
  }

  private fun validateWorkflowSnapshots(unitOfWork: UnitOfWork, work: List<WorkItem>) {
    work.forEach { item ->
      val family = when (item.workflowKind) {
        WorkItemKind.FEATURE_TASK_PROSE -> WorkflowFamily.IMPLEMENT
        WorkItemKind.FEATURE_TASK_RUNTIME -> WorkflowFamily.TASK_RUNTIME
        WorkItemKind.FEATURE_VERIFY -> WorkflowFamily.VERIFY
        WorkItemKind.FEATURE_GOAL -> null
      } ?: return@forEach
      val snapshot = family.get(unitOfWork.workflowStates, item.workflowId)
        ?: throw InvalidWorkListRowError(
          "Work-list row '${item.workflowId}' has no matching ${item.workflowKind.wireValue} workflow snapshot.",
        )
      workflowEngine.summaryView(family.definition, snapshot)
    }
  }
}
