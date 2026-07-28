package skillbill.infrastructure.sqlite.goal

import skillbill.ports.persistence.ReviewGenerationRepository
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFindingDispositionRecord
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGeneration
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewGenerationIdentity
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewSummary
import java.sql.Connection

internal class ReviewGenerationRuntime(
  private val connection: Connection,
) : ReviewGenerationRepository {
  override fun appendGeneration(generation: GoalSubtaskReviewGeneration) {
    val identity = generation.identity
    connection.prepareStatement(
      """
      INSERT OR IGNORE INTO review_generations (
        workflow_id, generation_id, review_base, reviewed_delta_digest,
        repository_checkpoint, superseded_by_generation_id
      ) VALUES (?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 1
      statement.setString(parameterIndex++, identity.workflowId)
      statement.setString(parameterIndex++, identity.generationId)
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
    val existing = loadFinding(workflowId, finding.findingId)
    if (existing != null && existing.copy(sourceGenerationId = finding.sourceGenerationId) == finding) {
      return
    }
    if (existing != null) {
      require(existing.sourceGenerationId != generationId) {
        "Conflicting immutable review finding '${finding.findingId}'."
      }
      appendFinding(
        workflowId,
        generationId,
        passNumber,
        finding.copy(findingId = "$generationId:${finding.findingId}"),
      )
      return
    }
    connection.prepareStatement(
      """
      INSERT OR IGNORE INTO review_generation_findings (
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
    require(loadFinding(workflowId, finding.findingId) == finding) {
      "Conflicting immutable review finding '${finding.findingId}'."
    }
  }

  override fun appendDisposition(record: GoalSubtaskReviewFindingDispositionRecord) {
    val evidenceJson = record.evidence.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]") {
      it.replace("\\", "\\\\").replace("\"", "\\\"")
    }
    connection.prepareStatement(
      """
      INSERT OR IGNORE INTO review_finding_dispositions (
        workflow_id, generation_id, finding_id, disposition, evidence_json
      ) VALUES (?, ?, ?, ?, ?)
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
        ) { "Conflicting immutable disposition for finding '${record.findingId}'." }
      }
    }
  }

  override fun loadGeneration(identity: GoalSubtaskReviewGenerationIdentity): GoalSubtaskReviewGeneration? =
    loadById(identity.workflowId, identity.generationId)?.takeIf { it.identity == identity }

  override fun unresolvedBlockers(workflowId: String): List<GoalSubtaskReviewFinding> = connection.prepareStatement(
    """
      SELECT f.finding_id, f.severity, f.category, f.location, f.summary, f.source_generation_id
      FROM review_generation_findings f
      JOIN review_generations g
        ON g.workflow_id = f.workflow_id AND g.generation_id = f.generation_id
      WHERE f.workflow_id = ?
        AND f.severity = 'blocker'
        AND g.superseded_by_generation_id IS NULL
        AND COALESCE((
          SELECT d.disposition
          FROM review_finding_dispositions d
          WHERE d.workflow_id = f.workflow_id AND d.finding_id = f.finding_id
          ORDER BY d.created_at DESC, d.generation_id DESC
          LIMIT 1
        ), 'unresolved') IN ('unresolved', 'still_present')
      ORDER BY f.finding_id
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) add(rows.toFinding())
      }
    }
  }

  override fun summary(workflowId: String): GoalSubtaskReviewSummary {
    val current = connection.prepareStatement(
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
    val terminalCounts = GoalSubtaskReviewFindingDisposition.entries
      .filter(GoalSubtaskReviewFindingDisposition::terminal)
      .associate { disposition ->
        disposition.wireValue to connection.countReviewDisposition(workflowId, disposition.wireValue)
      }
    val unresolved = unresolvedBlockers(workflowId)
    return GoalSubtaskReviewSummary(
      currentGenerationId = current?.first,
      currentPass = current?.second ?: 0,
      carriedBlockerCount = unresolved.count { it.sourceGenerationId != current?.first },
      newBlockerCount = unresolved.count { it.sourceGenerationId == current?.first },
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
          passNumbers = loadPassNumbers(workflowId, generationId),
          supersededByGenerationId = rows.getString("superseded_by_generation_id"),
        )
      }
    }

  private fun loadPassNumbers(workflowId: String, generationId: String): List<Int> = connection.prepareStatement(
    """
      SELECT pass_number FROM review_generation_passes
      WHERE workflow_id = ? AND generation_id = ? ORDER BY pass_number
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.setString(2, generationId)
    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }
  }

  private fun loadFinding(workflowId: String, findingId: String): GoalSubtaskReviewFinding? =
    connection.prepareStatement(
      """
      SELECT finding_id, severity, category, location, summary, source_generation_id
      FROM review_generation_findings WHERE workflow_id = ? AND finding_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setString(2, findingId)
      statement.executeQuery().use { rows -> if (rows.next()) rows.toFinding() else null }
    }
}

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
