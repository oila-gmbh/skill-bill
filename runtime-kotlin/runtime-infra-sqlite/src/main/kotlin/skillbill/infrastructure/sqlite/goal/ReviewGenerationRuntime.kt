package skillbill.infrastructure.sqlite.goal

import skillbill.infrastructure.sqlite.SQLiteConvergenceStateRepository
import skillbill.ports.persistence.ConvergenceReplayConflictException
import skillbill.ports.persistence.ConvergenceStateRepository
import skillbill.ports.persistence.ReviewGenerationRepository
import skillbill.workflow.taskruntime.model.CONVERGENCE_REFERENCE_MAX_LENGTH
import skillbill.workflow.taskruntime.model.CONVERGENCE_SUMMARY_MAX_LENGTH
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDispositionRecord
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGeneration
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGenerationIdentity
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewSummary
import skillbill.workflow.taskruntime.model.ReplayResult
import java.security.MessageDigest
import java.sql.Connection

internal class ReviewGenerationRuntime(
  private val connection: Connection,
  private val convergence: ConvergenceStateRepository = SQLiteConvergenceStateRepository(connection),
) : ReviewGenerationRepository {
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
    appendConvergenceCheckpoint(generation)
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
    if (existing != null) {
      require(existing == finding) { "Conflicting immutable review finding '${finding.findingId}'." }
    } else {
      connection.prepareStatement(
        """
        INSERT INTO review_generation_findings (
          workflow_id, generation_id, pass_number, finding_id, severity,
          category, location, summary, source_generation_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        var parameterIndex = 1
        statement.setString(parameterIndex++, workflowId)
        statement.setString(parameterIndex++, generationId)
        statement.setInt(parameterIndex++, passNumber)
        statement.setString(parameterIndex++, finding.findingId)
        statement.setString(parameterIndex++, finding.severity)
        statement.setString(parameterIndex++, finding.category)
        statement.setString(parameterIndex++, finding.location)
        statement.setString(parameterIndex++, finding.summary)
        statement.setString(parameterIndex, finding.sourceGenerationId)
        statement.executeUpdate()
      }
    }
    appendConvergenceFinding(workflowId, generationId, passNumber, finding)
  }

  override fun appendDisposition(record: GoalSubtaskReviewFindingDispositionRecord) {
    val evidenceJson = record.evidence.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]") {
      it.replace("\\", "\\\\").replace("\"", "\\\"")
    }
    connection.prepareStatement(
      """
      INSERT INTO review_finding_dispositions (
        workflow_id, generation_id, finding_id, disposition, evidence_json
      ) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id, generation_id, finding_id) DO NOTHING
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, record.workflowId)
      statement.setString(parameterIndex++, record.generationId)
      statement.setString(parameterIndex++, record.findingId)
      statement.setString(parameterIndex++, record.disposition.wireValue)
      statement.setString(parameterIndex, evidenceJson)
      statement.executeUpdate()
    }
    connection.prepareStatement(
      """
      SELECT disposition, evidence_json
      FROM review_finding_dispositions
      WHERE workflow_id = ? AND generation_id = ? AND finding_id = ?
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, record.workflowId)
      statement.setString(parameterIndex++, record.generationId)
      statement.setString(parameterIndex, record.findingId)
      statement.executeQuery().use { rows ->
        require(
          rows.next() &&
            rows.getString("disposition") == record.disposition.wireValue &&
            rows.getString("evidence_json") == evidenceJson,
        ) {
          "Conflicting immutable review disposition '${record.generationId}/${record.findingId}'."
        }
      }
    }
    appendConvergenceDisposition(record)
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

  private fun appendConvergenceFinding(
    workflowId: String,
    generationId: String,
    passNumber: Int,
    finding: GoalSubtaskReviewFinding,
  ) {
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

  private fun appendConvergenceCheckpoint(generation: GoalSubtaskReviewGeneration) {
    val identity = generation.identity
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

  private fun appendConvergenceDisposition(record: GoalSubtaskReviewFindingDispositionRecord) {
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
    rows.getString(1)
  }
}

private fun Connection.reviewFindingCreatedAt(workflowId: String, findingId: String): String = prepareStatement(
  "SELECT created_at FROM review_generation_findings WHERE workflow_id = ? AND finding_id = ?",
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, findingId)
  statement.executeQuery().use { rows ->
    require(rows.next()) { "Review finding '$findingId' is not durable." }
    rows.getString(1)
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
    statement.setString(1, record.workflowId)
    statement.setString(2, record.generationId)
    statement.setString(3, record.findingId)
    statement.executeQuery().use { rows ->
      require(rows.next()) { "Review disposition '${record.generationId}/${record.findingId}' is not durable." }
      rows.getString(1)
    }
  }

private fun Connection.loadCurrentReviewGeneration(workflowId: String): Pair<String, Int>? = prepareStatement(
  """
    SELECT g.generation_id, COALESCE(MAX(p.pass_number), 0) current_pass
    FROM review_generations g
    LEFT JOIN review_generation_passes p
      ON p.workflow_id = g.workflow_id AND p.generation_id = g.generation_id
    WHERE g.workflow_id = ? AND g.superseded_by_generation_id IS NULL
    GROUP BY g.generation_id, g.created_at
    ORDER BY g.created_at DESC, g.generation_id DESC LIMIT 1
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.executeQuery().use { rows ->
    if (rows.next()) rows.getString("generation_id") to rows.getInt("current_pass") else null
  }
}

private fun Connection.loadActiveReviewGeneration(workflowId: String): Pair<String?, Int>? = prepareStatement(
  """
      SELECT
        json_extract(w.artifacts_json, '$.goal_subtask_review_state.reserved_pass_number') reserved_pass,
        CASE
          WHEN json_extract(w.artifacts_json, '$.goal_subtask_review_state.reserved_pass_number') = 2
          THEN COALESCE(
            json_extract(w.artifacts_json, '$.goal_subtask_review_state.remediation_base_sha'),
            json_extract(w.artifacts_json, '$.goal_subtask_review_state.review_base_sha')
          )
          ELSE json_extract(w.artifacts_json, '$.goal_subtask_review_state.review_base_sha')
        END review_base,
        json_extract(w.artifacts_json, '$.goal_subtask_review_state.active_pass_delta_digest') delta_digest,
        json_extract(p.value, '$.repository_checkpoint.fingerprint') checkpoint
      FROM feature_task_workflows w
      LEFT JOIN json_each(w.artifacts_json, '$.feature_task_runtime_delivered_projections') p
        ON json_extract(p.value, '$.consumer_phase_id') = 'review'
      WHERE w.workflow_id = ?
        AND json_valid(w.artifacts_json)
        AND json_type(w.artifacts_json, '$.goal_subtask_review_state.reserved_pass_number') = 'integer'
      ORDER BY json_extract(p.value, '$.consumer_delivery_iteration') DESC
      LIMIT 1
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.executeQuery().use { rows ->
    if (!rows.next()) {
      null
    } else {
      val pass = rows.getInt("reserved_pass")
      val identityParts = listOf(
        workflowId,
        rows.getString("review_base"),
        rows.getString("delta_digest"),
        pass.toString(),
        rows.getString("checkpoint"),
      )
      val generation = identityParts.takeIf { parts -> parts.all { !it.isNullOrBlank() } }
        ?.joinToString("\u0000")
        ?.let(::sha256Hex)
        ?.let { "review-$it" }
      generation to pass
    }
  }
}

private fun Connection.loadReviewPassNumbers(workflowId: String, generationId: String): List<Int> = prepareStatement(
  """
      SELECT pass_number FROM review_generation_passes
      WHERE workflow_id = ? AND generation_id = ? ORDER BY pass_number
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, generationId)
  statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }
}

private fun Connection.loadReviewFinding(workflowId: String, findingId: String): GoalSubtaskReviewFinding? =
  prepareStatement(
    """
      SELECT finding_id, severity, category, location, summary, source_generation_id
      FROM review_generation_findings WHERE workflow_id = ? AND finding_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.setString(2, findingId)
    statement.executeQuery().use { rows -> if (rows.next()) rows.toFinding() else null }
  }

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray())
  .joinToString("") { byte -> "%02x".format(byte) }

private fun java.sql.ResultSet.toFinding(): GoalSubtaskReviewFinding = GoalSubtaskReviewFinding(
  findingId = getString("finding_id"),
  severity = getString("severity"),
  category = getString("category"),
  location = getString("location"),
  summary = getString("summary"),
  sourceGenerationId = getString("source_generation_id"),
)

private fun Connection.countReviewDisposition(workflowId: String, disposition: String): Int = prepareStatement(
  "SELECT COUNT(*) FROM review_finding_dispositions WHERE workflow_id = ? AND disposition = ?",
).use { statement ->
  var parameterIndex = 1
  statement.setString(parameterIndex++, workflowId)
  statement.setString(parameterIndex, disposition)
  statement.executeQuery().use { rows ->
    rows.next()
    rows.getInt(1)
  }
}
