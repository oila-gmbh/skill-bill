package skillbill.infrastructure.sqlite

import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import java.sql.Connection
import java.sql.ResultSet

internal fun Connection.readConvergenceHistory(workflowId: String): List<ConvergenceRecord> = prepareStatement(
  """
    SELECT * FROM feature_task_convergence_records
    WHERE workflow_id = ?
    ORDER BY generation, created_at, record_id
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.executeQuery().use { results ->
    buildList { while (results.next()) add(results.toConvergenceRecord()) }
  }
}

internal fun Connection.findConvergenceByIdentity(record: ConvergenceRecord): ConvergenceRecord? = prepareStatement(
  """
    SELECT * FROM feature_task_convergence_records
    WHERE record_id = ? OR (
      workflow_id = ? AND record_kind = ? AND generation = ? AND logical_id = ?
    )
    LIMIT 1
  """.trimIndent(),
).use { statement ->
  var index = 1
  statement.setString(index++, record.recordId)
  statement.setString(index++, record.provenance.workflowId)
  statement.setString(index++, record.kind.name)
  statement.setInt(index++, record.provenance.generation)
  statement.setString(index, record.logicalId)
  statement.executeQuery().use { result ->
    if (result.next()) result.toConvergenceRecord() else null
  }
}

internal fun Connection.requireConvergenceParentRelationship(
  record: ConvergenceRecord,
  history: List<ConvergenceRecord>,
): ConvergenceRecord? {
  val expectedParentKind = expectedConvergenceParentKind(record.kind) ?: return null
  val parent = history.lastOrNull {
    it.logicalId == record.parentLogicalId &&
      it.kind == expectedParentKind &&
      convergenceReviewPassIsCompatible(record, it)
  }
  require(parent != null) {
    "${record.kind.name} requires a durable ${expectedParentKind.name} parent identity."
  }
  return parent
}

internal fun expectedConvergenceParentKind(kind: ConvergenceRecordKind): ConvergenceRecordKind? = when (kind) {
  ConvergenceRecordKind.AUDIT_REPAIR -> ConvergenceRecordKind.AUDIT_GAP
  ConvergenceRecordKind.REVIEW_DISPOSITION -> ConvergenceRecordKind.REVIEW_FINDING
  else -> null
}

internal fun convergenceReviewPassIsCompatible(child: ConvergenceRecord, parent: ConvergenceRecord): Boolean =
  if (child.kind == ConvergenceRecordKind.REVIEW_DISPOSITION) {
    child.provenance.generation > parent.provenance.generation ||
      (
        child.provenance.generation == parent.provenance.generation &&
          requireNotNull(child.provenance.reviewPass) >= requireNotNull(parent.provenance.reviewPass)
        )
  } else {
    child.provenance.generation >= parent.provenance.generation &&
      child.provenance.reviewPass == parent.provenance.reviewPass
  }

private fun ResultSet.toConvergenceRecord() = ConvergenceRecord(
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
