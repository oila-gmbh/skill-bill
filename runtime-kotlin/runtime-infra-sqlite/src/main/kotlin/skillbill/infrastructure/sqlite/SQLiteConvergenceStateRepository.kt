package skillbill.infrastructure.sqlite

import skillbill.ports.persistence.ConvergenceStateRepository
import skillbill.ports.persistence.LegacyReconciliation
import skillbill.workflow.taskruntime.CONVERGENCE_STATE_CONTRACT_VERSION
import skillbill.workflow.taskruntime.ConvergenceIdentities
import skillbill.workflow.taskruntime.ConvergenceProvenance
import skillbill.workflow.taskruntime.ConvergenceRecord
import skillbill.workflow.taskruntime.ConvergenceRecordKind
import skillbill.workflow.taskruntime.ConvergenceStateCodec
import skillbill.workflow.taskruntime.ConvergenceStatus
import skillbill.workflow.taskruntime.ReplayResult
import skillbill.workflow.taskruntime.UnresolvedConvergence
import skillbill.workflow.taskruntime.currentByLogicalIdentity
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

class SQLiteConvergenceStateRepository(private val connection: Connection) : ConvergenceStateRepository {
  override fun append(record: ConvergenceRecord): ReplayResult {
    val expectedRecordId = ConvergenceIdentities.record(record.logicalId, record.provenance.generation)
    require(record.recordId == expectedRecordId) { "Convergence record identity is not deterministic." }
    requireParentRelationship(record)
    findByIdentity(record)?.let { existing ->
      return if (existing == record) ReplayResult.Identical(existing) else ReplayResult.Conflict(existing, record)
    }
    try {
      connection.prepareStatement(
        """
        INSERT INTO feature_task_convergence_records(
          record_id, contract_version, workflow_id, record_kind, generation, logical_id,
          parent_logical_id, phase_id, attempt, review_pass, record_status, classification, summary,
          affected_path, evidence_digest, evidence_ref, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        val provenance = record.provenance
        listOf(
          record.recordId, CONVERGENCE_STATE_CONTRACT_VERSION, provenance.workflowId, record.kind.name,
          provenance.generation, record.logicalId, record.parentLogicalId, provenance.phaseId,
          provenance.attempt, provenance.reviewPass, record.status.name, record.classification, record.summary,
          record.path, record.evidenceDigest, record.evidenceRef, record.createdAt,
        ).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
      }
    } catch (error: SQLException) {
      findByIdentity(record)?.let { existing ->
        return if (existing == record) ReplayResult.Identical(existing) else ReplayResult.Conflict(existing, record)
      }
      throw error
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
    val history = history(workflowId)
    val current = history.currentByLogicalIdentity().values
    val resolvedParents = current.filter {
      it.kind in setOf(ConvergenceRecordKind.AUDIT_REPAIR, ConvergenceRecordKind.REVIEW_DISPOSITION) &&
        it.status in setOf(ConvergenceStatus.RESOLVED, ConvergenceStatus.COMPLETED)
    }.mapNotNullTo(mutableSetOf(), ConvergenceRecord::parentLogicalId)
    fun open(kind: ConvergenceRecordKind, classification: String? = null) = current.filter {
      it.kind == kind && it.status == ConvergenceStatus.OPEN && it.logicalId !in resolvedParents &&
        (classification == null || it.classification == classification)
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
    encodedSource: String,
  ): LegacyReconciliation {
    val existing = connection.prepareStatement(
      "SELECT disposition, reason_code FROM feature_task_convergence_legacy_imports WHERE workflow_id = ? AND source_digest = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, sourceDigest)
      statement.executeQuery().use { result -> if (result.next()) result.getString(1) to result.getString(2) else null }
    }
    if (existing != null) return LegacyReconciliation.AlreadyImported
    val records = try {
      ConvergenceStateCodec.decodeLegacySource(encodedSource, "legacy:$sourceDigest")
    } catch (error: skillbill.error.InvalidFeatureTaskRuntimeConvergenceStateSchemaError) {
      recordLegacy(workflowId, sourceDigest, "quarantined", "invalid_contract")
      return LegacyReconciliation.Quarantined("invalid_contract")
    }
    if (records.any { it.provenance.workflowId != workflowId }) {
      recordLegacy(workflowId, sourceDigest, "quarantined", "workflow_identity_mismatch")
      return LegacyReconciliation.Quarantined("workflow_identity_mismatch")
    }
    if (!legacyRelationshipsAreCompatible(workflowId, records)) {
      recordLegacy(workflowId, sourceDigest, "quarantined", "invalid_relationship")
      return LegacyReconciliation.Quarantined("invalid_relationship")
    }
    if (!legacyEvidenceIsCompatible(records) ||
      records.any { findByIdentity(it)?.let { existingRecord -> existingRecord != it } == true }
    ) {
      recordLegacy(workflowId, sourceDigest, "quarantined", "conflicting_evidence")
      return LegacyReconciliation.Quarantined("conflicting_evidence")
    }
    records.sortedBy { expectedParentKind(it.kind) != null }.forEach { record ->
      if (append(record) is ReplayResult.Conflict) {
        recordLegacy(workflowId, sourceDigest, "quarantined", "conflicting_evidence")
        return LegacyReconciliation.Quarantined("conflicting_evidence")
      }
    }
    recordLegacy(workflowId, sourceDigest, "imported", null)
    return LegacyReconciliation.Imported(records.size)
  }

  private fun legacyRelationshipsAreCompatible(workflowId: String, records: List<ConvergenceRecord>): Boolean {
    val availableParents = (history(workflowId) + records).groupBy(ConvergenceRecord::logicalId)
    return records.all { record ->
      val expectedParentKind = expectedParentKind(record.kind) ?: return@all true
      availableParents[record.parentLogicalId].orEmpty().any { it.kind == expectedParentKind }
    }
  }

  private fun legacyEvidenceIsCompatible(records: List<ConvergenceRecord>): Boolean {
    fun groupsAreIdentical(groups: Collection<List<ConvergenceRecord>>) =
      groups.all { group -> group.distinct().size == 1 }
    val recordIdGroups = records.groupBy(ConvergenceRecord::recordId).values
    val logicalIdentityGroups = records.groupBy {
      listOf(it.provenance.workflowId, it.kind.name, it.provenance.generation, it.logicalId)
    }
    return groupsAreIdentical(recordIdGroups) && groupsAreIdentical(logicalIdentityGroups.values)
  }

  private fun recordLegacy(workflowId: String, digest: String, disposition: String, reason: String?) {
    connection.prepareStatement(
      "INSERT INTO feature_task_convergence_legacy_imports(workflow_id, source_digest, disposition, reason_code) VALUES (?, ?, ?, ?)",
    ).use {
      it.setString(1, workflowId); it.setString(2, digest); it.setString(3, disposition); it.setString(4, reason)
      it.executeUpdate()
    }
  }

  private fun requireParentRelationship(record: ConvergenceRecord) {
    val expectedParentKind = expectedParentKind(record.kind) ?: return
    val parent = history(record.provenance.workflowId).lastOrNull {
      it.logicalId == record.parentLogicalId && it.kind == expectedParentKind
    }
    require(parent != null) {
      "${record.kind.name} requires a durable ${expectedParentKind.name} parent identity."
    }
  }

  private fun expectedParentKind(kind: ConvergenceRecordKind): ConvergenceRecordKind? = when (kind) {
    ConvergenceRecordKind.AUDIT_REPAIR -> ConvergenceRecordKind.AUDIT_GAP
    ConvergenceRecordKind.REVIEW_DISPOSITION -> ConvergenceRecordKind.REVIEW_FINDING
    else -> null
  }

  private fun findByIdentity(record: ConvergenceRecord): ConvergenceRecord? = connection.prepareStatement(
    """
    SELECT * FROM feature_task_convergence_records
    WHERE record_id = ? OR (workflow_id = ? AND record_kind = ? AND generation = ? AND logical_id = ?)
    LIMIT 1
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, record.recordId)
    statement.setString(2, record.provenance.workflowId)
    statement.setString(3, record.kind.name)
    statement.setInt(4, record.provenance.generation)
    statement.setString(5, record.logicalId)
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
  classification = getString("classification"),
  summary = getString("summary"),
  parentLogicalId = getString("parent_logical_id"),
  path = getString("affected_path"),
  evidenceRef = getString("evidence_ref"),
)
