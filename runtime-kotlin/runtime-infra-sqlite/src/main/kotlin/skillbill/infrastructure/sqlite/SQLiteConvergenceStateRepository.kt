package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.ConvergenceStateRepository
import skillbill.ports.persistence.ConvergenceReplayConflictException
import skillbill.ports.persistence.model.LegacyReconciliation
import skillbill.workflow.taskruntime.ConvergenceStateCodec
import skillbill.workflow.taskruntime.ConvergenceStateSchemaValidator
import skillbill.workflow.taskruntime.model.CONVERGENCE_STATE_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import skillbill.workflow.taskruntime.model.ReplayResult
import skillbill.workflow.taskruntime.model.UnresolvedConvergence
import skillbill.workflow.taskruntime.model.currentByLogicalIdentity
import java.sql.Connection
import java.sql.SQLException

class SQLiteConvergenceStateRepository(
  private val connection: Connection,
  private val schemaValidator: ConvergenceStateSchemaValidator = bundledConvergenceStateSchemaValidator(),
) : ConvergenceStateRepository {
  override fun append(record: ConvergenceRecord): ReplayResult {
    schemaValidator.validate(
      ConvergenceStateCodec.encodeRecord(record),
      "feature_task_convergence_records:${record.recordId}",
    )
    val expectedRecordId = ConvergenceIdentities.record(
      record.provenance.workflowId,
      record.kind,
      record.logicalId,
      record.provenance.generation,
    )
    require(record.recordId == expectedRecordId) { "Convergence record identity is not deterministic." }
    val parentRecord = connection.requireConvergenceParentRelationship(
      record,
      history(record.provenance.workflowId),
    )
    connection.findConvergenceByIdentity(record)?.let { existing ->
      return existing.replayAgainst(record)
    }
    try {
      connection.prepareStatement(
        """
        INSERT INTO feature_task_convergence_records(
          record_id, contract_version, workflow_id, record_kind, generation, logical_id,
          parent_logical_id, parent_record_id, phase_id, attempt, review_pass, record_status,
          classification, summary, affected_path, evidence_digest, evidence_ref, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        val provenance = record.provenance
        listOf(
          record.recordId,
          CONVERGENCE_STATE_CONTRACT_VERSION,
          provenance.workflowId,
          record.kind.name,
          provenance.generation,
          record.logicalId,
          record.parentLogicalId,
          parentRecord?.recordId,
          provenance.phaseId,
          provenance.attempt,
          provenance.reviewPass,
          record.status.name,
          record.classification,
          record.summary,
          record.path,
          record.evidenceDigest,
          record.evidenceRef,
          record.createdAt,
        ).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
      }
    } catch (error: SQLException) {
      connection.findConvergenceByIdentity(record)?.let { existing ->
        return existing.replayAgainst(record)
      }
      throw error
    }
    return ReplayResult.Appended(record)
  }

  override fun history(workflowId: String): List<ConvergenceRecord> = connection.readConvergenceHistory(workflowId)

  override fun current(workflowId: String): Map<String, ConvergenceRecord> =
    history(workflowId).currentByLogicalIdentity()

  override fun unresolved(workflowId: String): UnresolvedConvergence {
    val history = history(workflowId)
    val current = history.currentByLogicalIdentity().values
    val terminalParentGenerations = current.filter {
      it.kind in setOf(ConvergenceRecordKind.AUDIT_REPAIR, ConvergenceRecordKind.REVIEW_DISPOSITION) &&
        it.status.isTerminalDisposition()
    }.mapNotNull { record -> record.parentLogicalId?.let { it to record.provenance.generation } }
      .groupingBy { it.first }
      .fold(0) { generation, entry -> maxOf(generation, entry.second) }
    fun open(kind: ConvergenceRecordKind) = current
      .filter {
        it.kind == kind &&
          it.status == ConvergenceStatus.OPEN &&
          (terminalParentGenerations[it.logicalId] ?: 0) < it.provenance.generation
      }
      .sortedWith(compareBy({ it.provenance.generation }, ConvergenceRecord::createdAt, ConvergenceRecord::recordId))
    val reviewBlockers = current.filter {
        it.kind == ConvergenceRecordKind.REVIEW_FINDING &&
        it.status == ConvergenceStatus.OPEN &&
        it.classification == REVIEW_BLOCKER_CLASSIFICATION &&
        (terminalParentGenerations[it.logicalId] ?: 0) < it.provenance.generation
    }.sortedWith(compareBy({ it.provenance.generation }, ConvergenceRecord::createdAt, ConvergenceRecord::recordId))
    return UnresolvedConvergence(
      implementationObligations = open(ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION),
      auditRepairs = (
        open(ConvergenceRecordKind.AUDIT_GAP) +
          open(ConvergenceRecordKind.AUDIT_REPAIR)
        ).sortedWith(
        compareBy({ it.provenance.generation }, ConvergenceRecord::createdAt, ConvergenceRecord::recordId),
      ),
      reviewBlockers = reviewBlockers,
    )
  }

  override fun reconcileLegacy(workflowId: String, sourceDigest: String, encodedSource: String): LegacyReconciliation {
    if (connection.hasLegacyReconciliation(workflowId, sourceDigest)) {
      return LegacyReconciliation.AlreadyImported
    }
    val decoded = decodeLegacyConvergence(encodedSource, sourceDigest, schemaValidator)
    val records = decoded.records
    val quarantineReason = decoded.failureReason ?: connection.legacyQuarantineReason(workflowId, records)
    if (quarantineReason != null) {
      val result = LegacyReconciliation.Quarantined(quarantineReason)
      connection.recordLegacyReconciliation(workflowId, sourceDigest, result)
      return result
    }
    val replayConflict = records.sortedBy { expectedConvergenceParentKind(it.kind) != null }
      .any { append(it) is ReplayResult.Conflict }
    if (replayConflict) {
      throw ConvergenceReplayConflictException(records.first().recordId)
    }
    val result = LegacyReconciliation.Imported(records.size)
    connection.recordLegacyReconciliation(workflowId, sourceDigest, result)
    return result
  }
}

private const val REVIEW_BLOCKER_CLASSIFICATION = "blocker"

private fun ConvergenceStatus.isTerminalDisposition(): Boolean =
  this in setOf(ConvergenceStatus.RESOLVED, ConvergenceStatus.COMPLETED)

private fun ConvergenceRecord.replayAgainst(proposed: ConvergenceRecord): ReplayResult =
  if (this == proposed) ReplayResult.Identical(this) else ReplayResult.Conflict(this, proposed)
