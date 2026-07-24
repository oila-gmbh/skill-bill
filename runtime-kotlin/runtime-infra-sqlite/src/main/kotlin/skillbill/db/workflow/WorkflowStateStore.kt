@file:Suppress("TooManyFunctions")

package skillbill.db.workflow

import skillbill.db.core.DbConstants
import skillbill.db.core.inImmediateTransaction
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.persistence.FeatureImplementWorkflowStateRepository
import skillbill.ports.persistence.FeatureTaskRuntimeWorkerRepository
import skillbill.ports.persistence.FeatureTaskRuntimeWorkflowStateRepository
import skillbill.ports.persistence.FeatureTaskWorkflowStateRepository
import skillbill.ports.persistence.FeatureVerifyWorkflowStateRepository
import skillbill.ports.persistence.GoalChildWorkflowStateRepository
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import java.sql.Connection
import java.time.Instant
import java.time.format.DateTimeParseException

typealias WorkflowStateRow = WorkflowStateRecord

private const val WORKFLOW_ID_PARAMETER_INDEX: Int = 1
private const val IDENTITY_WORKFLOW_ID_INDEX: Int = 1
private const val IDENTITY_CONTRACT_VERSION_INDEX: Int = 2
private const val IDENTITY_ISSUE_KEY_INDEX: Int = 3
private const val IDENTITY_REPOSITORY_INDEX: Int = 4
private const val IDENTITY_SPEC_PATH_INDEX: Int = 5
private const val IDENTITY_MODE_INDEX: Int = 6
private const val IDENTITY_ROUTE_SCOPE_INDEX: Int = 7
private const val CLAIM_WORKFLOW_ID_INDEX: Int = 1
private const val CLAIM_EXPECTED_UPDATED_AT_NULL_INDEX: Int = 2
private const val CLAIM_EXPECTED_UPDATED_AT_INDEX: Int = 3
private const val LOOKUP_WORKFLOW_ISSUE_KEY_INDEX: Int = 1
private const val LOOKUP_IDENTITY_ISSUE_KEY_INDEX: Int = 2
private const val LOOKUP_LEGACY_ROUTE_SCOPE_INDEX: Int = 3
private const val LOOKUP_REPOSITORY_IDENTITY_INDEX: Int = 4
private const val LOOKUP_ROUTE_SCOPE_INDEX: Int = 5
private const val MINIMUM_OWNER_TOKEN_LENGTH: Int = 16

/**
 * SQLite-backed [WorkflowStateRepository]. Delegates each per-family capability
 * interface to a small implementer sharing the connection, so no single class
 * crosses the detekt `TooManyFunctions` threshold.
 */
class WorkflowStateStore private constructor(
  connection: Connection,
  featureTaskStore: FeatureTaskWorkflowStateStore,
) : WorkflowStateRepository,
  FeatureTaskWorkflowStateRepository by featureTaskStore,
  GoalChildWorkflowStateRepository by featureTaskStore,
  FeatureTaskRuntimeWorkerRepository by featureTaskStore,
  FeatureImplementWorkflowStateRepository by FeatureImplementWorkflowStateStore(connection),
  FeatureVerifyWorkflowStateRepository by FeatureVerifyWorkflowStateStore(connection),
  FeatureTaskRuntimeWorkflowStateRepository by FeatureTaskRuntimeWorkflowStateStore(connection) {
  constructor(connection: Connection) : this(connection, FeatureTaskWorkflowStateStore(connection))
}

private class FeatureTaskWorkflowStateStore(
  private val connection: Connection,
) : FeatureTaskWorkflowStateRepository,
  GoalChildWorkflowStateRepository,
  FeatureTaskRuntimeWorkerRepository {
  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
    connection.featureTaskRuntimeWorkerOwnership(workflowId)

  override fun acquireFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean = connection.inImmediateTransaction {
    val claimed = prepareStatement(
      """
      UPDATE feature_task_workflows
      SET workflow_status = 'running', updated_at = CURRENT_TIMESTAMP
      WHERE workflow_id = ?
        AND mode = 'runtime'
        AND workflow_status NOT IN ('completed', 'failed', 'abandoned')
        AND NOT EXISTS (
          SELECT 1 FROM feature_task_runtime_worker_leases lease
          WHERE lease.workflow_id = feature_task_workflows.workflow_id
        )
        AND ((updated_at IS NULL AND ? IS NULL) OR updated_at = ?)
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, ownership.workflowId)
      statement.setString(parameterIndex++, expectedUpdatedAt)
      statement.setString(parameterIndex, expectedUpdatedAt)
      statement.executeUpdate() == 1
    }
    if (claimed) insertWorkerOwnership(ownership)
    claimed
  }

  override fun reserveFeatureTaskRuntimeWorkerTakeover(
    workflowId: String,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = connection.prepareStatement(
    """
    UPDATE feature_task_runtime_worker_leases
    SET lease_state = 'takeover_reserved'
    WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = 'active'
    """.trimIndent(),
  ).use { statement ->
    var parameterIndex = 1
    statement.setString(parameterIndex++, workflowId)
    statement.setString(parameterIndex++, expectedOwnerToken)
    statement.setLong(parameterIndex, expectedGeneration)
    statement.executeUpdate() == 1
  }

  override fun transferFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = connection.prepareStatement(
    """
    UPDATE feature_task_runtime_worker_leases SET
      contract_version = ?, generation = ?, owner_token = ?, host_identity = ?, boot_identity = ?,
      pid = ?, process_birth_token = ?, lease_state = ?, heartbeat_at = ?, expires_at = ?,
      phase_id = ?, phase_attempt = ?
    WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = 'takeover_reserved'
    """.trimIndent(),
  ).use { statement ->
    var parameterIndex = statement.bindOwnership(ownership, includeWorkflowId = false)
    statement.setString(parameterIndex++, ownership.workflowId)
    statement.setString(parameterIndex++, expectedOwnerToken)
    statement.setLong(parameterIndex, expectedGeneration)
    statement.executeUpdate() == 1
  }

  override fun heartbeatFeatureTaskRuntimeWorker(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    connection.prepareStatement(
      """
      UPDATE feature_task_runtime_worker_leases
      SET heartbeat_at = ?, expires_at = ?, phase_id = ?, phase_attempt = ?
      WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND lease_state = 'active'
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, ownership.heartbeatAt)
      statement.setString(parameterIndex++, ownership.expiresAt)
      statement.setString(parameterIndex++, ownership.phaseId)
      statement.setInt(parameterIndex++, ownership.phaseAttempt)
      statement.setString(parameterIndex++, ownership.workflowId)
      statement.setString(parameterIndex++, ownership.ownerToken)
      statement.setLong(parameterIndex, ownership.generation)
      statement.executeUpdate() == 1
    }

  override fun releaseFeatureTaskRuntimeWorker(workflowId: String, ownerToken: String, generation: Long): Boolean =
    connection.prepareStatement(
      "DELETE FROM feature_task_runtime_worker_leases WHERE workflow_id = ? AND owner_token = ? AND generation = ?",
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, workflowId)
      statement.setString(parameterIndex++, ownerToken)
      statement.setLong(parameterIndex, generation)
      statement.executeUpdate() == 1
    }

  override fun findFeatureTaskRuntimeCrashReconciliationCandidates(
    nowInstant: String,
  ): List<FeatureTaskRuntimeCrashReconciliationCandidate> = connection.prepareStatement(
    """
    SELECT workflows.workflow_id, workflows.current_step_id, workflows.workflow_status
    FROM feature_task_workflows AS workflows
    JOIN feature_task_runtime_worker_leases AS lease
      ON lease.workflow_id = workflows.workflow_id
    WHERE workflows.mode = 'runtime'
      AND workflows.workflow_status = 'running'
      AND lease.expires_at < ?
    ORDER BY workflows.workflow_id
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, nowInstant)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          val workflowId = rows.getString("workflow_id")
          val ownership = connection.featureTaskRuntimeWorkerOwnership(workflowId) ?: continue
          add(
            FeatureTaskRuntimeCrashReconciliationCandidate(
              ownership = ownership,
              currentStepId = rows.getString("current_step_id"),
              workflowStatus = rows.getString("workflow_status"),
            ),
          )
        }
      }
    }
  }

  // Composed inside the caller's UnitOfWork transaction (FeatureTaskRuntimeCrashReconciler and the
  // goal-parent recoverAndPersistTerminalOutcome each run this under database.transaction); it must
  // not open its own transaction, or the nested BEGIN IMMEDIATE would fail on real SQLite.
  override fun reconcileFeatureTaskRuntimeCrashedWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    interruptionReason: String,
    nowInstant: String,
  ): Boolean {
    val leaseReleased = connection.prepareStatement(
      """
      DELETE FROM feature_task_runtime_worker_leases
      WHERE workflow_id = ? AND owner_token = ? AND generation = ? AND expires_at < ?
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, workflowId)
      statement.setString(parameterIndex++, ownerToken)
      statement.setLong(parameterIndex++, generation)
      statement.setString(parameterIndex, nowInstant)
      statement.executeUpdate() == 1
    }
    if (!leaseReleased) return false
    // A concurrent writer may have moved the row out of 'running' between the candidate scan and
    // this write; that is a lost race, not a fault, so report no-op instead of asserting.
    return connection.prepareStatement(
      """
      UPDATE feature_task_workflows
      SET workflow_status = 'pending', interruption_reason = ?, updated_at = CURRENT_TIMESTAMP
      WHERE workflow_id = ? AND mode = 'runtime' AND workflow_status = 'running'
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, interruptionReason)
      statement.setString(parameterIndex, workflowId)
      statement.executeUpdate() == 1
    }
  }

  override fun claimFeatureTaskContinuation(workflowId: String, expectedUpdatedAt: String?): Boolean =
    connection.prepareStatement(
      """
      UPDATE feature_task_workflows
      SET workflow_status = 'running', updated_at = CURRENT_TIMESTAMP
      WHERE workflow_id = ?
        AND workflow_status NOT IN ('running', 'completed', 'failed', 'abandoned')
        AND ((updated_at IS NULL AND ? IS NULL) OR updated_at = ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(CLAIM_WORKFLOW_ID_INDEX, workflowId)
      statement.setString(CLAIM_EXPECTED_UPDATED_AT_NULL_INDEX, expectedUpdatedAt)
      statement.setString(CLAIM_EXPECTED_UPDATED_AT_INDEX, expectedUpdatedAt)
      statement.executeUpdate() == 1
    }

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
      statement.setString(IDENTITY_WORKFLOW_ID_INDEX, identity.workflowId)
      statement.setString(IDENTITY_CONTRACT_VERSION_INDEX, identity.contractVersion)
      statement.setString(IDENTITY_ISSUE_KEY_INDEX, identity.normalizedIssueKey)
      statement.setString(IDENTITY_REPOSITORY_INDEX, identity.repositoryIdentity)
      statement.setString(IDENTITY_SPEC_PATH_INDEX, identity.governedSpecPath)
      statement.setString(IDENTITY_MODE_INDEX, identity.mode.wireValue)
      statement.setString(IDENTITY_ROUTE_SCOPE_INDEX, identity.routeScope.wireValue)
      statement.executeUpdate()
    }
    val persisted = connection.featureTaskIdentity(identity.workflowId)
      ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(identity.workflowId, "identity was not persisted")
    if (persisted != identity) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        identity.workflowId,
        "immutable identity conflicts with the persisted record",
      )
    }
  }

  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    connection.featureTaskIdentity(workflowId)

  override fun deleteGoalChildWorkflowsByParent(parentWorkflowId: String): Int = connection.prepareStatement(
    """
      DELETE FROM feature_task_workflows
      WHERE workflow_id IN (
        SELECT workflows.workflow_id
        FROM feature_task_workflows AS workflows
        JOIN feature_task_execution_identities AS identities
          ON identities.workflow_id = workflows.workflow_id
        WHERE identities.route_scope = 'goal_child'
          AND json_extract(workflows.artifacts_json, '$.goal_continuation.parent_workflow_id') = ?
      )
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, parentWorkflowId)
    statement.executeUpdate()
  }

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = findFeatureTaskCandidates(
    normalizedIssueKey,
    repositoryIdentity,
    "standalone",
  )

  override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = findFeatureTaskCandidates(
    normalizedIssueKey,
    repositoryIdentity,
    "goal_child",
  )

  private fun findFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
    routeScope: String,
  ): List<FeatureTaskWorkflowCandidate> = connection.prepareStatement(
    """
    SELECT workflows.workflow_id
    FROM feature_task_workflows AS workflows
    LEFT JOIN feature_task_execution_identities AS identities
      ON identities.workflow_id = workflows.workflow_id
    WHERE (UPPER(workflows.issue_key) = ? OR identities.normalized_issue_key = ?)
      AND (
        (
          ? = 'standalone'
          AND identities.workflow_id IS NULL
          AND (
            workflows.mode = 'runtime'
            OR workflows.artifacts_json NOT LIKE '%"decomposition_runtime"%'
          )
        )
        OR (identities.repository_identity = ? AND identities.route_scope = ?)
      )
    ORDER BY identities.created_at, workflows.workflow_id
    """.trimIndent(),
  ).use { statement ->
    statement.setString(LOOKUP_WORKFLOW_ISSUE_KEY_INDEX, normalizedIssueKey)
    statement.setString(LOOKUP_IDENTITY_ISSUE_KEY_INDEX, normalizedIssueKey)
    statement.setString(LOOKUP_LEGACY_ROUTE_SCOPE_INDEX, routeScope)
    statement.setString(LOOKUP_REPOSITORY_IDENTITY_INDEX, repositoryIdentity)
    statement.setString(LOOKUP_ROUTE_SCOPE_INDEX, routeScope)
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

private fun Connection.insertWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
  prepareStatement(
    """
    INSERT INTO feature_task_runtime_worker_leases (
      workflow_id, contract_version, generation, owner_token, host_identity, boot_identity, pid,
      process_birth_token, lease_state, heartbeat_at, expires_at, phase_id, phase_attempt
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    statement.bindOwnership(ownership, includeWorkflowId = true)
    statement.executeUpdate()
  }
}

private fun java.sql.PreparedStatement.bindOwnership(
  ownership: FeatureTaskRuntimeWorkerOwnership,
  includeWorkflowId: Boolean,
): Int {
  var index = 1
  if (includeWorkflowId) setString(index++, ownership.workflowId)
  setString(index++, ownership.contractVersion)
  setLong(index++, ownership.generation)
  setString(index++, ownership.ownerToken)
  setString(index++, ownership.hostIdentity)
  setString(index++, ownership.bootIdentity)
  setLong(index++, ownership.pid)
  setString(index++, ownership.processBirthToken)
  setString(index++, ownership.leaseState.wireValue)
  setString(index++, ownership.heartbeatAt)
  setString(index++, ownership.expiresAt)
  setString(index++, ownership.phaseId)
  setInt(index++, ownership.phaseAttempt)
  return index
}

private fun Connection.featureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
  prepareStatement("SELECT * FROM feature_task_runtime_worker_leases WHERE workflow_id = ?").use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { row ->
      if (!row.next()) return null
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = row.requiredWorkerOwnershipString(workflowId, "workflow_id"),
        contractVersion = row.requiredWorkerOwnershipString(workflowId, "contract_version"),
        generation = row.getLong("generation"),
        ownerToken = row.requiredWorkerOwnershipString(workflowId, "owner_token"),
        hostIdentity = row.requiredWorkerOwnershipString(workflowId, "host_identity"),
        bootIdentity = row.requiredWorkerOwnershipString(workflowId, "boot_identity"),
        pid = row.getLong("pid"),
        processBirthToken = row.requiredWorkerOwnershipString(workflowId, "process_birth_token"),
        leaseState = decodeWorkerLeaseState(
          workflowId,
          row.requiredWorkerOwnershipString(workflowId, "lease_state"),
        ),
        heartbeatAt = row.requiredWorkerOwnershipString(workflowId, "heartbeat_at"),
        expiresAt = row.requiredWorkerOwnershipString(workflowId, "expires_at"),
        phaseId = row.requiredWorkerOwnershipString(workflowId, "phase_id"),
        phaseAttempt = row.getInt("phase_attempt"),
      ).also(::validateWorkerOwnership)
    }
  }

private fun java.sql.ResultSet.requiredWorkerOwnershipString(workflowId: String, column: String): String =
  getString(column) ?: throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
    workflowId,
    "$column is required",
  )

private fun decodeWorkerLeaseState(workflowId: String, value: String): FeatureTaskRuntimeWorkerLeaseState =
  FeatureTaskRuntimeWorkerLeaseState.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
      workflowId,
      "lease_state '$value' is not supported",
    )

private fun validateWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
  val heartbeatAt = parseOwnershipInstant(ownership, "heartbeat_at", ownership.heartbeatAt)
  val expiresAt = parseOwnershipInstant(ownership, "expires_at", ownership.expiresAt)
  val failure = when {
    ownership.contractVersion != skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION ->
      "unsupported contract_version '${ownership.contractVersion}'"
    ownership.generation < 1 -> "generation must be positive"
    ownership.ownerToken.length < MINIMUM_OWNER_TOKEN_LENGTH ->
      "owner_token must contain at least $MINIMUM_OWNER_TOKEN_LENGTH characters"
    ownership.hostIdentity.isBlank() || ownership.bootIdentity.isBlank() -> "host and boot identity are required"
    ownership.pid < 1 || ownership.processBirthToken.isBlank() -> "exact process identity is required"
    ownership.phaseId.isBlank() || ownership.phaseAttempt < 1 -> "phase coordinates are invalid"
    !expiresAt.isAfter(heartbeatAt) -> "expires_at must be later than heartbeat_at"
    else -> null
  }
  failure?.let { throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(ownership.workflowId, it) }
}

private fun parseOwnershipInstant(
  ownership: FeatureTaskRuntimeWorkerOwnership,
  field: String,
  value: String,
): Instant = try {
  Instant.parse(value)
} catch (_: DateTimeParseException) {
  throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
    ownership.workflowId,
    "$field must be an RFC 3339 instant",
  )
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
      mode = decodeIdentityMode(workflowId, row.getString("mode")),
      routeScope = decodeIdentityRouteScope(workflowId, row.getString("route_scope")),
    )
  }
}

private fun decodeIdentityMode(workflowId: String, value: String): FeatureTaskWorkflowMode =
  FeatureTaskWorkflowMode.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "mode '$value' is not supported")

private fun decodeIdentityRouteScope(workflowId: String, value: String): FeatureTaskRouteScope =
  FeatureTaskRouteScope.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "route_scope '$value' is not supported")

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
