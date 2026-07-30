package skillbill.infrastructure.sqlite.goal

import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewFinding
import java.sql.Connection

internal fun Connection.hasConvergenceWorkflow(workflowId: String): Boolean = prepareStatement(
  "SELECT 1 FROM feature_task_workflows WHERE workflow_id = ?",
).use { statement ->
  statement.setString(1, workflowId)
  statement.executeQuery().use { it.next() }
}

internal fun Connection.loadLegacyUnresolvedBlockers(workflowId: String): List<GoalSubtaskReviewFinding> =
  prepareStatement(
    """
    SELECT f.finding_id, f.severity, f.category, f.location, f.summary, f.source_generation_id
    FROM review_generation_findings f
    WHERE f.workflow_id = ?
      AND f.severity = 'blocker'
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
    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toFinding()) } }
  }

internal fun Connection.loadCurrentReviewGeneration(workflowId: String): Pair<String, Int>? = prepareStatement(
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

internal fun Connection.loadActiveReviewGeneration(workflowId: String): Pair<String?, Int>? = prepareStatement(
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
        ?.let(ConvergenceIdentities::digest)
        ?.let { "review-$it" }
      generation to pass
    }
  }
}

internal fun Connection.loadReviewPassNumbers(workflowId: String, generationId: String): List<Int> = prepareStatement(
  """
      SELECT pass_number FROM review_generation_passes
      WHERE workflow_id = ? AND generation_id = ? ORDER BY pass_number
  """.trimIndent(),
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, generationId)
  statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }
}

internal fun Connection.loadReviewFinding(workflowId: String, findingId: String): GoalSubtaskReviewFinding? =
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

internal fun Connection.loadReviewFindingGeneration(workflowId: String, findingId: String): String? = prepareStatement(
  "SELECT generation_id FROM review_generation_findings WHERE workflow_id = ? AND finding_id = ?",
).use { statement ->
  statement.setString(1, workflowId)
  statement.setString(2, findingId)
  statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
}

internal fun Connection.resolveReviewFindingId(workflowId: String, generationId: String, findingId: String): String =
  if (loadReviewFinding(workflowId, findingId)?.sourceGenerationId == generationId) {
    findingId
  } else {
    "$generationId:$findingId".takeIf { loadReviewFinding(workflowId, it) != null } ?: findingId
  }

internal fun java.sql.ResultSet.toFinding(): GoalSubtaskReviewFinding = GoalSubtaskReviewFinding(
  findingId = getString("finding_id"),
  severity = getString("severity"),
  category = getString("category"),
  location = getString("location"),
  summary = getString("summary"),
  sourceGenerationId = getString("source_generation_id"),
)

internal fun Connection.countReviewDisposition(workflowId: String, disposition: String): Int = prepareStatement(
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
