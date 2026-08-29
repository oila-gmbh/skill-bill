package skillbill.db.workflow

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.ProseFeatureTaskWorkflowWriteRefusedError
import skillbill.ports.workflow.FeatureTaskWorkflowRowRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import java.sql.Connection

internal class FeatureTaskWorkflowRowStore(
  private val connection: Connection,
) : FeatureTaskWorkflowRowRepository {
  override fun saveFeatureTaskWorkflow(row: WorkflowStateRecord, mode: FeatureTaskWorkflowMode) {
    // SKILL-175 subtask 6: refuse mode=prose above the schema (the CHECK constraint still spells
    // 'prose' so legacy rows stay insert-compatible with their own history; see
    // runtime-kotlin/agent/decisions.md, "In-flight prose row policy"). This is the live write path
    // `WorkflowService` calls for both families, so the guard lives here rather than only in the
    // `FeatureImplementWorkflowStateRepository` compatibility alias below.
    if (mode == FeatureTaskWorkflowMode.PROSE) {
      throw ProseFeatureTaskWorkflowWriteRefusedError(row.workflowId)
    }
    connection.upsertFeatureTaskWorkflowRow(
      row = row,
      mode = mode,
      implementationSkill = row.implementationSkill.orEmpty().ifBlank { mode.defaultImplementationSkill },
      defaultContractVersion = mode.defaultContractVersion,
    )
  }

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    connection.getFeatureTaskWorkflowRow(workflowId)

  override fun getFeatureTaskWorkflowAsMode(workflowId: String, mode: FeatureTaskWorkflowMode): WorkflowStateRecord? {
    val row = connection.getFeatureTaskWorkflowRow(workflowId) ?: return null
    if (row.mode != mode) {
      throw InvalidWorkflowStateSchemaError(
        "Feature-task workflow '$workflowId' is mode='${row.mode?.wireValue.orEmpty()}', not '${mode.wireValue}'.",
      )
    }
    return row
  }

  override fun listFeatureTaskWorkflows(mode: FeatureTaskWorkflowMode, limit: Int): List<WorkflowStateRecord> =
    connection.listFeatureTaskWorkflowRows(mode, limit)

  override fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    listFeatureTaskWorkflows(mode, 1).firstOrNull()

  override fun terminalizeLegacyProseFeatureTaskWorkflow(row: WorkflowStateRecord) {
    connection.terminalizeLegacyProseFeatureTaskWorkflowRow(row)
  }
}
