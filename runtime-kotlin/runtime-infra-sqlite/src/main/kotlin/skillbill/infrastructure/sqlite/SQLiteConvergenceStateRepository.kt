package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.ConvergenceStateRepository
import skillbill.ports.persistence.LegacyReconciliation
import skillbill.workflow.taskruntime.CONVERGENCE_STATE_CONTRACT_VERSION
import skillbill.workflow.taskruntime.ConvergenceProvenance
import skillbill.workflow.taskruntime.ConvergenceRecord
import skillbill.workflow.taskruntime.ConvergenceRecordKind
import skillbill.workflow.taskruntime.ConvergenceStatus
import skillbill.workflow.taskruntime.ReplayResult
import skillbill.workflow.taskruntime.UnresolvedConvergence
import skillbill.workflow.taskruntime.currentByLogicalIdentity
import java.sql.Connection
import java.sql.ResultSet

class SQLiteConvergenceStateRepository(private val connection: Connection) : ConvergenceStateRepository {
  override fun append(record: ConvergenceRecord): ReplayResult {
    val existing = find(record.recordId)
    if (existing != null) return if (existing == record) ReplayResult.Identical(existing) else ReplayResult.Conflict(existing, record)
    connection.prepareStatement(
      """
      INSERT INTO feature_task_convergence_records(
        record_id, contract_version, workflow_id, record_kind, generation, logical_id,
        parent_logical_id, phase_id, attempt, review_pass, record_status, summary,
        affected_path, evidence_digest, evidence_ref, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      val provenance = record.provenance
      listOf(
        record.recordId, CONVERGENCE_STATE_CONTRACT_VERSION, provenance.workflowId, record.kind.name,
        provenance.generation, record.logicalId, record.parentLogicalId, provenance.phaseId,
        provenance.attempt, provenance.reviewPass, record.status.name, record.summary, record.path,
        record.evidenceDigest, record.evidenceRef, record.createdAt,
      ).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
      statement.executeUpdate()
    }
    return ReplayResult.Appended(record)
  }

  override fun history(workflowId: String): List<ConvergenceRecord> = connection.prepareStatement(
    "SELECT * FROM feature_task_convergence_records WHERE workflow_id = ? ORDER BY generation, created_at, record_id",
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { results -> buildList { while (results.next()) add(results.toRecord()) } }
  }

  override fun current(workflowId: String): Map<String, ConvergenceRecord> =
    history(workflowId).currentByLogicalIdentity()

  override fun unresolved(workflowId: String): UnresolvedConvergence {
    val current = current(workflowId).values
    fun open(kind: ConvergenceRecordKind, classification: String? = null) = current.filter {
      it.kind == kind && it.status == ConvergenceStatus.OPEN &&
        (classification == null || it.summary?.startsWith("$classification:") == true)
    }
    return UnresolvedConvergence(
      implementationObligations = open(ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION),
      auditRepairs = open(ConvergenceRecordKind.AUDIT_REPAIR),
      reviewBlockers = open(ConvergenceRecordKind.REVIEW_FINDING, "blocker"),
    )
  }

  override fun reconcileLegacy(
    workflowId: String,
    sourceDigest: String,
    records: List<ConvergenceRecord>,
  ): LegacyReconciliation {
    val existing = connection.prepareStatement(
      "SELECT disposition, reason_code FROM feature_task_convergence_legacy_imports WHERE workflow_id = ? AND source_digest = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, sourceDigest)
      statement.executeQuery().use { result -> if (result.next()) result.getString(1) to result.getString(2) else null }
    }
    if (existing != null) return LegacyReconciliation.AlreadyImported
    if (records.any { it.provenance.workflowId != workflowId }) {
      recordLegacy(workflowId, sourceDigest, "quarantined", "workflow_identity_mismatch")
      return LegacyReconciliation.Quarantined("workflow_identity_mismatch")
    }
    records.forEach { record ->
      if (append(record) is ReplayResult.Conflict) error("Legacy convergence record conflicts with durable evidence.")
    }
    recordLegacy(workflowId, sourceDigest, "imported", null)
    return LegacyReconciliation.Imported(records.size)
  }

  private fun recordLegacy(workflowId: String, digest: String, disposition: String, reason: String?) {
    connection.prepareStatement(
      "INSERT INTO feature_task_convergence_legacy_imports(workflow_id, source_digest, disposition, reason_code) VALUES (?, ?, ?, ?)",
    ).use {
      it.setString(1, workflowId); it.setString(2, digest); it.setString(3, disposition); it.setString(4, reason)
      it.executeUpdate()
    }
  }

  private fun find(recordId: String): ConvergenceRecord? = connection.prepareStatement(
    "SELECT * FROM feature_task_convergence_records WHERE record_id = ?",
  ).use { statement ->
    statement.setString(1, recordId)
    statement.executeQuery().use { result -> if (result.next()) result.toRecord() else null }
  }
}

private fun ResultSet.toRecord() = ConvergenceRecord(
  recordId = getString("record_id"),
  logicalId = getString("logical_id"),
  kind = ConvergenceRecordKind.valueOf(getString("record_kind")),
  provenance = ConvergenceProvenance(
    workflowId = getString("workflow_id"),
    generation = getInt("generation"),
    phaseId = getString("phase_id"),
    attempt = getObject("attempt")?.let { getInt("attempt") },
    reviewPass = getObject("review_pass")?.let { getInt("review_pass") },
  ),
  evidenceDigest = getString("evidence_digest"),
  createdAt = getString("created_at"),
  status = ConvergenceStatus.valueOf(getString("record_status")),
  summary = getString("summary"),
  parentLogicalId = getString("parent_logical_id"),
  path = getString("affected_path"),
  evidenceRef = getString("evidence_ref"),
)
