package skillbill.infrastructure.sqlite.workflow

import skillbill.error.AuditRepairCycleConflictError
import skillbill.error.InvalidAuditRepairCycleSchemaError
import skillbill.infrastructure.sqlite.core.inImmediateTransaction
import skillbill.ports.featuretask.AuditRepairCycleRepository
import skillbill.ports.featuretask.model.AuditRepairStatusSnapshot
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairCycleCodec
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairLaunchBinding
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import skillbill.workflow.taskruntime.model.AuditRepairStage
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock

internal class AuditRepairCycleStore(
  private val connection: Connection,
  private val clock: Clock,
  private val transactionOwned: Boolean = false,
) : AuditRepairCycleRepository {
  private val launchStore = AuditRepairLaunchStore(connection, clock)

  private fun <T> writeTransaction(action: Connection.() -> T): T =
    if (transactionOwned) connection.action() else connection.inImmediateTransaction(action)

  override fun <T> withStageOwner(identity: AuditRepairIdentity, action: (AuditRepairCycleRepository) -> T): T =
    writeTransaction {
      val scoped = AuditRepairCycleStore(connection, clock, transactionOwned = true)
      scoped.validateStageOwner(identity)
      action(scoped)
    }

  override fun start(cycle: AuditRepairCycle): AuditRepairCycle = writeTransaction {
    cycle.validate()
    if (cycle.revisions.size != 1) {
      throw InvalidAuditRepairCycleSchemaError("A new cycle must contain only its diagnosis.")
    }
    launchStore.assertOwnership(cycle.identity)
    val existing = find(cycle.identity.workflowId, cycle.identity.cycleId)
    if (existing != null) {
      if (!sameDiagnosis(existing, cycle)) {
        throw AuditRepairCycleConflictError("Cycle identity already belongs to different diagnosis evidence.")
      }
      return@writeTransaction existing
    }
    val attemptExists = connection.prepareStatement(
      "SELECT 1 FROM audit_repair_cycles WHERE workflow_id = ? AND audit_attempt = ?",
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, cycle.identity.workflowId)
      statement.setInt(parameterIndex++, cycle.identity.auditAttempt)
      statement.executeQuery().use { it.next() }
    }
    if (attemptExists) {
      throw AuditRepairCycleConflictError("Audit attempt already has a durable diagnosis cycle.")
    }
    val activeCycleExists = connection.prepareStatement(
      "SELECT 1 FROM audit_repair_cycles WHERE workflow_id = ? AND stage <> 'satisfied' LIMIT 1",
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, cycle.identity.workflowId)
      statement.executeQuery().use { it.next() }
    }
    if (activeCycleExists) {
      throw AuditRepairCycleConflictError("An active audit-repair cycle already owns this workflow.")
    }
    connection.prepareStatement(
      "INSERT INTO audit_repair_cycles (workflow_id, cycle_id, audit_attempt, revision, stage) VALUES (?, ?, ?, ?, ?)",
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, cycle.identity.workflowId)
      statement.setString(parameterIndex++, cycle.identity.cycleId)
      statement.setInt(parameterIndex++, cycle.identity.auditAttempt)
      statement.setInt(parameterIndex++, cycle.current.revision)
      statement.setString(parameterIndex++, cycle.current.stage.wireValue)
      statement.executeUpdate()
    }
    insertEvidence(cycle)
    cycle
  }

  private fun sameDiagnosis(left: AuditRepairCycle, right: AuditRepairCycle): Boolean =
    left.identity.workflowId == right.identity.workflowId &&
      left.identity.auditAttempt == right.identity.auditAttempt &&
      left.identity.executionId == right.identity.executionId &&
      left.identity.sessionId == right.identity.sessionId &&
      left.identity.cycleId == right.identity.cycleId &&
      left.revisions.first().copy(recordedAt = "") == right.revisions.first().copy(recordedAt = "")

  override fun find(workflowId: String, cycleId: String): AuditRepairCycle? = readCycle(workflowId, cycleId, null)

  override fun findForAttempt(workflowId: String, auditAttempt: Int): AuditRepairCycle? =
    readCycle(workflowId, null, auditAttempt)

  override fun statusSnapshot(workflowId: String): AuditRepairStatusSnapshot? = connection.prepareStatement(
    "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = ? AND mode = 'runtime'",
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { rows ->
      if (rows.next()) AuditRepairStatusSnapshot(rows.getString(1), findActive(workflowId)) else null
    }
  }

  override fun findActive(workflowId: String): AuditRepairCycle? = connection.prepareStatement(
    "SELECT active_audit_cycle_id FROM feature_task_workflows WHERE workflow_id = ?",
  ).use { statement ->
    var parameterIndex = 1
    statement.setString(parameterIndex++, workflowId)
    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
  }?.let { cycleId -> readCycle(workflowId, cycleId, null) }

  override fun bindLaunch(binding: AuditRepairLaunchBinding): AuditRepairLaunchBinding =
    writeTransaction { launchStore.bindLaunch(binding) }

  override fun recordProviderSession(
    identity: AuditRepairIdentity,
    providerSessionId: String,
  ): AuditRepairLaunchBinding = writeTransaction { launchStore.recordProviderSession(identity, providerSessionId) }

  override fun findLaunchBinding(
    workflowId: String,
    executionId: String,
    requestedSessionId: String,
  ): AuditRepairLaunchBinding? = launchStore.findLaunchBinding(workflowId, executionId, requestedSessionId)

  override fun validateStageOwner(identity: AuditRepairIdentity) = launchStore.assertOwnership(identity)

  private fun readCycle(workflowId: String, cycleId: String?, auditAttempt: Int?): AuditRepairCycle? =
    connection.prepareStatement(
      """
      SELECT current.revision AS durable_revision, current.stage, evidence.evidence_json, current.audit_attempt,
        evidence.revision AS evidence_revision,
        current.cycle_id
      FROM audit_repair_cycles AS current
      LEFT JOIN audit_repair_cycle_revisions AS evidence
        ON evidence.workflow_id = current.workflow_id AND evidence.cycle_id = current.cycle_id
      WHERE current.workflow_id = ? AND (current.cycle_id = ? OR current.audit_attempt = ?)
      ORDER BY evidence.revision
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, workflowId)
      statement.setString(parameterIndex++, cycleId)
      statement.setObject(parameterIndex++, auditAttempt)
      statement.executeQuery().use { rows -> readRevisions(rows, workflowId, cycleId, auditAttempt) }
    }.also {
      assertNoOrphanedEvidence(workflowId)
    }

  private fun readRevisions(
    rows: ResultSet,
    workflowId: String,
    cycleId: String?,
    auditAttempt: Int?,
  ): AuditRepairCycle? {
    var restored: AuditRepairCycle? = null
    var durableRevision = -1
    var durableStage: String? = null
    while (rows.next()) {
      val evidence = rows.getString("evidence_json")
        ?: throw InvalidAuditRepairCycleSchemaError("Durable stage has no matching evidence.")
      val cycle = AuditRepairCycleCodec.decode(evidence)
      val previous = restored
      val expectedRevision = previous?.current?.revision?.plus(1) ?: 0
      validateRevisionIdentity(rows, cycle, Triple(workflowId, cycleId, auditAttempt), expectedRevision)
      assertPreservedHistory(previous, cycle)
      restored = cycle
      durableRevision = rows.getInt("durable_revision")
      durableStage = rows.getString("stage")
    }
    if (restored != null &&
      (restored.current.revision != durableRevision || restored.current.stage.wireValue != durableStage)
    ) {
      throw InvalidAuditRepairCycleSchemaError("Durable stage disagrees with its evidence.")
    }
    return restored
  }

  private fun assertPreservedHistory(previous: AuditRepairCycle?, cycle: AuditRepairCycle) {
    if (previous != null && !sameCycleHistory(cycle.copy(revisions = cycle.revisions.dropLast(1)), previous)) {
      throw InvalidAuditRepairCycleSchemaError("Durable audit revision rewrites earlier evidence.")
    }
  }

  private fun validateRevisionIdentity(
    rows: ResultSet,
    cycle: AuditRepairCycle,
    query: Triple<String, String?, Int?>,
    expectedRevision: Int,
  ) {
    val (workflowId, cycleId, auditAttempt) = query
    val matchesRequest = cycle.identity.workflowId == workflowId &&
      (cycleId == null || cycle.identity.cycleId == cycleId) &&
      (auditAttempt == null || cycle.identity.auditAttempt == auditAttempt)
    val matchesStorage = cycle.identity.cycleId == rows.getString("cycle_id") &&
      cycle.identity.auditAttempt == rows.getInt("audit_attempt") &&
      cycle.current.revision == rows.getInt("evidence_revision") && cycle.current.revision == expectedRevision
    if (!matchesRequest || !matchesStorage) {
      throw InvalidAuditRepairCycleSchemaError("Durable audit evidence has missing or mismatched revisions.")
    }
  }

  private fun assertNoOrphanedEvidence(workflowId: String) {
    val orphaned = connection.prepareStatement(
      """
      SELECT 1 FROM audit_repair_cycle_revisions AS evidence
      LEFT JOIN audit_repair_cycles AS current
        ON current.workflow_id = evidence.workflow_id AND current.cycle_id = evidence.cycle_id
      WHERE evidence.workflow_id = ? AND current.cycle_id IS NULL
      LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, workflowId)
      statement.executeQuery().use { it.next() }
    }
    if (orphaned) {
      throw InvalidAuditRepairCycleSchemaError("Durable audit evidence has no owning cycle.")
    }
  }

  override fun advance(
    identity: AuditRepairIdentity,
    expectedRevision: Int,
    revision: AuditRepairRevision,
  ): AuditRepairCycle = writeTransaction {
    launchStore.assertOwnership(identity, requireProviderSession = revision.stage != AuditRepairStage.PAUSED)
    val previous = find(identity.workflowId, identity.cycleId)
      ?: throw AuditRepairCycleConflictError("Cycle does not exist.")
    if (previous.identity.auditAttempt != identity.auditAttempt) {
      throw AuditRepairCycleConflictError("Stage caller does not own the original audit attempt.")
    }
    if (!sameExecutionBinding(previous.identity, identity)) {
      throw AuditRepairCycleConflictError("Stage caller does not own the original audit execution and session.")
    }
    val next = previous.append(expectedRevision, revision)
    if (next.revisions.size == previous.revisions.size) return@writeTransaction previous
    AuditRepairWorkflowControl(connection).advance(previous, next)
    insertEvidence(next)
    val updated = connection.prepareStatement(
      """
      UPDATE audit_repair_cycles SET revision = ?, stage = ?
      WHERE workflow_id = ? AND cycle_id = ? AND revision = ?
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setInt(parameterIndex++, next.current.revision)
      statement.setString(parameterIndex++, next.current.stage.wireValue)
      statement.setString(parameterIndex++, identity.workflowId)
      statement.setString(parameterIndex++, identity.cycleId)
      statement.setInt(parameterIndex++, expectedRevision)
      statement.executeUpdate()
    }
    if (updated != 1) throw AuditRepairCycleConflictError("Concurrent stage update rejected.")
    next
  }

  override fun attachCheckpoint(
    identity: AuditRepairIdentity,
    expectedRevision: Int,
    checkpointIntent: String,
    checkpoint: AuditRepairCheckpoint,
  ): AuditRepairCycle = writeTransaction {
    launchStore.assertOwnership(identity)
    val previous = find(identity.workflowId, identity.cycleId)
      ?: throw AuditRepairCycleConflictError("Cycle does not exist.")
    if (previous.identity.auditAttempt != identity.auditAttempt) {
      throw AuditRepairCycleConflictError("Checkpoint attachment does not own the original audit attempt.")
    }
    if (!sameExecutionBinding(previous.identity, identity)) {
      throw AuditRepairCycleConflictError(
        "Checkpoint attachment does not own the original audit execution and session.",
      )
    }
    val attached = previous.attachCheckpoint(expectedRevision, checkpointIntent, checkpoint)
    if (attached == previous) return@writeTransaction previous
    val updated = connection.prepareStatement(
      """
      UPDATE audit_repair_cycle_revisions SET evidence_json = ?
      WHERE workflow_id = ? AND cycle_id = ? AND revision = ?
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, AuditRepairCycleCodec.encode(attached))
      statement.setString(parameterIndex++, identity.workflowId)
      statement.setString(parameterIndex++, identity.cycleId)
      statement.setInt(parameterIndex++, previous.current.revision)
      statement.executeUpdate()
    }
    if (updated != 1) throw AuditRepairCycleConflictError("Concurrent checkpoint attachment rejected.")
    attached
  }

  private fun insertEvidence(cycle: AuditRepairCycle) {
    connection.prepareStatement(
      """
      INSERT INTO audit_repair_cycle_revisions (workflow_id, cycle_id, revision, evidence_json)
      VALUES (?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, cycle.identity.workflowId)
      statement.setString(parameterIndex++, cycle.identity.cycleId)
      statement.setInt(parameterIndex++, cycle.current.revision)
      statement.setString(parameterIndex++, AuditRepairCycleCodec.encode(cycle))
      statement.executeUpdate()
    }
    connection.enqueueAuditRepairTransition(cycle)
  }

  private fun sameCycleHistory(left: AuditRepairCycle, right: AuditRepairCycle): Boolean = left == right

  private fun sameExecutionBinding(left: AuditRepairIdentity, right: AuditRepairIdentity): Boolean =
    left.workflowId == right.workflowId &&
      left.auditAttempt == right.auditAttempt && left.executionId == right.executionId &&
      left.sessionId == right.sessionId && left.cycleId == right.cycleId
}
