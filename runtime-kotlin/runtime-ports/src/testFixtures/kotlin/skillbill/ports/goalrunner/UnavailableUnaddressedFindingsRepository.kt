package skillbill.ports.goalrunner

import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding

object UnavailableUnaddressedFindingsRepository : UnaddressedFindingsRepository {
  override fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun clearWorkflowLedger(workflowId: String) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun fetchOutcomes(workflowId: String): List<ReviewFindingOutcomeRecord> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun fetchLedger(issueKey: String): List<UnaddressedFinding> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun workflowIdsForIssue(issueKey: String): List<String> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun issueExists(issueKey: String): Boolean = error("Unaddressed-findings persistence is unavailable.")
}
