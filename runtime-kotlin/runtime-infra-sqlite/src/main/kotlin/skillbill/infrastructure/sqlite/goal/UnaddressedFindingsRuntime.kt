package skillbill.infrastructure.sqlite.goal

import skillbill.goalrunner.model.UnaddressedFinding
import java.sql.Connection

internal class UnaddressedFindingsRuntime(private val connection: Connection) {
  fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) {
    connection.prepareStatement(
      "DELETE FROM unaddressed_findings WHERE workflow_id = ? AND review_pass_number = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setInt(2, reviewPassNumber)
      statement.executeUpdate()
    }
    connection.prepareStatement(
      """
      INSERT INTO unaddressed_findings (
        issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
        severity, issue_category, location, summary
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      findings.forEach { finding ->
        statement.setString(1, finding.issueKey)
        statement.setString(2, finding.workflowId)
        statement.setInt(3, finding.subtaskId)
        statement.setInt(4, finding.reviewPassNumber)
        statement.setInt(5, finding.findingOrdinal)
        statement.setString(6, finding.severity)
        statement.setString(7, finding.issueCategory)
        statement.setString(8, finding.location)
        statement.setString(9, finding.summary)
        statement.addBatch()
      }
      statement.executeBatch()
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
    "SELECT 1 FROM goal_issue_progress WHERE issue_key = ? LIMIT 1",
  ).use { statement ->
    statement.setString(1, issueKey)
    statement.executeQuery().use { it.next() }
  }
}
