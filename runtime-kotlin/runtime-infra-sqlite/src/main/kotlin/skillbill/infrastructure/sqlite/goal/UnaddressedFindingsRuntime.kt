package skillbill.infrastructure.sqlite.goal

import skillbill.goalrunner.model.ReviewFindingOutcome
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import java.sql.Connection

@Suppress("TooManyFunctions")
internal class UnaddressedFindingsRuntime(private val connection: Connection) {
  fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) {
    deletePassesUpTo(workflowId, reviewPassNumber)
    connection.prepareStatement(
      """
      INSERT INTO unaddressed_findings (
        issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
        severity, issue_category, location, summary, review_run_id, finding_id
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      findings.forEach { finding ->
        var parameterIndex = 1
        statement.setString(parameterIndex++, finding.issueKey)
        statement.setString(parameterIndex++, finding.workflowId)
        statement.setInt(parameterIndex++, finding.subtaskId)
        statement.setInt(parameterIndex++, finding.reviewPassNumber)
        statement.setInt(parameterIndex++, finding.findingOrdinal)
        statement.setString(parameterIndex++, finding.severity)
        statement.setString(parameterIndex++, finding.issueCategory)
        statement.setString(parameterIndex++, finding.location)
        statement.setString(parameterIndex++, finding.summary)
        statement.setString(parameterIndex++, finding.reviewRunId)
        statement.setString(parameterIndex, finding.findingId)
        statement.addBatch()
      }
      statement.executeBatch()
    }
  }

  /**
   * Outcomes live in their own table because the ledger above is retracted on every pass and on
   * review-generation invalidation, which would destroy an outcome recorded alongside it.
   */
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

  /**
   * The ledger only ever holds the immediately preceding pass, so a finding first reported in pass 1
   * and retired in pass 3 would leave pass 1's row CARRIED forever and under-report acceptance. The
   * durable outcome table does keep every pass, so a terminal outcome is propagated back to every
   * earlier still-carried row of the same workflow sharing the finding key.
   *
   * The match is on `finding_key`, never on `finding_id`: each pass is its own review run and
   * renumbers from `F-001`, so an id match would back-propagate a terminal outcome onto whichever
   * unrelated earlier finding happened to share an ordinal.
   */
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

  /**
   * Every pass of one workflow reviews the same immutable base-to-current delta, so the newest pass
   * is a complete re-observation of that scope and supersedes its predecessors. Retaining an earlier
   * pass would keep reporting findings the fix loop has since addressed — and because an unresolved
   * Blocker stops advancement, every Blocker surviving into a completed subtask would be one that
   * was addressed.
   */
  fun clearWorkflowLedger(workflowId: String) {
    connection.prepareStatement("DELETE FROM unaddressed_findings WHERE workflow_id = ?").use { statement ->
      statement.setString(1, workflowId)
      statement.executeUpdate()
    }
  }

  private fun deletePassesUpTo(workflowId: String, reviewPassNumber: Int) {
    connection.prepareStatement(
      "DELETE FROM unaddressed_findings WHERE workflow_id = ? AND review_pass_number <= ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setInt(2, reviewPassNumber)
      statement.executeUpdate()
    }
  }

  fun fetchLedger(issueKey: String): List<UnaddressedFinding> = fetchLedgerBy("issue_key", issueKey)

  fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> = fetchLedgerBy("workflow_id", workflowId)

  private fun fetchLedgerBy(column: String, value: String): List<UnaddressedFinding> = connection.prepareStatement(
    """
    SELECT issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
           severity, issue_category, location, summary, review_run_id, finding_id
    FROM unaddressed_findings
    WHERE $column = ?
    ORDER BY subtask_id, review_pass_number, finding_ordinal
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, value)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          add(
            UnaddressedFinding(
              issueKey = rows.getString("issue_key"),
              workflowId = rows.getString("workflow_id"),
              subtaskId = rows.getInt("subtask_id"),
              reviewPassNumber = rows.getInt("review_pass_number"),
              findingOrdinal = rows.getInt("finding_ordinal"),
              severity = rows.getString("severity"),
              issueCategory = rows.getString("issue_category"),
              location = rows.getString("location"),
              summary = rows.getString("summary"),
              reviewRunId = rows.getString("review_run_id"),
              findingId = rows.getString("finding_id"),
            ),
          )
        }
      }
    }
  }

  fun workflowIdsForIssue(issueKey: String): List<String> = connection.prepareStatement(
    "SELECT workflow_id FROM feature_task_workflows WHERE issue_key = ? ORDER BY workflow_id",
  ).use { statement ->
    statement.setString(1, issueKey)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          rows.getString("workflow_id")?.takeIf(String::isNotBlank)?.let(::add)
        }
      }
    }
  }

  fun issueExists(issueKey: String): Boolean = connection.prepareStatement(
    "SELECT 1 FROM feature_task_workflows WHERE issue_key = ? LIMIT 1",
  ).use { statement ->
    statement.setString(1, issueKey)
    statement.executeQuery().use { it.next() }
  }
}
