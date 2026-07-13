package skillbill.db.workflow

import skillbill.db.core.DbConstants
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.FeatureImplementWorkflowStateRepository
import skillbill.ports.persistence.FeatureTaskRuntimeWorkflowStateRepository
import skillbill.ports.persistence.FeatureTaskWorkflowStateRepository
import skillbill.ports.persistence.FeatureVerifyWorkflowStateRepository
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import java.sql.Connection

typealias WorkflowStateRow = WorkflowStateRecord

private const val WORKFLOW_ID_PARAMETER_INDEX: Int = 1

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
  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_execution_identities (
        workflow_id, contract_version, normalized_issue_key, repository_identity,
        governed_spec_path, mode, route_scope
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id) DO NOTHING
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, identity.workflowId)
      statement.setString(2, identity.contractVersion)
      statement.setString(3, identity.normalizedIssueKey)
      statement.setString(4, identity.repositoryIdentity)
      statement.setString(5, identity.governedSpecPath)
      statement.setString(6, identity.mode.wireValue)
      statement.setString(7, identity.routeScope.wireValue)
      statement.executeUpdate()
    }
    val persisted = connection.featureTaskIdentity(identity.workflowId)
      ?: error("Feature-task identity '${identity.workflowId}' was not persisted.")
    require(persisted == identity) {
      "Immutable feature-task execution identity conflicts for workflow '${identity.workflowId}'."
    }
  }

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
  ): List<FeatureTaskWorkflowCandidate> = connection.prepareStatement(
    """
    SELECT workflows.workflow_id
    FROM feature_task_workflows AS workflows
    LEFT JOIN feature_task_execution_identities AS identities
      ON identities.workflow_id = workflows.workflow_id
    WHERE UPPER(workflows.issue_key) = ?
    ORDER BY identities.created_at, workflows.workflow_id
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, normalizedIssueKey)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          val workflowId = rows.getString("workflow_id")
          val workflow = connection.getFeatureTaskWorkflowRow(workflowId)
            ?: error("Feature-task identity '$workflowId' has no workflow row.")
          add(FeatureTaskWorkflowCandidate(connection.featureTaskIdentity(workflowId), workflow))
        }
      }
    }
  }
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

private fun Connection.featureTaskIdentity(workflowId: String): FeatureTaskExecutionIdentity? = prepareStatement(
  """
  SELECT contract_version, normalized_issue_key, repository_identity, governed_spec_path, mode, route_scope
  FROM feature_task_execution_identities WHERE workflow_id = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.executeQuery().use { row ->
    if (!row.next()) return null
    FeatureTaskExecutionIdentity(
      workflowId = workflowId,
      contractVersion = row.getString("contract_version"),
      normalizedIssueKey = row.getString("normalized_issue_key"),
      repositoryIdentity = row.getString("repository_identity"),
      governedSpecPath = row.getString("governed_spec_path"),
      mode = FeatureTaskWorkflowMode.entries.single { it.wireValue == row.getString("mode") },
      routeScope = FeatureTaskRouteScope.entries.single { it.wireValue == row.getString("route_scope") },
    )
  }
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

  override fun getFeatureImplementWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    connection.getFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.PROSE, workflowIds)

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
          specInputTypes = decodeWorkflowStringList(resultSet.getString("spec_input_types")),
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

  override fun getFeatureTaskRuntimeWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    connection.getFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.RUNTIME, workflowIds)

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

  override fun getFeatureVerifyWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    connection.getWorkflowRows("feature_verify_workflows", workflowIds)

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
        issue_key,
        started_at,
        updated_at,
        state_entered_at,
        state_entered_at_estimated,
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
        issue_key,
        started_at,
        updated_at,
        state_entered_at,
        state_entered_at_estimated,
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

private fun Connection.getWorkflowRows(tableName: String, workflowIds: Set<String>): Map<String, WorkflowStateRecord> {
  if (workflowIds.isEmpty()) {
    return emptyMap()
  }
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
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
      finished_at
    FROM $tableName
    WHERE workflow_id IN (${workflowIds.workflowSqlPlaceholders()})
    """.trimIndent(),
  ).use { statement ->
    statement.bindWorkflowIdsForQuery(workflowIds)
    statement.executeQuery().use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          val row = resultSet.toWorkflowStateRecord()
          put(row.workflowId, row)
        }
      }
    }
  }
}

private fun Connection.getFeatureTaskWorkflowRows(
  mode: FeatureTaskWorkflowMode,
  workflowIds: Set<String>,
): Map<String, WorkflowStateRecord> {
  if (workflowIds.isEmpty()) {
    return emptyMap()
  }
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
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
      finished_at
    FROM feature_task_workflows
    WHERE mode = ? AND workflow_id IN (${workflowIds.workflowSqlPlaceholders()})
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, mode.wireValue)
    statement.bindWorkflowIdsForQuery(workflowIds, startIndex = 2)
    statement.executeQuery().use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          val row = resultSet.toFeatureTaskWorkflowStateRecord()
          put(row.workflowId, row)
        }
      }
    }
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
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
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
      issue_key,
      started_at,
      updated_at,
      state_entered_at,
      state_entered_at_estimated,
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
  issueKey = getString("issue_key"),
  startedAt = getString("started_at"),
  updatedAt = getString("updated_at"),
  stateEnteredAt = getString("state_entered_at"),
  stateEnteredAtEstimated = getInt("state_entered_at_estimated") != 0,
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
    issueKey = getString("issue_key"),
    startedAt = getString("started_at"),
    updatedAt = getString("updated_at"),
    stateEnteredAt = getString("state_entered_at"),
    stateEnteredAtEstimated = getInt("state_entered_at_estimated") != 0,
    finishedAt = getString("finished_at"),
    mode = mode,
    implementationSkill = getString("implementation_skill"),
  )
}
