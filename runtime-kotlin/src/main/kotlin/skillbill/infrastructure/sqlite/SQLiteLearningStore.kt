package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonSupport
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningRecord
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningSourceValidation
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.UpdateLearningRequest
import java.sql.Connection

object SQLiteLearningStore {
  private const val PARAM_ONE: Int = 1
  private const val PARAM_TWO: Int = 2
  private const val PARAM_THREE: Int = 3
  private const val PARAM_FOUR: Int = 4
  private const val PARAM_FIVE: Int = 5
  private const val PARAM_SIX: Int = 6
  private const val PARAM_SEVEN: Int = 7

  fun addLearning(
    connection: Connection,
    request: CreateLearningRequest,
    sourceValidation: LearningSourceValidation,
  ): Int {
    val (validatedScope, validatedScopeKey) =
      LearningsRuntime.validateLearningScope(request.scope, request.scopeKey)
    val (validatedTitle, validatedRuleText) =
      LearningsRuntime.validateLearningText(request.title, request.ruleText)
    val requestedSource =
      LearningsRuntime.validateLearningSourceReference(
        request.sourceReviewRunId,
        request.sourceFindingId,
      )
    require(
      requestedSource.reviewRunId == sourceValidation.reviewRunId &&
        requestedSource.findingId == sourceValidation.findingId,
    ) {
      "Learning source validation must match the requested source."
    }
    val effectiveRationale =
      LearningsRuntime.effectiveRationale(
        rationale = request.rationale,
        rejectedOutcomeNote = sourceValidation.rejectedOutcome.note,
      )

    connection.prepareStatement(
      """
      INSERT INTO learnings (
        scope,
        scope_key,
        title,
        rule_text,
        rationale,
        status,
        source_review_run_id,
        source_finding_id
      ) VALUES (?, ?, ?, ?, ?, 'active', ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, validatedScope.wireName)
      statement.setString(PARAM_TWO, validatedScopeKey)
      statement.setString(PARAM_THREE, validatedTitle)
      statement.setString(PARAM_FOUR, validatedRuleText)
      statement.setString(PARAM_FIVE, effectiveRationale)
      statement.setString(PARAM_SIX, sourceValidation.reviewRunId)
      statement.setString(PARAM_SEVEN, sourceValidation.findingId)
      statement.executeUpdate()
    }
    return connection.createStatement().use { statement ->
      statement.executeQuery("SELECT last_insert_rowid()").use { resultSet ->
        resultSet.next()
        resultSet.getInt(PARAM_ONE)
      }
    }
  }

  fun getLearning(connection: Connection, learningId: Int): LearningRecord = connection.prepareStatement(
    learningRecordSelectSql("WHERE id = ?"),
  ).use { statement ->
    statement.setInt(PARAM_ONE, learningId)
    statement.executeQuery().use { resultSet ->
      require(resultSet.next()) { "Unknown learning id '$learningId'." }
      resultSet.toLearningRecord()
    }
  }

  fun listLearnings(connection: Connection, status: String): List<LearningRecord> {
    val query =
      buildString {
        appendLine(learningRecordSelectSql())
        if (status != "all") {
          appendLine("WHERE status = ?")
        }
        append("ORDER BY id")
      }
    return connection.prepareStatement(query).use { statement ->
      if (status != "all") {
        statement.setString(PARAM_ONE, status)
      }
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(resultSet.toLearningRecord())
          }
        }
      }
    }
  }

  fun resolveLearnings(
    connection: Connection,
    repoScopeKey: String?,
    skillName: String?,
  ): Triple<String?, String?, List<LearningRecord>> {
    val normalizedRepoScopeKey = LearningsRuntime.normalizeOptionalLookupValue(repoScopeKey, "--repo")
    val normalizedSkillName = LearningsRuntime.normalizeOptionalLookupValue(skillName, "--skill")
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
        ${learningRecordSelectSql()}
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
              add(resultSet.toLearningRecord())
            }
          }
        }
      }
    return Triple(normalizedRepoScopeKey, normalizedSkillName, rows)
  }

  fun editLearning(connection: Connection, request: UpdateLearningRequest): LearningRecord {
    val current = getLearning(connection, request.learningId)
    val nextScope = request.scope ?: LearningScope.fromWireName(current.scope)
    val nextScopeKey = request.scopeKey ?: current.scopeKey
    val (validatedScope, validatedScopeKey) = LearningsRuntime.validateLearningScope(nextScope, nextScopeKey)
    val (nextTitle, nextRuleText) =
      LearningsRuntime.validateLearningText(
        title = request.title ?: current.title,
        ruleText = request.ruleText ?: current.ruleText,
      )
    val nextRationale = request.rationale?.trim() ?: current.rationale

    connection.prepareStatement(
      """
      UPDATE learnings
      SET scope = ?,
          scope_key = ?,
          title = ?,
          rule_text = ?,
          rationale = ?,
          updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, validatedScope.wireName)
      statement.setString(PARAM_TWO, validatedScopeKey)
      statement.setString(PARAM_THREE, nextTitle)
      statement.setString(PARAM_FOUR, nextRuleText)
      statement.setString(PARAM_FIVE, nextRationale)
      statement.setInt(PARAM_SIX, request.learningId)
      statement.executeUpdate()
    }
    return getLearning(connection, request.learningId)
  }

  fun setLearningStatus(connection: Connection, learningId: Int, status: String): LearningRecord {
    val validatedStatus = LearningsRuntime.validateLearningStatus(status)
    getLearning(connection, learningId)
    connection.prepareStatement(
      """
      UPDATE learnings
      SET status = ?, updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, validatedStatus)
      statement.setInt(PARAM_TWO, learningId)
      statement.executeUpdate()
    }
    return getLearning(connection, learningId)
  }

  fun deleteLearning(connection: Connection, learningId: Int) {
    getLearning(connection, learningId)
    connection.prepareStatement("DELETE FROM learnings WHERE id = ?").use { statement ->
      statement.setInt(PARAM_ONE, learningId)
      statement.executeUpdate()
    }
  }

  fun countLearnings(connection: Connection, status: String? = null): Int {
    val query =
      if (status == null) {
        "SELECT COUNT(*) FROM learnings"
      } else {
        "SELECT COUNT(*) FROM learnings WHERE status = ?"
      }
    return connection.prepareStatement(query).use { statement ->
      if (status != null) {
        statement.setString(PARAM_ONE, status)
      }
      statement.executeQuery().use { resultSet ->
        if (resultSet.next()) {
          resultSet.getInt(PARAM_ONE)
        } else {
          0
        }
      }
    }
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
      statement.setString(PARAM_ONE, reviewSessionId)
      statement.setString(PARAM_TWO, learningsJson)
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
      statement.setString(PARAM_ONE, reviewSessionId)
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) {
          return null
        }
        decodeSessionLearnings(resultSet.getString("learnings_json"))
      }
    }
}

private fun learningRecordSelectSql(whereClause: String? = null): String = buildString {
  appendLine("SELECT")
  appendLine("  id,")
  appendLine("  scope,")
  appendLine("  scope_key,")
  appendLine("  title,")
  appendLine("  rule_text,")
  appendLine("  rationale,")
  appendLine("  status,")
  appendLine("  source_review_run_id,")
  appendLine("  source_finding_id,")
  appendLine("  created_at,")
  appendLine("  updated_at")
  appendLine("FROM learnings")
  if (whereClause != null) {
    append(whereClause)
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

private fun java.sql.ResultSet.toLearningRecord(): LearningRecord = LearningRecord(
  id = getInt("id"),
  scope = getString("scope"),
  scopeKey = getString("scope_key"),
  title = getString("title"),
  ruleText = getString("rule_text"),
  rationale = getString("rationale").orEmpty(),
  status = getString("status"),
  sourceReviewRunId = getString("source_review_run_id"),
  sourceFindingId = getString("source_finding_id"),
  createdAt = getString("created_at"),
  updatedAt = getString("updated_at"),
)
