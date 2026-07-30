package skillbill.infrastructure.sqlite.goal

import skillbill.ports.persistence.ConvergenceReplayConflictException
import skillbill.ports.persistence.ConvergenceStateRepository
import skillbill.workflow.taskruntime.model.CONVERGENCE_REFERENCE_MAX_LENGTH
import skillbill.workflow.taskruntime.model.CONVERGENCE_SUMMARY_MAX_LENGTH
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDispositionRecord
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGeneration
import skillbill.workflow.taskruntime.model.ReplayResult
import java.sql.Connection

internal class ReviewConvergenceRecorder(
  private val connection: Connection,
  private val convergence: ConvergenceStateRepository,
) {
  fun appendFinding(workflowId: String, generationId: String, passNumber: Int, finding: GoalSubtaskReviewFinding) {
    if (!connection.hasConvergenceWorkflow(workflowId)) return
    val generation = connection.reviewGenerationOrdinal(workflowId, generationId)
    val logicalId = ConvergenceIdentities.logical(
      workflowId,
      ConvergenceRecordKind.REVIEW_FINDING,
      finding.findingId,
    )
    val record = ConvergenceRecord(
      recordId = ConvergenceIdentities.record(
        workflowId,
        ConvergenceRecordKind.REVIEW_FINDING,
        logicalId,
        generation,
      ),
      logicalId = logicalId,
      kind = ConvergenceRecordKind.REVIEW_FINDING,
      provenance = ConvergenceProvenance(workflowId, generation, "review", reviewPass = passNumber),
      evidenceDigest = ConvergenceIdentities.digest(
        listOf(
          finding.findingId,
          finding.severity,
          finding.category,
          finding.location,
          finding.summary,
          finding.sourceGenerationId,
        ).joinToString("|"),
      ),
      createdAt = connection.reviewFindingCreatedAt(workflowId, finding.findingId),
      status = ConvergenceStatus.OPEN,
      classification = finding.severity,
      summary = finding.summary.take(CONVERGENCE_SUMMARY_MAX_LENGTH),
      path = finding.location.take(CONVERGENCE_REFERENCE_MAX_LENGTH),
    )
    convergence.appendOrThrow(record)
  }

  fun appendCheckpoint(generation: GoalSubtaskReviewGeneration) {
    val identity = generation.identity
    if (!connection.hasConvergenceWorkflow(identity.workflowId)) return
    val ordinal = connection.reviewGenerationOrdinal(identity.workflowId, identity.generationId)
    val logicalId = ConvergenceIdentities.logical(
      identity.workflowId,
      ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
      "review:${identity.generationId}",
    )
    val record = ConvergenceRecord(
      recordId = ConvergenceIdentities.record(
        identity.workflowId,
        ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
        logicalId,
        ordinal,
      ),
      logicalId = logicalId,
      kind = ConvergenceRecordKind.REPOSITORY_CHECKPOINT,
      provenance = ConvergenceProvenance(identity.workflowId, ordinal, "review"),
      evidenceDigest = ConvergenceIdentities.digest(
        listOf(
          identity.reviewBase,
          identity.reviewedDeltaDigest,
          identity.repositoryCheckpoint,
          generation.supersededByGenerationId.orEmpty(),
        ).joinToString("|"),
      ),
      createdAt = connection.reviewGenerationCreatedAt(identity.workflowId, identity.generationId),
      status = ConvergenceStatus.COMPLETED,
      summary = "review generation $ordinal repository checkpoint",
      evidenceRef = identity.repositoryCheckpoint.take(CONVERGENCE_REFERENCE_MAX_LENGTH),
    )
    convergence.appendOrThrow(record)
  }

  fun appendDisposition(record: GoalSubtaskReviewFindingDispositionRecord) {
    if (!connection.hasConvergenceWorkflow(record.workflowId)) return
    val generation = connection.reviewGenerationOrdinal(record.workflowId, record.generationId)
    val parentLogicalId = ConvergenceIdentities.logical(
      record.workflowId,
      ConvergenceRecordKind.REVIEW_FINDING,
      record.findingId,
    )
    val logicalId = ConvergenceIdentities.logical(
      record.workflowId,
      ConvergenceRecordKind.REVIEW_DISPOSITION,
      "${record.generationId}:${record.findingId}:${record.disposition.wireValue}",
    )
    val convergenceRecord = ConvergenceRecord(
      recordId = ConvergenceIdentities.record(
        record.workflowId,
        ConvergenceRecordKind.REVIEW_DISPOSITION,
        logicalId,
        generation,
      ),
      logicalId = logicalId,
      kind = ConvergenceRecordKind.REVIEW_DISPOSITION,
      provenance = ConvergenceProvenance(
        record.workflowId,
        generation,
        "review",
        reviewPass = connection.reviewGenerationPass(record.workflowId, record.generationId),
      ),
      evidenceDigest = ConvergenceIdentities.digest(
        listOf(record.disposition.wireValue, record.evidence.joinToString("|")).joinToString("|"),
      ),
      createdAt = connection.reviewDispositionCreatedAt(record),
      status = if (record.disposition.terminal) ConvergenceStatus.RESOLVED else ConvergenceStatus.OPEN,
      classification = record.disposition.wireValue.replace('-', '_'),
      summary = "review finding ${record.findingId} ${record.disposition.wireValue}"
        .take(CONVERGENCE_SUMMARY_MAX_LENGTH),
      parentLogicalId = parentLogicalId,
    )
    convergence.appendOrThrow(convergenceRecord)
  }
}

private fun ConvergenceStateRepository.appendOrThrow(record: ConvergenceRecord) {
  if (append(record) is ReplayResult.Conflict) {
    throw ConvergenceReplayConflictException(record.recordId)
  }
}

private fun Connection.reviewGenerationOrdinal(workflowId: String, generationId: String): Int = prepareStatement(
  "SELECT generation_ordinal FROM review_generations WHERE workflow_id = ? AND generation_id = ?",
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, generationId)
  statement.executeQuery().use { rows ->
    require(rows.next() && rows.getInt(1) > 0) { "Review generation '$generationId' is not durable." }
    rows.getInt(1)
  }
}

private fun Connection.reviewGenerationPass(workflowId: String, generationId: String): Int = prepareStatement(
  """
  SELECT MAX(pass_number)
  FROM review_generation_passes
  WHERE workflow_id = ? AND generation_id = ?
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, generationId)
  statement.executeQuery().use { rows ->
    require(rows.next() && rows.getInt(1) > 0) { "Review generation '$generationId' has no durable pass." }
    rows.getInt(1)
  }
}

private fun Connection.reviewGenerationCreatedAt(workflowId: String, generationId: String): String = prepareStatement(
  "SELECT created_at FROM review_generations WHERE workflow_id = ? AND generation_id = ?",
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, generationId)
  statement.executeQuery().use { rows ->
    require(rows.next()) { "Review generation '$generationId' is not durable." }
    rows.getString(1).toConvergenceTimestamp()
  }
}

private fun Connection.reviewFindingCreatedAt(workflowId: String, findingId: String): String = prepareStatement(
  "SELECT created_at FROM review_generation_findings WHERE workflow_id = ? AND finding_id = ?",
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, findingId)
  statement.executeQuery().use { rows ->
    require(rows.next()) { "Review finding '$findingId' is not durable." }
    rows.getString(1).toConvergenceTimestamp()
  }
}

private fun Connection.reviewDispositionCreatedAt(record: GoalSubtaskReviewFindingDispositionRecord): String =
  prepareStatement(
    """
    SELECT created_at
    FROM review_finding_dispositions
    WHERE workflow_id = ? AND generation_id = ? AND finding_id = ?
    """.trimIndent(),
  ).use { statement ->
    var parameterIndex = 1
    statement.setString(parameterIndex++, record.workflowId)
    statement.setString(parameterIndex++, record.generationId)
    statement.setString(parameterIndex, record.findingId)
    statement.executeQuery().use { rows ->
      require(rows.next()) { "Review disposition '${record.generationId}/${record.findingId}' is not durable." }
      rows.getString(1).toConvergenceTimestamp()
    }
  }

private fun String.toConvergenceTimestamp(): String = if ('T' in this) this else replace(' ', 'T') + "Z"
