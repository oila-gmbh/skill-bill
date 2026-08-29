package skillbill.db.workflow

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION
import skillbill.db.core.DbConstants
import skillbill.db.core.inImmediateTransaction
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.ProseFeatureTaskWorkflowWriteRefusedError
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.featuretask.model.FeatureTaskWorkflowCandidate
import skillbill.ports.goalrunner.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.FeatureImplementWorkflowStateRepository
import skillbill.ports.workflow.FeatureTaskRuntimeWorkerRepository
import skillbill.ports.workflow.FeatureTaskRuntimeWorkflowStateRepository
import skillbill.ports.workflow.FeatureTaskWorkflowStateRepository
import skillbill.ports.workflow.FeatureVerifyWorkflowStateRepository
import skillbill.ports.workflow.GoalChildWorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.FeatureVerifySessionSummary
import skillbill.ports.workflow.model.WorkflowStateRecord
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.format.DateTimeParseException

internal class FeatureTaskWorkflowStateStore(
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

  override fun deleteGoalChildWorkflow(
    parentWorkflowId: String,
    subtaskId: Int,
    workflowId: String,
    scope: GoalChildWorkflowDeletionScope,
  ): Int {
    val deletableStatuses = scope.deletableStatuses
    return connection.prepareStatement(
      """
        DELETE FROM feature_task_workflows
        WHERE workflow_id = ?
          AND workflow_status IN (${deletableStatuses.joinToString(", ") { "?" }})
          AND EXISTS (
            SELECT 1
            FROM feature_task_execution_identities AS identities
            WHERE identities.workflow_id = feature_task_workflows.workflow_id
              AND identities.route_scope = 'goal_child'
          )
          AND json_extract(artifacts_json, '$.goal_continuation.parent_workflow_id') = ?
          AND json_extract(artifacts_json, '$.goal_continuation.subtask_id') = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      deletableStatuses.forEachIndexed { offset, status ->
        statement.setString(DELETE_GOAL_CHILD_FIRST_STATUS_INDEX + offset, status)
      }
      statement.setString(DELETE_GOAL_CHILD_FIRST_STATUS_INDEX + deletableStatuses.size, parentWorkflowId)
      statement.setInt(DELETE_GOAL_CHILD_FIRST_STATUS_INDEX + deletableStatuses.size + 1, subtaskId)
      statement.executeUpdate()
    }
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

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int = connection.prepareStatement(
    """
    SELECT COUNT(*) AS child_count
    FROM feature_task_execution_identities
    WHERE normalized_issue_key = ? AND route_scope = 'goal_child'
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, normalizedIssueKey)
    statement.executeQuery().use { rows -> if (rows.next()) rows.getInt("child_count") else 0 }
  }

  internal fun findFeatureTaskCandidates(
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
          AND workflows.artifacts_json NOT LIKE '%"decomposition_runtime"%'
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
            ?: throw InvalidWorkflowStateSchemaError(
              "Feature-task identity '$workflowId' has no workflow row.",
            )
          add(FeatureTaskWorkflowCandidate(connection.featureTaskIdentity(workflowId), workflow))
        }
      }
    }
  }
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
