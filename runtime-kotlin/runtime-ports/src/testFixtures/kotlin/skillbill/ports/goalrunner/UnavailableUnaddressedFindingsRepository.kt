package skillbill.ports.goalrunner

import IssueKey
import WorkflowId
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding

object UnavailableUnaddressedFindingsRepository : UnaddressedFindingsRepository {
  override fun replaceLedgerForPass(workflowId: WorkflowId, reviewPassNumber: Int, findings: List<UnaddressedFinding>) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun clearWorkflowLedger(workflowId: WorkflowId) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun fetchOutcomes(workflowId: WorkflowId): List<ReviewFindingOutcomeRecord> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun fetchLedger(issueKey: IssueKey): List<UnaddressedFinding> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun fetchWorkflowLedger(workflowId: WorkflowId): List<UnaddressedFinding> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun workflowIdsForIssue(issueKey: IssueKey): List<String> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun issueExists(issueKey: IssueKey): Boolean = error("Unaddressed-findings persistence is unavailable.")
}
