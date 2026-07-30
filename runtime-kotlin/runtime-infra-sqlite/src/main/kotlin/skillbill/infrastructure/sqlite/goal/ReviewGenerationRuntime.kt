package skillbill.infrastructure.sqlite.goal

import skillbill.infrastructure.sqlite.SQLiteConvergenceStateRepository
import skillbill.ports.persistence.ConvergenceStateRepository
import skillbill.ports.persistence.ReviewGenerationRepository
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDispositionRecord
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGeneration
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGenerationIdentity
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewSummary
import java.sql.Connection

internal class ReviewGenerationRuntime(
  private val connection: Connection,
  private val convergence: ConvergenceStateRepository = SQLiteConvergenceStateRepository(connection),
) : ReviewGenerationRepository {
  private val reviewConvergence = ReviewConvergenceRecorder(connection, convergence)

  override fun appendGeneration(generation: GoalSubtaskReviewGeneration) {
    val identity = generation.identity
    connection.prepareStatement(
      """
      INSERT OR IGNORE INTO review_generations (
        workflow_id, generation_id, generation_ordinal, review_base, reviewed_delta_digest,
        repository_checkpoint, superseded_by_generation_id
      ) VALUES (?, ?, (
        SELECT COALESCE(MAX(generation_ordinal), 0) + 1 FROM review_generations WHERE workflow_id = ?
      ), ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, identity.workflowId)
      statement.setString(parameterIndex++, identity.generationId)
      statement.setString(parameterIndex++, identity.workflowId)
      statement.setString(parameterIndex++, identity.reviewBase)
      statement.setString(parameterIndex++, identity.reviewedDeltaDigest)
      statement.setString(parameterIndex++, identity.repositoryCheckpoint)
      statement.setString(parameterIndex, generation.supersededByGenerationId)
      statement.executeUpdate()
    }
    val persisted = loadById(identity.workflowId, identity.generationId)
      ?: error("Review generation insert did not persist '${identity.generationId}'.")
    require(
      persisted.identity == identity &&
        persisted.supersededByGenerationId == generation.supersededByGenerationId,
    ) {
      "Conflicting immutable review generation '${identity.generationId}'."
    }
    reviewConvergence.appendCheckpoint(generation)
  }

  override fun appendPass(workflowId: String, generationId: String, passNumber: Int, repositoryCheckpoint: String) {
    connection.prepareStatement(
      """
      INSERT OR IGNORE INTO review_generation_passes (
        workflow_id, generation_id, pass_number, repository_checkpoint
      ) VALUES (?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, workflowId)
      statement.setString(parameterIndex++, generationId)
      statement.setInt(parameterIndex++, passNumber)
      statement.setString(parameterIndex, repositoryCheckpoint)
      statement.executeUpdate()
    }
    connection.prepareStatement(
      """
      SELECT repository_checkpoint FROM review_generation_passes
      WHERE workflow_id = ? AND generation_id = ? AND pass_number = ?
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, workflowId)
      statement.setString(parameterIndex++, generationId)
      statement.setInt(parameterIndex, passNumber)
      statement.executeQuery().use { rows ->
        require(rows.next() && rows.getString("repository_checkpoint") == repositoryCheckpoint) {
          "Conflicting immutable review pass '$generationId/$passNumber'."
        }
      }
    }
  }

  override fun appendFinding(
    workflowId: String,
    generationId: String,
    passNumber: Int,
    finding: GoalSubtaskReviewFinding,
  ) {
    val existing = connection.loadReviewFinding(workflowId, finding.findingId)
    val existingGenerationId = connection.loadReviewFindingGeneration(workflowId, finding.findingId)
    val persistedFinding = when {
      existing == null || existing == finding -> finding
      existingGenerationId == generationId -> finding
      existing.copy(sourceGenerationId = finding.sourceGenerationId) == finding -> existing
      else -> finding.copy(findingId = "${finding.sourceGenerationId}:${finding.findingId}")
    }
    val persisted = connection.loadReviewFinding(workflowId, persistedFinding.findingId)
    require(persisted == null || persisted == persistedFinding || existingGenerationId == generationId) {
      "Conflicting immutable review finding '${persistedFinding.findingId}'."
    }
    if (persisted == null || existingGenerationId == generationId) {
      connection.prepareStatement(
        """
        INSERT INTO review_generation_findings (
          workflow_id, generation_id, pass_number, finding_id, severity,
          category, location, summary, source_generation_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(workflow_id, finding_id) DO UPDATE SET
          generation_id = excluded.generation_id,
          pass_number = excluded.pass_number,
          severity = excluded.severity,
          category = excluded.category,
          location = excluded.location,
          summary = excluded.summary,
          source_generation_id = excluded.source_generation_id,
          created_at = CURRENT_TIMESTAMP
        """.trimIndent(),
      ).use { statement ->
        var parameterIndex = 1
        statement.setString(parameterIndex++, workflowId)
        statement.setString(parameterIndex++, generationId)
        statement.setInt(parameterIndex++, passNumber)
        statement.setString(parameterIndex++, persistedFinding.findingId)
        statement.setString(parameterIndex++, persistedFinding.severity)
        statement.setString(parameterIndex++, persistedFinding.category)
        statement.setString(parameterIndex++, persistedFinding.location)
        statement.setString(parameterIndex++, persistedFinding.summary)
        statement.setString(parameterIndex, persistedFinding.sourceGenerationId)
        statement.executeUpdate()
      }
    }
    reviewConvergence.appendFinding(workflowId, generationId, passNumber, persistedFinding)
  }

  override fun appendDisposition(record: GoalSubtaskReviewFindingDispositionRecord) {
    val persistedRecord = record.copy(
      findingId = connection.resolveReviewFindingId(record.workflowId, record.generationId, record.findingId),
    )
    val evidenceJson = persistedRecord.evidence.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]") {
      it.replace("\\", "\\\\").replace("\"", "\\\"")
    }
    connection.prepareStatement(
      """
      INSERT INTO review_finding_dispositions (
        workflow_id, generation_id, finding_id, disposition, evidence_json
      ) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id, generation_id, finding_id) DO UPDATE SET
        disposition = excluded.disposition,
        evidence_json = excluded.evidence_json,
        created_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, persistedRecord.workflowId)
      statement.setString(parameterIndex++, persistedRecord.generationId)
      statement.setString(parameterIndex++, persistedRecord.findingId)
      statement.setString(parameterIndex++, persistedRecord.disposition.wireValue)
      statement.setString(parameterIndex, evidenceJson)
      statement.executeUpdate()
    }
    reviewConvergence.appendDisposition(persistedRecord)
  }

  override fun loadGeneration(identity: GoalSubtaskReviewGenerationIdentity): GoalSubtaskReviewGeneration? =
    loadById(identity.workflowId, identity.generationId)?.takeIf { it.identity == identity }

  override fun hasGenerations(workflowId: String): Boolean = connection.prepareStatement(
    "SELECT 1 FROM review_generations WHERE workflow_id = ? LIMIT 1",
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { rows -> rows.next() }
  }

  override fun unresolvedBlockers(workflowId: String): List<GoalSubtaskReviewFinding> {
    if (!connection.hasConvergenceWorkflow(workflowId)) {
      return connection.loadLegacyUnresolvedBlockers(workflowId)
    }
    val unresolvedLogicalIds = convergence.unresolved(workflowId).reviewBlockers
      .mapTo(linkedSetOf(), ConvergenceRecord::logicalId)
    return connection.prepareStatement(
      """
      SELECT f.finding_id, f.severity, f.category, f.location, f.summary, f.source_generation_id
      FROM review_generation_findings f
      WHERE f.workflow_id = ?
        AND f.severity = 'blocker'
      ORDER BY f.finding_id
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val finding = rows.toFinding()
            val logicalId = ConvergenceIdentities.logical(
              workflowId,
              ConvergenceRecordKind.REVIEW_FINDING,
              finding.findingId,
            )
            if (logicalId in unresolvedLogicalIds) add(finding)
          }
        }
      }
    }
  }

  override fun summary(workflowId: String): GoalSubtaskReviewSummary {
    val current = connection.loadCurrentReviewGeneration(workflowId)
    val active = connection.loadActiveReviewGeneration(workflowId)
    val currentGenerationId = active?.first ?: current?.first
    val terminalCounts = GoalSubtaskReviewFindingDisposition.entries
      .filter(GoalSubtaskReviewFindingDisposition::terminal)
      .associate { disposition ->
        disposition.wireValue to connection.countReviewDisposition(workflowId, disposition.wireValue)
      }
    val unresolved = unresolvedBlockers(workflowId)
    return GoalSubtaskReviewSummary(
      currentGenerationId = currentGenerationId,
      currentPass = active?.second ?: current?.second ?: 0,
      carriedBlockerCount = unresolved.count { it.sourceGenerationId != currentGenerationId },
      newBlockerCount = unresolved.count { it.sourceGenerationId == currentGenerationId },
      terminalDispositionCounts = terminalCounts,
    )
  }

  private fun loadById(workflowId: String, generationId: String): GoalSubtaskReviewGeneration? =
    connection.prepareStatement(
      """
      SELECT review_base, reviewed_delta_digest, repository_checkpoint, superseded_by_generation_id
      FROM review_generations WHERE workflow_id = ? AND generation_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, generationId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        GoalSubtaskReviewGeneration(
          identity = GoalSubtaskReviewGenerationIdentity(
            workflowId = workflowId,
            generationId = generationId,
            reviewBase = rows.getString("review_base"),
            reviewedDeltaDigest = rows.getString("reviewed_delta_digest"),
            repositoryCheckpoint = rows.getString("repository_checkpoint"),
          ),
          passNumbers = connection.loadReviewPassNumbers(workflowId, generationId),
          supersededByGenerationId = rows.getString("superseded_by_generation_id"),
        )
      }
    }
}
