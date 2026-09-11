package skillbill.infrastructure.sqlite.workflow

import skillbill.ports.workflow.FeatureTaskRuntimeWorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskRuntimeSnapshot
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import java.sql.Connection

internal class FeatureTaskRuntimeWorkflowStateStore(
  private val connection: Connection,
) : FeatureTaskRuntimeWorkflowStateRepository {
  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    connection.upsertFeatureTaskWorkflowRow(
      row = row,
      mode = FeatureTaskWorkflowMode.RUNTIME,
      implementationSkill = row.implementationSkill.orEmpty().ifBlank {
        FeatureTaskWorkflowMode.RUNTIME.defaultImplementationSkill
      },
      defaultContractVersion = FeatureTaskWorkflowMode.RUNTIME.defaultContractVersion,
    )
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? =
    connection.getFeatureTaskWorkflowRowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)

  override fun getFeatureTaskRuntimeWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    connection.getFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.RUNTIME, workflowIds)

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    connection.listFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.RUNTIME, limit)

  override fun listFeatureTaskRuntimeSnapshots(limit: Int): List<FeatureTaskRuntimeSnapshot> =
    connection.listFeatureTaskWorkflowSnapshotRows(FeatureTaskWorkflowMode.RUNTIME, limit)
      .map(::runtimeSnapshot)

  override fun getFeatureTaskRuntimeSnapshot(workflowId: String): FeatureTaskRuntimeSnapshot? =
    connection.getFeatureTaskWorkflowSnapshotRow(workflowId)?.let(::runtimeSnapshot)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()

  override fun deleteFeatureTaskRuntimeWorkflow(workflowId: String): Boolean = connection.prepareStatement(
    "DELETE FROM feature_task_workflows WHERE workflow_id = ? AND mode = ?",
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.setString(2, FeatureTaskWorkflowMode.RUNTIME.wireValue)
    statement.executeUpdate() == 1
  }

  private fun runtimeSnapshot(row: WorkflowStateRecord): FeatureTaskRuntimeSnapshot = FeatureTaskRuntimeSnapshot(
    workflow = row,
    identity = connection.featureTaskIdentity(row.workflowId),
  )
}
