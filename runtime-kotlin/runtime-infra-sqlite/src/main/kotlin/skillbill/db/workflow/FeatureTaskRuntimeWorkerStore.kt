package skillbill.db.workflow

import skillbill.db.core.inImmediateTransaction
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.FeatureTaskRuntimeWorkerRepository
import java.sql.Connection

internal class FeatureTaskRuntimeWorkerStore(
  private val connection: Connection,
) : FeatureTaskRuntimeWorkerRepository {
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
}
