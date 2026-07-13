package skillbill.db.workflow

import skillbill.db.core.DbConstants
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.WorkflowStateRecord
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val terminalWorkflowStatuses: Set<String> = setOf("completed", "failed", "abandoned")
private const val SQLITE_TIMESTAMP_NOW = "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"
private val sqliteInsertionTimestampFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

internal val FeatureTaskWorkflowMode.defaultImplementationSkill: String
  get() = when (this) {
    FeatureTaskWorkflowMode.PROSE -> "bill-feature-task-prose"
    FeatureTaskWorkflowMode.RUNTIME -> "bill-feature-task-runtime"
  }

internal val FeatureTaskWorkflowMode.defaultContractVersion: String
  get() = when (this) {
    FeatureTaskWorkflowMode.PROSE -> DbConstants.FEATURE_IMPLEMENT_WORKFLOW_CONTRACT_VERSION
    FeatureTaskWorkflowMode.RUNTIME -> DbConstants.FEATURE_TASK_RUNTIME_WORKFLOW_CONTRACT_VERSION
  }

internal fun Connection.upsertWorkflowRow(tableName: String, row: WorkflowStateRecord, defaultContractVersion: String) {
  val transitionTimestamp = nextStateEnteredAtSql(tableName)
  val insertionTimestamp = row.startedAt.orInsertionTimestamp()
  prepareStatement(
    """
    INSERT INTO $tableName (
      workflow_id, session_id, workflow_name, contract_version, workflow_status, current_step_id,
      steps_json, artifacts_json, issue_key, started_at, state_entered_at, state_entered_at_estimated, finished_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0,
              CASE WHEN ? THEN COALESCE(NULLIF(?, ''), CURRENT_TIMESTAMP) ELSE NULL END)
    ON CONFLICT(workflow_id) DO UPDATE SET
      session_id = excluded.session_id,
      workflow_name = excluded.workflow_name,
      contract_version = excluded.contract_version,
      workflow_status = excluded.workflow_status,
      current_step_id = excluded.current_step_id,
      steps_json = excluded.steps_json,
      artifacts_json = excluded.artifacts_json,
      issue_key = COALESCE(NULLIF(excluded.issue_key, ''), $tableName.issue_key),
      updated_at = CURRENT_TIMESTAMP,
      state_entered_at = CASE
        WHEN $tableName.workflow_status != excluded.workflow_status THEN $transitionTimestamp
        ELSE $tableName.state_entered_at
      END,
      state_entered_at_estimated = CASE
        WHEN $tableName.workflow_status != excluded.workflow_status THEN 0
        ELSE $tableName.state_entered_at_estimated
      END,
      finished_at = CASE
        WHEN excluded.workflow_status IN ('completed', 'failed', 'abandoned')
          THEN COALESCE(NULLIF(excluded.finished_at, ''), CURRENT_TIMESTAMP)
        ELSE NULL
      END
    """.trimIndent(),
  ).use { statement ->
    statement.bindWorkflowRow(row, defaultContractVersion, insertionTimestamp)
    statement.executeUpdate()
  }
}

internal fun Connection.upsertFeatureTaskWorkflowRow(
  row: WorkflowStateRecord,
  mode: FeatureTaskWorkflowMode,
  implementationSkill: String,
  defaultContractVersion: String,
) {
  val transitionTimestamp = nextStateEnteredAtSql("feature_task_workflows")
  val insertionTimestamp = row.startedAt.orInsertionTimestamp()
  prepareStatement(
    """
    INSERT INTO feature_task_workflows (
      workflow_id, session_id, workflow_name, contract_version, workflow_status, current_step_id,
      steps_json, artifacts_json, issue_key, started_at, state_entered_at, state_entered_at_estimated,
      finished_at, mode, implementation_skill
    ) VALUES (?, ?, 'bill-feature-task', ?, ?, ?, ?, ?, ?, ?, ?, 0,
              CASE WHEN ? THEN COALESCE(NULLIF(?, ''), CURRENT_TIMESTAMP) ELSE NULL END, ?, ?)
    ON CONFLICT(workflow_id) DO UPDATE SET
      session_id = excluded.session_id,
      workflow_name = 'bill-feature-task',
      contract_version = excluded.contract_version,
      workflow_status = excluded.workflow_status,
      current_step_id = excluded.current_step_id,
      steps_json = excluded.steps_json,
      artifacts_json = excluded.artifacts_json,
      issue_key = COALESCE(NULLIF(excluded.issue_key, ''), feature_task_workflows.issue_key),
      updated_at = CURRENT_TIMESTAMP,
      state_entered_at = CASE
        WHEN feature_task_workflows.workflow_status != excluded.workflow_status THEN $transitionTimestamp
        ELSE feature_task_workflows.state_entered_at
      END,
      state_entered_at_estimated = CASE
        WHEN feature_task_workflows.workflow_status != excluded.workflow_status THEN 0
        ELSE feature_task_workflows.state_entered_at_estimated
      END,
      finished_at = CASE
        WHEN excluded.workflow_status IN ('completed', 'failed', 'abandoned')
          THEN COALESCE(NULLIF(excluded.finished_at, ''), CURRENT_TIMESTAMP)
        ELSE NULL
      END,
      mode = excluded.mode,
      implementation_skill = excluded.implementation_skill
    """.trimIndent(),
  ).use { statement ->
    statement.bindFeatureTaskWorkflowRow(row, mode, implementationSkill, defaultContractVersion, insertionTimestamp)
    statement.executeUpdate()
  }
}

private fun PreparedStatement.bindWorkflowRow(
  row: WorkflowStateRecord,
  defaultContractVersion: String,
  insertionTimestamp: String,
) {
  val parameters = SqlParameterBinder(this)
  parameters.text(row.workflowId)
  parameters.text(row.sessionId)
  parameters.text(row.workflowName)
  parameters.text(row.contractVersion.ifBlank { defaultContractVersion })
  parameters.text(row.workflowStatus)
  parameters.text(row.currentStepId)
  parameters.text(row.stepsJson)
  parameters.text(row.artifactsJson)
  parameters.text(row.issueKey)
  parameters.text(insertionTimestamp)
  parameters.text(insertionTimestamp)
  parameters.boolean(row.workflowStatus in terminalWorkflowStatuses)
  parameters.text(row.finishedAt)
}

private fun PreparedStatement.bindFeatureTaskWorkflowRow(
  row: WorkflowStateRecord,
  mode: FeatureTaskWorkflowMode,
  implementationSkill: String,
  defaultContractVersion: String,
  insertionTimestamp: String,
) {
  val parameters = SqlParameterBinder(this)
  parameters.text(row.workflowId)
  parameters.text(row.sessionId)
  parameters.text(row.contractVersion.ifBlank { defaultContractVersion })
  parameters.text(row.workflowStatus)
  parameters.text(row.currentStepId)
  parameters.text(row.stepsJson)
  parameters.text(row.artifactsJson)
  parameters.text(row.issueKey)
  parameters.text(insertionTimestamp)
  parameters.text(insertionTimestamp)
  parameters.boolean(row.workflowStatus in terminalWorkflowStatuses)
  parameters.text(row.finishedAt)
  parameters.text(mode.wireValue)
  parameters.text(implementationSkill)
}

private fun nextStateEnteredAtSql(tableName: String): String =
  """
  CASE
    WHEN julianday(NULLIF($tableName.state_entered_at, '')) IS NULL THEN $SQLITE_TIMESTAMP_NOW
    WHEN julianday($SQLITE_TIMESTAMP_NOW) > julianday($tableName.state_entered_at) THEN $SQLITE_TIMESTAMP_NOW
    ELSE strftime('%Y-%m-%dT%H:%M:%fZ', julianday($tableName.state_entered_at) + 0.001 / 86400.0)
  END
  """.trimIndent()

private fun String?.orInsertionTimestamp(): String =
  takeUnless { it.isNullOrBlank() } ?: sqliteInsertionTimestampFormatter.format(Instant.now())

private class SqlParameterBinder(
  private val statement: PreparedStatement,
) {
  private var nextIndex = FIRST_PARAMETER_INDEX

  fun text(value: String?) {
    statement.setString(nextIndex, value)
    nextIndex += INDEX_INCREMENT
  }

  fun boolean(value: Boolean) {
    statement.setBoolean(nextIndex, value)
    nextIndex += INDEX_INCREMENT
  }
}

private const val FIRST_PARAMETER_INDEX = 1
private const val INDEX_INCREMENT = 1
