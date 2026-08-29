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

internal fun Connection.insertWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
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

internal fun PreparedStatement.bindOwnership(
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

internal fun Connection.featureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? =
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

internal fun ResultSet.requiredWorkerOwnershipString(workflowId: String, column: String): String =
  getString(column) ?: throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
    workflowId,
    "$column is required",
  )

internal fun decodeWorkerLeaseState(workflowId: String, value: String): FeatureTaskRuntimeWorkerLeaseState =
  FeatureTaskRuntimeWorkerLeaseState.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
      workflowId,
      "lease_state '$value' is not supported",
    )

internal fun validateWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
  val heartbeatAt = parseOwnershipInstant(ownership, "heartbeat_at", ownership.heartbeatAt)
  val expiresAt = parseOwnershipInstant(ownership, "expires_at", ownership.expiresAt)
  val failure = when {
    ownership.contractVersion != FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION ->
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

internal fun parseOwnershipInstant(
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

internal fun Connection.featureTaskIdentity(workflowId: String): FeatureTaskExecutionIdentity? = prepareStatement(
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

internal fun decodeIdentityMode(workflowId: String, value: String): FeatureTaskWorkflowMode =
  FeatureTaskWorkflowMode.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "mode '$value' is not supported")

internal fun decodeIdentityRouteScope(workflowId: String, value: String): FeatureTaskRouteScope =
  FeatureTaskRouteScope.entries.singleOrNull { it.wireValue == value }
    ?: throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "route_scope '$value' is not supported")
