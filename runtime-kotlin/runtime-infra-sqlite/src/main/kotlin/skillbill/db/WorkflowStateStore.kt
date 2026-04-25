package skillbill.db

import skillbill.contracts.JsonSupport
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import java.sql.Connection

typealias WorkflowStateRow = WorkflowStateRecord

private const val WORKFLOW_ID_PARAMETER_INDEX: Int = 1
private const val SESSION_ID_PARAMETER_INDEX: Int = 2
private const val WORKFLOW_NAME_PARAMETER_INDEX: Int = 3
private const val CONTRACT_VERSION_PARAMETER_INDEX: Int = 4
private const val WORKFLOW_STATUS_PARAMETER_INDEX: Int = 5
private const val CURRENT_STEP_ID_PARAMETER_INDEX: Int = 6
private const val STEPS_JSON_PARAMETER_INDEX: Int = 7
private const val ARTIFACTS_JSON_PARAMETER_INDEX: Int = 8
private const val INSERT_TERMINAL_PARAMETER_INDEX: Int = 9
private const val INSERT_FINISHED_AT_PARAMETER_INDEX: Int = 10
private const val UPDATE_TERMINAL_PARAMETER_INDEX: Int = 11

private val terminalWorkflowStatuses: Set<String> = setOf("completed", "failed", "abandoned")

class WorkflowStateStore(
  private val connection: Connection,
) : WorkflowStateRepository {
  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    connection.upsertWorkflowRow(
      tableName = "feature_implement_workflows",
      row = row,
      defaultContractVersion = DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION,
    )
  }

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) {
    connection.upsertWorkflowRow(
      tableName = "feature_verify_workflows",
      row = row,
      defaultContractVersion = DbConstants.FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION,
    )
  }

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? =
    connection.getWorkflowRow("feature_implement_workflows", workflowId)

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? =
    connection.getWorkflowRow("feature_verify_workflows", workflowId)

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> =
    connection.listWorkflowRows("feature_implement_workflows", limit)

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> =
    connection.listWorkflowRows("feature_verify_workflows", limit)

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = listFeatureImplementWorkflows(1).firstOrNull()

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = listFeatureVerifyWorkflows(1).firstOrNull()

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? =
    connection.prepareStatement(
      """
      SELECT
        session_id,
        issue_key_provided,
        issue_key_type,
        spec_input_types,
        spec_word_count,
        feature_size,
        feature_name,
        rollout_needed,
        acceptance_criteria_count,
        open_questions_count,
        spec_summary
      FROM feature_implement_sessions
      WHERE session_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(WORKFLOW_ID_PARAMETER_INDEX, sessionId)
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) {
          return null
        }
        FeatureImplementSessionSummary(
          sessionId = resultSet.getString("session_id"),
          issueKeyProvided = resultSet.getInt("issue_key_provided") == 1,
          issueKeyType = resultSet.getString("issue_key_type"),
          specInputTypes = decodeStringList(resultSet.getString("spec_input_types")),
          specWordCount = resultSet.getInt("spec_word_count"),
          featureSize = resultSet.getString("feature_size"),
          featureName = resultSet.getString("feature_name"),
          rolloutNeeded = resultSet.getInt("rollout_needed") == 1,
          acceptanceCriteriaCount = resultSet.getInt("acceptance_criteria_count"),
          openQuestionsCount = resultSet.getInt("open_questions_count"),
          specSummary = resultSet.getString("spec_summary"),
        )
      }
    }

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? =
    connection.prepareStatement(
      """
      SELECT
        session_id,
        acceptance_criteria_count,
        rollout_relevant,
        spec_summary
      FROM feature_verify_sessions
      WHERE session_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(WORKFLOW_ID_PARAMETER_INDEX, sessionId)
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) {
          return null
        }
        FeatureVerifySessionSummary(
          sessionId = resultSet.getString("session_id"),
          acceptanceCriteriaCount = resultSet.getInt("acceptance_criteria_count"),
          rolloutRelevant = resultSet.getInt("rollout_relevant") == 1,
          specSummary = resultSet.getString("spec_summary"),
        )
      }
    }
}

private fun Connection.upsertWorkflowRow(tableName: String, row: WorkflowStateRecord, defaultContractVersion: String) {
  prepareStatement(
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
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN COALESCE(NULLIF(?, ''), CURRENT_TIMESTAMP) ELSE NULL END)
    ON CONFLICT(workflow_id) DO UPDATE SET
      session_id = excluded.session_id,
      workflow_name = excluded.workflow_name,
      contract_version = excluded.contract_version,
      workflow_status = excluded.workflow_status,
      current_step_id = excluded.current_step_id,
      steps_json = excluded.steps_json,
      artifacts_json = excluded.artifacts_json,
      updated_at = CURRENT_TIMESTAMP,
      finished_at = CASE
        WHEN ? THEN COALESCE(NULLIF(excluded.finished_at, ''), CURRENT_TIMESTAMP)
        ELSE NULL
      END
    """.trimIndent(),
  ).use { statement ->
    val terminal = row.workflowStatus in terminalWorkflowStatuses
    statement.setString(WORKFLOW_ID_PARAMETER_INDEX, row.workflowId)
    statement.setString(SESSION_ID_PARAMETER_INDEX, row.sessionId)
    statement.setString(WORKFLOW_NAME_PARAMETER_INDEX, row.workflowName)
    statement.setString(CONTRACT_VERSION_PARAMETER_INDEX, row.contractVersion.ifBlank { defaultContractVersion })
    statement.setString(WORKFLOW_STATUS_PARAMETER_INDEX, row.workflowStatus)
    statement.setString(CURRENT_STEP_ID_PARAMETER_INDEX, row.currentStepId)
    statement.setString(STEPS_JSON_PARAMETER_INDEX, row.stepsJson)
    statement.setString(ARTIFACTS_JSON_PARAMETER_INDEX, row.artifactsJson)
    statement.setBoolean(INSERT_TERMINAL_PARAMETER_INDEX, terminal)
    statement.setString(INSERT_FINISHED_AT_PARAMETER_INDEX, row.finishedAt)
    statement.setBoolean(UPDATE_TERMINAL_PARAMETER_INDEX, terminal)
    statement.executeUpdate()
  }
}

private fun Connection.getWorkflowRow(tableName: String, workflowId: String): WorkflowStateRecord? = prepareStatement(
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
  statement.setString(1, workflowId)
  statement.executeQuery().use { resultSet ->
    if (!resultSet.next()) {
      return null
    }
    resultSet.toWorkflowStateRecord()
  }
}

private fun Connection.listWorkflowRows(tableName: String, limit: Int): List<WorkflowStateRecord> {
  val normalizedLimit = limit.coerceAtLeast(0)
  return prepareStatement(
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
    ORDER BY updated_at DESC, rowid DESC
    LIMIT ?
    """.trimIndent(),
  ).use { statement ->
    statement.setInt(1, normalizedLimit)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(resultSet.toWorkflowStateRecord())
        }
      }
    }
  }
}

private fun java.sql.ResultSet.toWorkflowStateRecord(): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = getString("workflow_id"),
  sessionId = getString("session_id"),
  workflowName = getString("workflow_name"),
  contractVersion = getString("contract_version"),
  workflowStatus = getString("workflow_status"),
  currentStepId = getString("current_step_id"),
  stepsJson = getString("steps_json"),
  artifactsJson = getString("artifacts_json"),
  startedAt = getString("started_at"),
  updatedAt = getString("updated_at"),
  finishedAt = getString("finished_at"),
)

private fun decodeStringList(rawValue: String?): List<String> =
  JsonSupport.parseArrayOrEmpty(rawValue.orEmpty()).mapNotNull { element -> element as? String }
