package skillbill.db.workflow

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonSupport
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy
import skillbill.ports.persistence.GoalRunnerControlRepository
import skillbill.workflow.model.CodeReviewExecutionMode
import java.sql.Connection

internal class GoalRunnerControlStore(
  private val connection: Connection,
) : GoalRunnerControlRepository {
  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? =
    selectJson(parentWorkflowId, "review_policy_json")?.let { decodeReviewPolicy(it) }

  override fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy {
    connection.prepareStatement(
      """
      INSERT INTO goal_runner_controls (parent_workflow_id, review_policy_json)
      VALUES (?, ?)
      ON CONFLICT(parent_workflow_id) DO UPDATE SET
        review_policy_json = excluded.review_policy_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.setString(2, JsonSupport.mapToJsonString(policy.toArtifactMap()))
      statement.executeUpdate()
    }
    return policy
  }

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> =
    selectJson(parentWorkflowId, "out_of_band_acceptances_json")
      ?.let(::decodeAcceptances)
      .orEmpty()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance {
    val merged = outOfBandAcceptances(parentWorkflowId) + (acceptance.subtaskId to acceptance)
    connection.prepareStatement(
      """
      INSERT INTO goal_runner_controls (parent_workflow_id, out_of_band_acceptances_json)
      VALUES (?, ?)
      ON CONFLICT(parent_workflow_id) DO UPDATE SET
        out_of_band_acceptances_json = excluded.out_of_band_acceptances_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.setString(
        2,
        JsonSupport.valueToJsonElement(
          merged.values.sortedBy(GoalRunnerOutOfBandAcceptance::subtaskId)
            .map(GoalRunnerOutOfBandAcceptance::toArtifactMap),
        ).toString(),
      )
      statement.executeUpdate()
    }
    return acceptance
  }

  override fun clearOutOfBandAcceptances(parentWorkflowId: String) {
    connection.prepareStatement(
      """
      UPDATE goal_runner_controls
      SET out_of_band_acceptances_json = '[]', updated_at = CURRENT_TIMESTAMP
      WHERE parent_workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.executeUpdate()
    }
  }

  private fun selectJson(parentWorkflowId: String, column: String): String? = connection.prepareStatement(
    "SELECT $column FROM goal_runner_controls WHERE parent_workflow_id = ?",
  ).use { statement ->
    statement.setString(1, parentWorkflowId)
    statement.executeQuery().use { rows ->
      if (rows.next()) rows.getString(1)?.takeIf(String::isNotBlank) else null
    }
  }
}

private fun GoalRunnerReviewPolicy.toArtifactMap(): Map<String, Any?> = buildMap {
  put("code_review_mode", codeReviewMode.wireValue)
  parallelReviewAgent?.let { put("parallel_review_agent", it) }
  if (agentAddonSelection.entries.isNotEmpty()) {
    put(
      "agent_addon_selection",
      agentAddonSelection.entries.map { entry ->
        mapOf(
          "slug" to entry.slug,
          "source_identity" to entry.sourceIdentity,
          "content_sha256" to entry.contentSha256,
        )
      },
    )
  }
}

private fun GoalRunnerOutOfBandAcceptance.toArtifactMap(): Map<String, Any?> = mapOf(
  "subtask_id" to subtaskId,
  "commit_sha" to commitSha,
  "reason" to reason,
  "accepted_at" to acceptedAt,
)

private fun decodeReviewPolicy(raw: String): GoalRunnerReviewPolicy {
  val policy = JsonSupport.parseObjectOrNull(raw)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: error("Goal review policy durable record must be an object.")
  val mode = policy["code_review_mode"] as? String
    ?: error("Goal review policy durable record is missing code_review_mode.")
  val codeReviewMode = CodeReviewExecutionMode.fromWire(mode)
  val parallelReviewAgent = when (val value = policy["parallel_review_agent"]) {
    null -> null
    is String -> value.takeIf(String::isNotBlank)
      ?: error("Goal review policy durable record has a blank parallel_review_agent.")
    else -> error("Goal review policy durable record parallel_review_agent must be a string.")
  }
  val addOns = (policy["agent_addon_selection"] as? List<*>).orEmpty().mapIndexed { index, value ->
    val entry = JsonSupport.anyToStringAnyMap(value)
      ?: error("Goal review policy durable add-on entry $index must be a map.")
    PersistedAgentAddonSelectionEntry(
      slug = entry["slug"] as? String ?: error("Goal review policy durable add-on entry $index is missing slug."),
      sourceIdentity = entry["source_identity"] as? String
        ?: error("Goal review policy durable add-on entry $index is missing source_identity."),
      contentSha256 = entry["content_sha256"] as? String
        ?: error("Goal review policy durable add-on entry $index is missing content_sha256."),
    )
  }
  return GoalRunnerReviewPolicy(codeReviewMode, parallelReviewAgent, AgentAddonSelection(addOns))
}

private fun decodeAcceptances(raw: String): Map<Int, GoalRunnerOutOfBandAcceptance> {
  val entries = runCatching { JsonSupport.json.parseToJsonElement(raw) }
    .getOrElse { error -> throw IllegalArgumentException("Goal acceptance durable record is not valid JSON.", error) }
  val values = (JsonSupport.jsonElementToValue(entries) as? List<*>)
    ?: error("Goal acceptance durable record must be a list.")
  return values.associate { value ->
    val entry = JsonSupport.anyToStringAnyMap(value)
      ?: error("Goal acceptance durable record entries must be maps.")
    val acceptance = GoalRunnerOutOfBandAcceptance(
      subtaskId = (entry["subtask_id"] as? Number)?.toInt()
        ?: error("Goal acceptance durable record entry is missing subtask_id."),
      commitSha = entry["commit_sha"] as? String
        ?: error("Goal acceptance durable record entry is missing commit_sha."),
      reason = entry["reason"] as? String
        ?: error("Goal acceptance durable record entry is missing reason."),
      acceptedAt = entry["accepted_at"] as? String
        ?: error("Goal acceptance durable record entry is missing accepted_at."),
    )
    acceptance.subtaskId to acceptance
  }
}
