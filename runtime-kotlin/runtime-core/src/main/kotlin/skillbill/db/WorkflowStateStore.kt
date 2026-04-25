package skillbill.db

import skillbill.ports.persistence.WorkflowStateRecord
import skillbill.ports.persistence.WorkflowStateRepository
import java.sql.Connection

typealias WorkflowStateRow = WorkflowStateRecord

class WorkflowStateStore(
  private val connection: Connection,
) : WorkflowStateRepository {
  private companion object {
    const val WORKFLOW_ID_PARAMETER_INDEX: Int = 1
    const val SESSION_ID_PARAMETER_INDEX: Int = 2
    const val WORKFLOW_NAME_PARAMETER_INDEX: Int = 3
    const val CONTRACT_VERSION_PARAMETER_INDEX: Int = 4
    const val WORKFLOW_STATUS_PARAMETER_INDEX: Int = 5
    const val CURRENT_STEP_ID_PARAMETER_INDEX: Int = 6
    const val STEPS_JSON_PARAMETER_INDEX: Int = 7
    const val ARTIFACTS_JSON_PARAMETER_INDEX: Int = 8
    const val FINISHED_AT_PARAMETER_INDEX: Int = 9
  }

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    upsert(
      tableName = "feature_implement_workflows",
      row = row,
      defaultContractVersion = DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION,
    )
  }

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) {
    upsert(
      tableName = "feature_verify_workflows",
      row = row,
      defaultContractVersion = DbConstants.FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION,
    )
  }

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = get(
    "feature_implement_workflows",
    workflowId,
  )

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? =
    get("feature_verify_workflows", workflowId)

  private fun upsert(tableName: String, row: WorkflowStateRecord, defaultContractVersion: String) {
    connection.prepareStatement(
      """
      INSERT INTO $tableName (
        workflow_id,
        session_id,
        workflow_name,
        contract_version,
        workflow_status,
        current_step_id,
        steps_json,
        artifacts_json,
        finished_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id) DO UPDATE SET
        session_id = excluded.session_id,
        workflow_name = excluded.workflow_name,
        contract_version = excluded.contract_version,
        workflow_status = excluded.workflow_status,
        current_step_id = excluded.current_step_id,
        steps_json = excluded.steps_json,
        artifacts_json = excluded.artifacts_json,
        updated_at = CURRENT_TIMESTAMP,
        finished_at = excluded.finished_at
      """.trimIndent(),
    ).use { statement ->
      statement.setString(WORKFLOW_ID_PARAMETER_INDEX, row.workflowId)
      statement.setString(SESSION_ID_PARAMETER_INDEX, row.sessionId)
      statement.setString(WORKFLOW_NAME_PARAMETER_INDEX, row.workflowName)
      statement.setString(
        CONTRACT_VERSION_PARAMETER_INDEX,
        row.contractVersion.ifBlank { defaultContractVersion },
      )
      statement.setString(WORKFLOW_STATUS_PARAMETER_INDEX, row.workflowStatus)
      statement.setString(CURRENT_STEP_ID_PARAMETER_INDEX, row.currentStepId)
      statement.setString(STEPS_JSON_PARAMETER_INDEX, row.stepsJson)
      statement.setString(ARTIFACTS_JSON_PARAMETER_INDEX, row.artifactsJson)
      statement.setString(FINISHED_AT_PARAMETER_INDEX, row.finishedAt)
      statement.executeUpdate()
    }
  }

  private fun get(tableName: String, workflowId: String): WorkflowStateRecord? = connection.prepareStatement(
    """
      SELECT
        workflow_id,
        session_id,
        workflow_name,
        contract_version,
        workflow_status,
        current_step_id,
        steps_json,
        artifacts_json,
        started_at,
        updated_at,
        finished_at
      FROM $tableName
      WHERE workflow_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(WORKFLOW_ID_PARAMETER_INDEX, workflowId)
    statement.executeQuery().use { resultSet ->
      if (!resultSet.next()) {
        return null
      }
      WorkflowStateRecord(
        workflowId = resultSet.getString("workflow_id"),
        sessionId = resultSet.getString("session_id"),
        workflowName = resultSet.getString("workflow_name"),
        contractVersion = resultSet.getString("contract_version"),
        workflowStatus = resultSet.getString("workflow_status"),
        currentStepId = resultSet.getString("current_step_id"),
        stepsJson = resultSet.getString("steps_json"),
        artifactsJson = resultSet.getString("artifacts_json"),
        startedAt = resultSet.getString("started_at"),
        updatedAt = resultSet.getString("updated_at"),
        finishedAt = resultSet.getString("finished_at"),
      )
    }
  }
}
