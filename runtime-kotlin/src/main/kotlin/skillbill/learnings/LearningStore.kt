package skillbill.learnings

import skillbill.review.LearningRecord
import java.sql.Connection

data class CreateLearningRequest(
  val scope: LearningScope,
  val scopeKey: String,
  val title: String,
  val ruleText: String,
  val rationale: String,
  val sourceReviewRunId: String?,
  val sourceFindingId: String?,
)

data class UpdateLearningRequest(
  val learningId: Int,
  val scope: LearningScope?,
  val scopeKey: String?,
  val title: String?,
  val ruleText: String?,
  val rationale: String?,
)

object LearningStore {
  private const val PARAM_ONE: Int = 1
  private const val PARAM_TWO: Int = 2
  private const val PARAM_THREE: Int = 3
  private const val PARAM_FOUR: Int = 4
  private const val PARAM_FIVE: Int = 5
  private const val PARAM_SIX: Int = 6
  private const val PARAM_SEVEN: Int = 7

  fun addLearning(connection: Connection, request: CreateLearningRequest): Int {
    val (validatedScope, validatedScopeKey) =
      LearningsRuntime.validateLearningScope(request.scope, request.scopeKey)
    val validatedSource =
      LearningsRuntime.validateLearningSource(
        connection = connection,
        sourceReviewRunId = request.sourceReviewRunId,
        sourceFindingId = request.sourceFindingId,
      )
    require(request.title.trim().isNotEmpty()) { "Learning title must not be empty." }
    require(request.ruleText.trim().isNotEmpty()) { "Learning rule text must not be empty." }
    val effectiveRationale =
      effectiveRationale(
        rationale = request.rationale,
        rejectedOutcomeNote = validatedSource.rejectedOutcome.second,
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
      statement.setString(PARAM_THREE, request.title.trim())
      statement.setString(PARAM_FOUR, request.ruleText.trim())
      statement.setString(PARAM_FIVE, effectiveRationale)
      statement.setString(PARAM_SIX, validatedSource.reviewRunId)
      statement.setString(PARAM_SEVEN, validatedSource.findingId)
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
      WHERE id = ?
    """.trimIndent(),
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

  fun editLearning(connection: Connection, request: UpdateLearningRequest): LearningRecord {
    val current = getLearning(connection, request.learningId)
    val nextScope = request.scope ?: LearningScope.fromWireName(current.scope)
    val nextScopeKey = request.scopeKey ?: current.scopeKey
    val (validatedScope, validatedScopeKey) = LearningsRuntime.validateLearningScope(nextScope, nextScopeKey)
    val nextTitle = request.title?.trim() ?: current.title
    val nextRuleText = request.ruleText?.trim() ?: current.ruleText
    val nextRationale = request.rationale?.trim() ?: current.rationale
    require(nextTitle.isNotEmpty()) { "Learning title must not be empty." }
    require(nextRuleText.isNotEmpty()) { "Learning rule text must not be empty." }

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
    require(status in LearningsRuntime.learningStatuses) {
      "Learning status must be one of ${LearningsRuntime.learningStatuses.joinToString(", ")}."
    }
    getLearning(connection, learningId)
    connection.prepareStatement(
      """
      UPDATE learnings
      SET status = ?, updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(PARAM_ONE, status)
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
}

private fun effectiveRationale(rationale: String, rejectedOutcomeNote: String): String =
  if (rationale.trim().isEmpty() && rejectedOutcomeNote.isNotBlank()) {
    rejectedOutcomeNote.trim()
  } else {
    rationale.trim()
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
