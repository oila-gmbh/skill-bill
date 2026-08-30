package skillbill.infrastructure.sqlite.goal

import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.review.model.ReviewFindingCitation
import java.sql.Connection

internal class UnaddressedFindingsLedgerRuntime(private val connection: Connection) {
  fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) {
    deletePassesUpTo(workflowId, reviewPassNumber)
    connection.prepareStatement(
      """
      INSERT INTO unaddressed_findings (
        issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
        severity, issue_category, location, summary, review_run_id, finding_id,
        claim_verdict, scope_disposition, citations,
        severity_adjustment_direction, severity_adjustment_justification,
        verification_disposition, verification_reason
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        statement.setString(parameterIndex++, finding.findingId)
        statement.setString(parameterIndex++, finding.claimVerdict?.wireValue)
        statement.setString(parameterIndex++, finding.scopeDisposition?.wireValue)
        statement.setString(parameterIndex++, ReviewFindingCitation.encodeList(finding.citations))
        statement.setString(parameterIndex++, finding.severityAdjustment?.direction?.wireValue)
        statement.setString(parameterIndex++, finding.severityAdjustment?.justification)
        statement.setString(parameterIndex++, finding.verificationDisposition)
        statement.setString(parameterIndex, finding.verificationReason)
        statement.addBatch()
      }
      statement.executeBatch()
    }
  }

  fun clearWorkflowLedger(workflowId: String) {
    connection.prepareStatement("DELETE FROM unaddressed_findings WHERE workflow_id = ?").use { statement ->
      statement.setString(1, workflowId)
      statement.executeUpdate()
    }
  }

  fun fetchLedger(issueKey: String): List<UnaddressedFinding> = fetchLedgerBy("issue_key", issueKey)

  fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> = fetchLedgerBy("workflow_id", workflowId)

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

  private fun deletePassesUpTo(workflowId: String, reviewPassNumber: Int) {
    connection.prepareStatement(
      "DELETE FROM unaddressed_findings WHERE workflow_id = ? AND review_pass_number <= ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.setInt(2, reviewPassNumber)
      statement.executeUpdate()
    }
  }

  private fun fetchLedgerBy(column: String, value: String): List<UnaddressedFinding> = connection.prepareStatement(
    """
    SELECT issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
           severity, issue_category, location, summary, review_run_id, finding_id,
           claim_verdict, scope_disposition, citations,
           severity_adjustment_direction, severity_adjustment_justification,
           verification_disposition, verification_reason
    FROM unaddressed_findings
    WHERE $column = ?
    ORDER BY subtask_id, review_pass_number, finding_ordinal
    """.trimIndent(),
  ).use { statement ->
    statement.setString(1, value)
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          add(readUnaddressedFinding(rows))
        }
      }
    }
  }
}
