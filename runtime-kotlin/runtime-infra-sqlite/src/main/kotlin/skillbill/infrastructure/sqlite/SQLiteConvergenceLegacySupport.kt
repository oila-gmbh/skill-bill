package skillbill.infrastructure.sqlite

import skillbill.error.InvalidFeatureTaskRuntimeConvergenceStateSchemaError
import skillbill.ports.persistence.model.LegacyReconciliation
import skillbill.workflow.taskruntime.ConvergenceStateCodec
import skillbill.workflow.taskruntime.ConvergenceStateSchemaValidator
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import java.sql.Connection

internal const val CONFLICTING_EVIDENCE = "conflicting_evidence"

internal data class LegacyConvergenceDecode(
  val records: List<ConvergenceRecord>,
  val failureReason: String?,
)

internal fun decodeLegacyConvergence(
  encodedSource: String,
  sourceDigest: String,
  schemaValidator: ConvergenceStateSchemaValidator,
): LegacyConvergenceDecode = try {
  LegacyConvergenceDecode(
    records = ConvergenceStateCodec.decodeLegacySource(
      encodedSource,
      "legacy:$sourceDigest",
      schemaValidator,
    ),
    failureReason = null,
  )
} catch (error: InvalidFeatureTaskRuntimeConvergenceStateSchemaError) {
  LegacyConvergenceDecode(records = emptyList(), failureReason = error.toLegacyFailureReason())
}

internal fun Connection.legacyQuarantineReason(workflowId: String, records: List<ConvergenceRecord>): String? = when {
  records.any { it.provenance.workflowId != workflowId } -> "workflow_identity_mismatch"
  !legacyRelationshipsAreCompatible(readConvergenceHistory(workflowId), records) -> "invalid_relationship"
  !legacyEvidenceIsCompatible(records) -> CONFLICTING_EVIDENCE
  records.any { record -> findConvergenceByIdentity(record)?.let { it != record } == true } -> CONFLICTING_EVIDENCE
  else -> null
}

internal fun Connection.hasLegacyReconciliation(workflowId: String, sourceDigest: String): Boolean = prepareStatement(
  """
    SELECT 1
    FROM feature_task_convergence_legacy_imports
    WHERE workflow_id = ? AND source_digest = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, sourceDigest)
  statement.executeQuery().use { it.next() }
}

internal fun Connection.recordLegacyReconciliation(
  workflowId: String,
  sourceDigest: String,
  result: LegacyReconciliation,
) {
  val disposition = if (result is LegacyReconciliation.Imported) "imported" else "quarantined"
  val reason = (result as? LegacyReconciliation.Quarantined)?.reasonCode
  prepareStatement(
    """
    INSERT INTO feature_task_convergence_legacy_imports(
      workflow_id, source_digest, disposition, reason_code
    ) VALUES (?, ?, ?, ?)
    """.trimIndent(),
  ).use { statement ->
    var index = 1
    statement.setString(index++, workflowId)
    statement.setString(index++, sourceDigest)
    statement.setString(index++, disposition)
    statement.setString(index, reason)
    statement.executeUpdate()
  }
}

private fun legacyRelationshipsAreCompatible(
  existingHistory: List<ConvergenceRecord>,
  records: List<ConvergenceRecord>,
): Boolean {
  val availableParents = (existingHistory + records).groupBy(ConvergenceRecord::logicalId)
  return records.all { record ->
    val expectedParentKind = expectedConvergenceParentKind(record.kind) ?: return@all true
    availableParents[record.parentLogicalId].orEmpty().any {
      it.kind == expectedParentKind &&
        it.provenance.generation == record.provenance.generation &&
        convergenceReviewPassIsCompatible(record, it)
    }
  }
}

private fun legacyEvidenceIsCompatible(records: List<ConvergenceRecord>): Boolean {
  fun groupsAreIdentical(groups: Collection<List<ConvergenceRecord>>) =
    groups.all { group -> group.distinct().size == 1 }
  val recordIdGroups = records.groupBy(ConvergenceRecord::recordId).values
  val logicalIdentityGroups = records.groupBy {
    listOf(it.provenance.workflowId, it.kind.name, it.provenance.generation, it.logicalId)
  }.values
  return groupsAreIdentical(recordIdGroups) && groupsAreIdentical(logicalIdentityGroups)
}

private fun InvalidFeatureTaskRuntimeConvergenceStateSchemaError.toLegacyFailureReason(): String = INVALID_CONTRACT

private const val INVALID_CONTRACT = "invalid_contract"
