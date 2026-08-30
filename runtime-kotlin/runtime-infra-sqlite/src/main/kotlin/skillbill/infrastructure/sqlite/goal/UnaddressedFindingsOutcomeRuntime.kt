package skillbill.infrastructure.sqlite.goal

import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import java.sql.Connection

internal class UnaddressedFindingsOutcomeRuntime(private val connection: Connection) {
  fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>) {
    if (outcomes.isEmpty()) return
    connection.prepareStatement(
      """
      INSERT INTO review_finding_outcomes (
        workflow_id, review_pass_number, finding_ordinal, review_run_id, finding_id, finding_key,
        key_state, outcome
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id, review_pass_number, finding_ordinal) DO UPDATE SET
        review_run_id = excluded.review_run_id,
        finding_id = excluded.finding_id,
        finding_key = excluded.finding_key,
        key_state = excluded.key_state,
        outcome = excluded.outcome,
        recorded_at = CURRENT_TIMESTAMP
      """.trimIndent(),
    ).use { statement ->
      outcomes.forEach { outcome ->
        var parameterIndex = 1
        statement.setString(parameterIndex++, outcome.workflowId)
        statement.setInt(parameterIndex++, outcome.reviewPassNumber)
        statement.setInt(parameterIndex++, outcome.findingOrdinal)
        statement.setString(parameterIndex++, outcome.reviewRunId)
        statement.setString(parameterIndex++, outcome.findingId)
        statement.setString(parameterIndex++, outcome.findingKey)
        statement.setString(parameterIndex++, outcome.keyState)
        statement.setString(parameterIndex, outcome.outcome.wireValue)
        statement.addBatch()
      }
      statement.executeBatch()
    }
    reconcileEarlierPasses(outcomes)
  }

  fun fetchOutcomes(workflowId: String): List<ReviewFindingOutcomeRecord> = connection.prepareStatement(
    """
    SELECT workflow_id, review_pass_number, finding_ordinal, review_run_id, finding_id, finding_key, outcome
    FROM review_finding_outcomes
    WHERE workflow_id = ?
    ORDER BY review_pass_number, finding_ordinal
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          add(
            ReviewFindingOutcomeRecord(
              workflowId = rows.getString("workflow_id"),
              reviewPassNumber = rows.getInt("review_pass_number"),
              findingOrdinal = rows.getInt("finding_ordinal"),
              outcome = ReviewFindingOutcome.fromWireValue(rows.getString("outcome")),
              reviewRunId = rows.getString("review_run_id"),
              findingId = rows.getString("finding_id"),
              findingKey = rows.getString("finding_key"),
            ),
          )
        }
      }
    }
  }

  private fun reconcileEarlierPasses(outcomes: List<ReviewFindingOutcomeRecord>) {
    val terminal = outcomes.filter { it.outcome != ReviewFindingOutcome.CARRIED && it.findingKey != null }
    if (terminal.isEmpty()) return
    connection.prepareStatement(
      """
      UPDATE review_finding_outcomes
      SET outcome = ?, recorded_at = CURRENT_TIMESTAMP
      WHERE workflow_id = ? AND finding_key = ? AND review_pass_number < ? AND outcome = 'carried'
      """.trimIndent(),
    ).use { statement ->
      terminal.forEach { outcome ->
        var parameterIndex = 1
        statement.setString(parameterIndex++, outcome.outcome.wireValue)
        statement.setString(parameterIndex++, outcome.workflowId)
        statement.setString(parameterIndex++, outcome.findingKey)
        statement.setInt(parameterIndex, outcome.reviewPassNumber)
        statement.addBatch()
      }
      statement.executeBatch()
    }
  }
}
