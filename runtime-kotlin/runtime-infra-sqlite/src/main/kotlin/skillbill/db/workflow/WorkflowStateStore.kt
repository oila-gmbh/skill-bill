package skillbill.db.workflow

import skillbill.contracts.JsonSupport
import skillbill.db.core.DbConstants
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.FeatureImplementWorkflowStateRepository
import skillbill.ports.persistence.FeatureTaskRuntimeWorkflowStateRepository
import skillbill.ports.persistence.FeatureTaskWorkflowStateRepository
import skillbill.ports.persistence.FeatureVerifyWorkflowStateRepository
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
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
private const val FEATURE_TASK_MODE_PARAMETER_INDEX: Int = 12
private const val FEATURE_TASK_IMPLEMENTATION_SKILL_PARAMETER_INDEX: Int = 13

private val terminalWorkflowStatuses: Set<String> = setOf("completed", "failed", "abandoned")

/**
 * SQLite-backed [WorkflowStateRepository]. Delegates each per-family capability
 * interface to a small implementer sharing the connection, so no single class
 * crosses the detekt `TooManyFunctions` threshold.
 */
class WorkflowStateStore(
  private val connection: Connection,
) : WorkflowStateRepository,
  FeatureTaskWorkflowStateRepository by FeatureTaskWorkflowStateStore(connection),
  FeatureImplementWorkflowStateRepository by FeatureImplementWorkflowStateStore(connection),
  FeatureVerifyWorkflowStateRepository by FeatureVerifyWorkflowStateStore(connection),
  FeatureTaskRuntimeWorkflowStateRepository by FeatureTaskRuntimeWorkflowStateStore(connection)

private class FeatureTaskWorkflowStateStore(
  private val connection: Connection,
) : FeatureTaskWorkflowStateRepository {
  override fun saveFeatureTaskWorkflow(row: WorkflowStateRecord, mode: FeatureTaskWorkflowMode) {
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
}

private class FeatureImplementWorkflowStateStore(
  private val connection: Connection,
) : FeatureImplementWorkflowStateRepository {
  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    connection.upsertFeatureTaskWorkflowRow(
      row = row,
      mode = FeatureTaskWorkflowMode.PROSE,
      implementationSkill = row.implementationSkill.orEmpty().ifBlank {
        FeatureTaskWorkflowMode.PROSE.defaultImplementationSkill
      },
      defaultContractVersion = FeatureTaskWorkflowMode.PROSE.defaultContractVersion,
    )
  }

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? =
    connection.getFeatureTaskWorkflowRowAsMode(workflowId, FeatureTaskWorkflowMode.PROSE)

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> =
    connection.listFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.PROSE, limit)

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = listFeatureImplementWorkflows(1).firstOrNull()

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
}

private class FeatureTaskRuntimeWorkflowStateStore(
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

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    connection.listFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.RUNTIME, limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()
}

private class FeatureVerifyWorkflowStateStore(
  private val connection: Connection,
) : FeatureVerifyWorkflowStateRepository {
  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) {
    connection.upsertWorkflowRow(
      tableName = "feature_verify_workflows",
      row = row,
      defaultContractVersion = DbConstants.FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION,
    )
  }

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? =
    connection.getWorkflowRow("feature_verify_workflows", workflowId)

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> =
    connection.listWorkflowRows("feature_verify_workflows", limit)

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = listFeatureVerifyWorkflows(1).firstOrNull()

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
        WHEN excluded.workflow_status IN ('completed', 'failed', 'abandoned')
          THEN COALESCE(NULLIF(excluded.finished_at, ''), CURRENT_TIMESTAMP)
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
    statement.executeUpdate()
  }
}

private val FeatureTaskWorkflowMode.defaultImplementationSkill: String
  get() = when (this) {
    FeatureTaskWorkflowMode.PROSE -> "bill-feature-task-prose"
    FeatureTaskWorkflowMode.RUNTIME -> "bill-feature-task-runtime"
  }

private val FeatureTaskWorkflowMode.defaultContractVersion: String
  get() = when (this) {
    FeatureTaskWorkflowMode.PROSE -> DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION
    FeatureTaskWorkflowMode.RUNTIME -> DbConstants.FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION
  }

private fun Connection.upsertFeatureTaskWorkflowRow(
  row: WorkflowStateRecord,
  mode: FeatureTaskWorkflowMode,
  implementationSkill: String,
  defaultContractVersion: String,
) {
  prepareStatement(
    """
    INSERT INTO feature_task_workflows (
      workflow_id,
      session_id,
      workflow_name,
      contract_version,
      workflow_status,
      current_step_id,
      steps_json,
      artifacts_json,
      finished_at,
      mode,
      implementation_skill
    ) VALUES (?, ?, 'bill-feature-task', ?, ?, ?, ?, ?, CASE WHEN ? THEN COALESCE(NULLIF(?, ''), CURRENT_TIMESTAMP) ELSE NULL END, ?, ?)
    ON CONFLICT(workflow_id) DO UPDATE SET
      session_id = excluded.session_id,
      workflow_name = 'bill-feature-task',
      contract_version = excluded.contract_version,
      workflow_status = excluded.workflow_status,
      current_step_id = excluded.current_step_id,
      steps_json = excluded.steps_json,
      artifacts_json = excluded.artifacts_json,
      updated_at = CURRENT_TIMESTAMP,
      finished_at = CASE
        WHEN excluded.workflow_status IN ('completed', 'failed', 'abandoned')
          THEN COALESCE(NULLIF(excluded.finished_at, ''), CURRENT_TIMESTAMP)
        ELSE NULL
      END,
      mode = excluded.mode,
      implementation_skill = excluded.implementation_skill
    """.trimIndent(),
  ).use { statement ->
    val terminal = row.workflowStatus in terminalWorkflowStatuses
    statement.setString(WORKFLOW_ID_PARAMETER_INDEX, row.workflowId)
    statement.setString(SESSION_ID_PARAMETER_INDEX, row.sessionId)
    statement.setString(CONTRACT_VERSION_PARAMETER_INDEX - 1, row.contractVersion.ifBlank { defaultContractVersion })
    statement.setString(WORKFLOW_STATUS_PARAMETER_INDEX - 1, row.workflowStatus)
    statement.setString(CURRENT_STEP_ID_PARAMETER_INDEX - 1, row.currentStepId)
    statement.setString(STEPS_JSON_PARAMETER_INDEX - 1, row.stepsJson)
    statement.setString(ARTIFACTS_JSON_PARAMETER_INDEX - 1, row.artifactsJson)
    statement.setBoolean(INSERT_TERMINAL_PARAMETER_INDEX - 1, terminal)
    statement.setString(INSERT_FINISHED_AT_PARAMETER_INDEX - 1, row.finishedAt)
    statement.setString(FEATURE_TASK_MODE_PARAMETER_INDEX - 2, mode.wireValue)
    statement.setString(FEATURE_TASK_IMPLEMENTATION_SKILL_PARAMETER_INDEX - 2, implementationSkill)
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

private fun Connection.getFeatureTaskWorkflowRow(workflowId: String): WorkflowStateRecord? = prepareStatement(
  """
      SELECT
        workflow_id,
        session_id,
        workflow_name,
        mode,
        implementation_skill,
        contract_version,
        workflow_status,
        current_step_id,
        steps_json,
        artifacts_json,
        started_at,
        updated_at,
        finished_at
      FROM feature_task_workflows
      WHERE workflow_id = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.executeQuery().use { resultSet ->
    if (!resultSet.next()) {
      return null
    }
    resultSet.toFeatureTaskWorkflowStateRecord()
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

private fun Connection.getFeatureTaskWorkflowRowAsMode(
  workflowId: String,
  mode: FeatureTaskWorkflowMode,
): WorkflowStateRecord? {
  val row = getFeatureTaskWorkflowRow(workflowId) ?: return null
  if (row.mode != mode) {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task workflow '$workflowId' is mode='${row.mode?.wireValue.orEmpty()}', not '${mode.wireValue}'.",
    )
  }
  return row
}

private fun Connection.listFeatureTaskWorkflowRows(
  mode: FeatureTaskWorkflowMode,
  limit: Int,
): List<WorkflowStateRecord> {
  val normalizedLimit = limit.coerceAtLeast(0)
  return prepareStatement(
    """
    SELECT
      workflow_id,
      session_id,
      workflow_name,
      mode,
      implementation_skill,
      contract_version,
      workflow_status,
      current_step_id,
      steps_json,
      artifacts_json,
      started_at,
      updated_at,
      finished_at
    FROM feature_task_workflows
    WHERE mode = ?
    ORDER BY updated_at DESC, rowid DESC
    LIMIT ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, mode.wireValue)
    statement.setInt(2, normalizedLimit)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(resultSet.toFeatureTaskWorkflowStateRecord())
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

private fun java.sql.ResultSet.toFeatureTaskWorkflowStateRecord(): WorkflowStateRecord {
  val workflowId = getString("workflow_id")
  val workflowName = getString("workflow_name")
  if (workflowName != "bill-feature-task") {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task workflow '$workflowId' must persist workflow_name='bill-feature-task'; found '$workflowName'.",
    )
  }
  val rawMode = getString("mode")
  val mode = FeatureTaskWorkflowMode.fromWireValue(rawMode)
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task workflow '$workflowId' has unknown mode '$rawMode'.",
    )
  return WorkflowStateRecord(
    workflowId = workflowId,
    sessionId = getString("session_id"),
    workflowName = workflowName,
    contractVersion = getString("contract_version"),
    workflowStatus = getString("workflow_status"),
    currentStepId = getString("current_step_id"),
    stepsJson = getString("steps_json"),
    artifactsJson = getString("artifacts_json"),
    startedAt = getString("started_at"),
    updatedAt = getString("updated_at"),
    finishedAt = getString("finished_at"),
    mode = mode,
    implementationSkill = getString("implementation_skill"),
  )
}

private fun decodeStringList(rawValue: String?): List<String> =
  JsonSupport.parseArrayOrEmpty(rawValue.orEmpty()).mapNotNull { element -> element as? String }
