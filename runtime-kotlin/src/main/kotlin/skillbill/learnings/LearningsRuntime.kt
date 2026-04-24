package skillbill.learnings

import skillbill.contracts.JsonSupport
import skillbill.review.LearningRecord
import skillbill.review.ReviewRuntime
import java.sql.Connection

data class LearningSourceValidation(
  val reviewRunId: String,
  val findingId: String,
  val rejectedOutcome: Pair<String, String>,
)

object LearningsRuntime {
  val learningStatuses: List<String> = listOf("active", "disabled")
  private val rejectedFindingOutcomeTypes = listOf("fix_rejected", "false_positive")
  private const val REJECTED_OUTCOME_FIRST_PARAM_INDEX: Int = 3

  fun validateLearningScope(scope: LearningScope, scopeKey: String): Pair<LearningScope, String> =
    scope to scope.normalizeScopeKey(scopeKey)

  fun validateLearningSource(
    connection: Connection,
    sourceReviewRunId: String?,
    sourceFindingId: String?,
  ): LearningSourceValidation {
    require(!sourceReviewRunId.isNullOrBlank() && !sourceFindingId.isNullOrBlank()) {
      "Learnings must be derived from a rejected review finding. Provide both --from-run and --from-finding."
    }
    require(ReviewRuntime.findingExists(connection, sourceReviewRunId, sourceFindingId)) {
      "Unknown learning source '$sourceReviewRunId:$sourceFindingId'. Import the review and finding first."
    }
    val rejectedOutcome = fetchLatestRejectedOutcome(connection, sourceReviewRunId, sourceFindingId)
    require(rejectedOutcome != null) {
      "Finding '$sourceFindingId' in run '$sourceReviewRunId' has no rejected outcome. " +
        "Learnings can only be created from findings the user rejected " +
        "(fix_rejected or false_positive)."
    }
    return LearningSourceValidation(
      reviewRunId = sourceReviewRunId,
      findingId = sourceFindingId,
      rejectedOutcome = rejectedOutcome,
    )
  }

  fun fetchLatestRejectedOutcome(
    connection: Connection,
    reviewRunId: String,
    findingId: String,
  ): Pair<String, String>? {
    val placeholders = rejectedFindingOutcomeTypes.joinToString(", ") { "?" }
    return connection.prepareStatement(
      """
      SELECT event_type, note
      FROM feedback_events
      WHERE review_run_id = ? AND finding_id = ? AND event_type IN ($placeholders)
      ORDER BY id DESC
      LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, reviewRunId)
      statement.setString(2, findingId)
      rejectedFindingOutcomeTypes.forEachIndexed { index, value ->
        statement.setString(index + REJECTED_OUTCOME_FIRST_PARAM_INDEX, value)
      }
      statement.executeQuery().use { resultSet ->
        if (resultSet.next()) {
          resultSet.getString("event_type") to resultSet.getString("note").orEmpty()
        } else {
          null
        }
      }
    }
  }

  fun normalizeOptionalLookupValue(rawValue: String?, argumentName: String): String? {
    if (rawValue == null) {
      return null
    }
    val normalized = rawValue.trim()
    require(normalized.isNotEmpty()) { "$argumentName must not be empty when provided." }
    return normalized
  }

  fun resolveLearnings(
    connection: Connection,
    repoScopeKey: String?,
    skillName: String?,
  ): Triple<String?, String?, List<LearningRecord>> {
    val normalizedRepoScopeKey = normalizeOptionalLookupValue(repoScopeKey, "--repo")
    val normalizedSkillName = normalizeOptionalLookupValue(skillName, "--skill")
    val scopeClauses = mutableListOf("scope = '${LearningScope.GLOBAL.wireName}'")
    val parameters = mutableListOf<String>()
    if (normalizedRepoScopeKey != null) {
      scopeClauses += "(scope = '${LearningScope.REPO.wireName}' AND scope_key = ?)"
      parameters += normalizedRepoScopeKey
    }
    if (normalizedSkillName != null) {
      scopeClauses += "(scope = '${LearningScope.SKILL.wireName}' AND scope_key = ?)"
      parameters += normalizedSkillName
    }

    val rows =
      connection.prepareStatement(
        """
        SELECT
          id,
          scope,
          scope_key,
          title,
          rule_text,
          rationale,
          status,
          source_review_run_id,
          source_finding_id,
          created_at,
          updated_at
        FROM learnings
        WHERE status = 'active'
          AND (${scopeClauses.joinToString(" OR ")})
        ORDER BY
          ${learningScopeOrderClause("scope")},
          id
        """.trimIndent(),
      ).use { statement ->
        parameters.forEachIndexed { index, parameter ->
          statement.setString(index + 1, parameter)
        }
        statement.executeQuery().use { resultSet ->
          buildList {
            while (resultSet.next()) {
              add(LearningStore.getLearning(connection, resultSet.getInt("id")))
            }
          }
        }
      }
    return Triple(normalizedRepoScopeKey, normalizedSkillName, rows)
  }

  fun saveSessionLearnings(connection: Connection, reviewSessionId: String, learningsJson: String) {
    connection.prepareStatement(
      """
      INSERT INTO session_learnings (review_session_id, learnings_json, updated_at)
      VALUES (?, ?, CURRENT_TIMESTAMP)
      ON CONFLICT(review_session_id) DO UPDATE SET
        learnings_json = excluded.learnings_json,
        updated_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, reviewSessionId)
      statement.setString(2, learningsJson)
      statement.executeUpdate()
    }
  }

  fun fetchSessionLearnings(connection: Connection, reviewSessionId: String): Map<String, Any?>? =
    connection.prepareStatement(
      """
      SELECT learnings_json
      FROM session_learnings
      WHERE review_session_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, reviewSessionId)
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) {
          return null
        }
        decodeSessionLearnings(resultSet.getString("learnings_json"))
      }
    }
}

private fun learningScopeOrderClause(columnName: String): String = buildString {
  appendLine("CASE $columnName")
  LearningScope.precedence.forEachIndexed { index, scope ->
    appendLine("  WHEN '${scope.wireName}' THEN $index")
  }
  append("  ELSE ${LearningScope.precedence.size}\nEND")
}

private fun decodeSessionLearnings(rawJson: String): Map<String, Any?>? = JsonSupport.parseObjectOrNull(rawJson)?.let {
  JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
}
