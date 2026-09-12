package skillbill.infrastructure.sqlite.workflow

import skillbill.error.AuditRepairCycleConflictError
import skillbill.workflow.taskruntime.model.AuditRepairCycleCodec
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairLaunchBinding
import java.sql.Connection
import java.time.Clock

internal class AuditRepairLaunchStore(
  private val connection: Connection,
  private val clock: Clock,
) {
  fun bindLaunch(binding: AuditRepairLaunchBinding): AuditRepairLaunchBinding = run {
    assertOwnership(binding.identity, requireLaunchBinding = false)
    val existing = launchBinding(binding.workflowId, binding.auditAttempt, binding.cycleId)
    if (existing != null) {
      assertActiveCycle(binding.identity)
      return@run rebind(existing, binding)
    }
    val activeCycle = connection.prepareStatement(
      "SELECT cycle_id FROM audit_repair_cycles WHERE workflow_id = ? AND stage <> 'satisfied' LIMIT 1",
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, binding.workflowId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
    if (activeCycle != null && activeCycle != binding.cycleId) {
      throw AuditRepairCycleConflictError("An active audit-repair cycle already owns this workflow.")
    }
    connection.prepareStatement(
      """
        INSERT INTO audit_repair_launch_bindings
          (workflow_id, audit_attempt, cycle_id, execution_id, requested_session_id,
           provider_session_id, owner_token, fencing_generation, bound_at, checkpoint_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, binding.workflowId)
      statement.setInt(parameterIndex++, binding.auditAttempt)
      statement.setString(parameterIndex++, binding.cycleId)
      statement.setString(parameterIndex++, binding.executionId)
      statement.setString(parameterIndex++, binding.requestedSessionId)
      statement.setString(parameterIndex++, binding.providerSessionId)
      statement.setString(parameterIndex++, binding.ownerToken)
      statement.setLong(parameterIndex++, binding.fencingGeneration)
      statement.setString(parameterIndex++, binding.boundAt)
      statement.setString(parameterIndex++, binding.checkpoint?.let(AuditRepairCycleCodec::encodeCheckpoint))
      statement.executeUpdate()
    }
    connection.prepareStatement(
      "UPDATE feature_task_workflows SET active_audit_cycle_id = ? WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, binding.cycleId)
      statement.setString(2, binding.workflowId)
      statement.executeUpdate()
    }
    binding
  }

  private fun rebind(existing: AuditRepairLaunchBinding, binding: AuditRepairLaunchBinding): AuditRepairLaunchBinding {
    if (existing.identity.copy(ownerToken = binding.ownerToken, fencingGeneration = binding.fencingGeneration)
      != binding.identity
    ) {
      throw AuditRepairCycleConflictError("Conflicting audit-repair launch binding.")
    }
    if (existing.checkpoint != null && binding.checkpoint != null && existing.checkpoint != binding.checkpoint) {
      throw AuditRepairCycleConflictError("Audit launch cannot replace its implementation checkpoint.")
    }
    connection.prepareStatement(
      "UPDATE audit_repair_launch_bindings SET owner_token = ?, fencing_generation = ?, " +
        "checkpoint_json = COALESCE(checkpoint_json, ?) " +
        "WHERE workflow_id = ? AND audit_attempt = ? AND cycle_id = ?",
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, binding.ownerToken)
      statement.setLong(parameterIndex++, binding.fencingGeneration)
      statement.setString(parameterIndex++, binding.checkpoint?.let(AuditRepairCycleCodec::encodeCheckpoint))
      statement.setString(parameterIndex++, binding.workflowId)
      statement.setInt(parameterIndex++, binding.auditAttempt)
      statement.setString(parameterIndex++, binding.cycleId)
      statement.executeUpdate()
    }
    return existing.copy(
      ownerToken = binding.ownerToken,
      fencingGeneration = binding.fencingGeneration,
      checkpoint = existing.checkpoint ?: binding.checkpoint,
    )
  }

  fun recordProviderSession(identity: AuditRepairIdentity, providerSessionId: String): AuditRepairLaunchBinding = run {
    assertOwnership(identity, requireBindingPresence = true, requireProviderSession = false)
    if (providerSessionId.isBlank()) throw AuditRepairCycleConflictError("Provider identity is blank.")
    val binding = launchBinding(identity.workflowId, identity.auditAttempt, identity.cycleId)
      ?: throw AuditRepairCycleConflictError("Audit-repair launch binding is absent.")
    if (binding.executionId != identity.executionId || binding.requestedSessionId != identity.sessionId) {
      throw AuditRepairCycleConflictError("Provider session does not match the launch binding.")
    }
    if (binding.providerSessionId != null && binding.providerSessionId != providerSessionId) {
      throw AuditRepairCycleConflictError("Provider session identity changed during a launch.")
    }
    if (binding.providerSessionId == null) {
      connection.prepareStatement(
        """
        UPDATE audit_repair_launch_bindings SET provider_session_id = ?
        WHERE workflow_id = ? AND audit_attempt = ? AND cycle_id = ?
        """.trimIndent(),
      ).use { statement ->
        var parameterIndex = 1
        statement.setString(parameterIndex++, providerSessionId)
        statement.setString(parameterIndex++, identity.workflowId)
        statement.setInt(parameterIndex++, identity.auditAttempt)
        statement.setString(parameterIndex++, identity.cycleId)
        if (statement.executeUpdate() != 1) throw AuditRepairCycleConflictError("Provider session binding was lost.")
      }
    }
    binding.copy(providerSessionId = providerSessionId)
  }

  fun findLaunchBinding(
    workflowId: String,
    executionId: String,
    requestedSessionId: String,
  ): AuditRepairLaunchBinding? = launchBindingByExecution(workflowId, executionId, requestedSessionId)

  private fun launchBinding(workflowId: String, auditAttempt: Int, cycleId: String): AuditRepairLaunchBinding? =
    connection.prepareStatement(
      "SELECT execution_id, requested_session_id, provider_session_id, " +
        "owner_token, fencing_generation, bound_at, checkpoint_json " +
        "FROM audit_repair_launch_bindings WHERE workflow_id = ? AND audit_attempt = ? AND cycle_id = ?",
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, workflowId)
      statement.setInt(parameterIndex++, auditAttempt)
      statement.setString(parameterIndex++, cycleId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) {
          null
        } else {
          AuditRepairLaunchBinding(
            workflowId = workflowId,
            auditAttempt = auditAttempt,
            cycleId = cycleId,
            executionId = rows.getString("execution_id"),
            requestedSessionId = rows.getString("requested_session_id"),
            providerSessionId = rows.getString("provider_session_id"),
            ownerToken = rows.getString("owner_token"),
            fencingGeneration = rows.getLong("fencing_generation"),
            boundAt = rows.getString("bound_at"),
            checkpoint = rows.getString("checkpoint_json")?.let(AuditRepairCycleCodec::decodeCheckpoint),
          )
        }
      }
    }

  private fun launchBindingByExecution(
    workflowId: String,
    executionId: String,
    requestedSessionId: String,
  ): AuditRepairLaunchBinding? = connection.prepareStatement(
    "SELECT audit_attempt, cycle_id, provider_session_id, owner_token, fencing_generation, bound_at, checkpoint_json " +
      "FROM audit_repair_launch_bindings WHERE workflow_id = ? AND execution_id = ? AND requested_session_id = ?",
  ).use { statement ->
    var parameterIndex = 1
    statement.setString(parameterIndex++, workflowId)
    statement.setString(parameterIndex++, executionId)
    statement.setString(parameterIndex++, requestedSessionId)
    statement.executeQuery().use { rows ->
      if (!rows.next()) {
        null
      } else {
        AuditRepairLaunchBinding(
          workflowId = workflowId,
          auditAttempt = rows.getInt("audit_attempt"),
          cycleId = rows.getString("cycle_id"),
          executionId = executionId,
          requestedSessionId = requestedSessionId,
          providerSessionId = rows.getString("provider_session_id"),
          ownerToken = rows.getString("owner_token"),
          fencingGeneration = rows.getLong("fencing_generation"),
          boundAt = rows.getString("bound_at"),
          checkpoint = rows.getString("checkpoint_json")?.let(AuditRepairCycleCodec::decodeCheckpoint),
        )
      }
    }
  }

  fun assertOwnership(
    identity: AuditRepairIdentity,
    requireLaunchBinding: Boolean = true,
    requireBindingPresence: Boolean = true,
    requireProviderSession: Boolean = true,
  ) {
    AuditRepairLeaseGuard(connection, clock).validate(identity)
    val binding = if (requireLaunchBinding) {
      launchBinding(identity.workflowId, identity.auditAttempt, identity.cycleId)
    } else {
      null
    }
    if (workflowSessionId(identity.workflowId)?.takeIf(String::isNotBlank)?.let {
        it != identity.sessionId && binding?.requestedSessionId != identity.sessionId
      } == true
    ) {
      throw AuditRepairCycleConflictError("Audit stage caller does not belong to the workflow session.")
    }
    if (requireLaunchBinding) {
      assertActiveCycle(identity)
      assertBinding(identity, binding, requireProviderSession, requireBindingPresence)
    }
  }

  private fun assertActiveCycle(identity: AuditRepairIdentity) {
    val activeCycle = connection.prepareStatement(
      "SELECT active_audit_cycle_id FROM feature_task_workflows WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, identity.workflowId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
    if (activeCycle != identity.cycleId) {
      throw AuditRepairCycleConflictError("Audit stage caller does not own the workflow's active cycle.")
    }
  }

  private fun assertBinding(
    identity: AuditRepairIdentity,
    binding: AuditRepairLaunchBinding?,
    requireProviderSession: Boolean,
    requireBindingPresence: Boolean,
  ) {
    val reason = when {
      binding != null && binding.identity != identity -> "Audit stage caller does not match its durable launch binding."
      requireProviderSession && binding != null && binding.providerSessionId.isNullOrBlank() ->
        "Audit stage caller has no durable provider session identity."
      binding == null && (requireBindingPresence || launchBindingExists(identity.workflowId)) ->
        "Audit stage caller has no matching durable launch binding."
      else -> null
    }
    if (reason != null) throw AuditRepairCycleConflictError(reason)
  }

  private fun workflowSessionId(workflowId: String): String? {
    val hasSessionId = connection.metaData
      .getColumns(null, null, "feature_task_workflows", "session_id")
      .use { it.next() }
    if (!hasSessionId) return null
    return connection.prepareStatement(
      "SELECT session_id FROM feature_task_workflows WHERE workflow_id = ?",
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, workflowId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
  }

  private fun launchBindingExists(workflowId: String): Boolean = connection.prepareStatement(
    "SELECT 1 FROM audit_repair_launch_bindings WHERE workflow_id = ? LIMIT 1",
  ).use { statement ->
    var parameterIndex = 1
    statement.setString(parameterIndex++, workflowId)
    statement.executeQuery().use { it.next() }
  }
}
