package skillbill.infrastructure.sqlite.goal

import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import java.sql.Connection

internal class UnaddressedFindingsRuntime(connection: Connection) {
  private val ledger = UnaddressedFindingsLedgerRuntime(connection)
  private val outcomes = UnaddressedFindingsOutcomeRuntime(connection)

  fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) =
    ledger.replaceLedgerForPass(workflowId, reviewPassNumber, findings)

  fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>) = this.outcomes.recordOutcomes(outcomes)

  fun fetchOutcomes(workflowId: String): List<ReviewFindingOutcomeRecord> = outcomes.fetchOutcomes(workflowId)

  fun clearWorkflowLedger(workflowId: String) = ledger.clearWorkflowLedger(workflowId)

  fun fetchLedger(issueKey: String): List<UnaddressedFinding> = ledger.fetchLedger(issueKey)

  fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> = ledger.fetchWorkflowLedger(workflowId)

  fun workflowIdsForIssue(issueKey: String): List<String> = ledger.workflowIdsForIssue(issueKey)

  fun issueExists(issueKey: String): Boolean = ledger.issueExists(issueKey)
}
