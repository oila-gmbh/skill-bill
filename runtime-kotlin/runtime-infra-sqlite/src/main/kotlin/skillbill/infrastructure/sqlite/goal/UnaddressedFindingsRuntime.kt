package skillbill.infrastructure.sqlite.goal

import skillbill.goalrunner.model.UnaddressedFinding
import java.sql.Connection

internal class UnaddressedFindingsRuntime(private val connection: Connection) {
  fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) {
    deletePassesUpTo(workflowId, reviewPassNumber)
    connection.prepareStatement(
      """
      INSERT INTO unaddressed_findings (
        issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
        severity, issue_category, location, summary
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        statement.setString(parameterIndex, finding.summary)
        statement.addBatch()
      }
      statement.executeBatch()
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

  fun fetchLedger(issueKey: String): List<UnaddressedFinding> = connection.prepareStatement(
    """
    SELECT issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
           severity, issue_category, location, summary
    FROM unaddressed_findings
    WHERE issue_key = ?
    ORDER BY subtask_id, review_pass_number, finding_ordinal
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, issueKey)
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
            ),
          )
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
