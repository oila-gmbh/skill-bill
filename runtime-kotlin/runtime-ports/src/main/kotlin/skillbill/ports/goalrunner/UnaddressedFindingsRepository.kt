package skillbill.ports.goalrunner

import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding

interface UnaddressedFindingsRepository {
  fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>)

  /**
   * Records the terminal accepted/rejected/carried disposition for findings a run produced. Survives
   * ledger retraction, so coverage does not depend on the ledger row still existing.
   */
  fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>)

  fun fetchOutcomes(workflowId: String): List<ReviewFindingOutcomeRecord>

  /**
   * Review-generation invalidation restarts pass numbering at 1, so pass-scoped retraction can no
   * longer reach the superseded generation's rows; they must be dropped wholesale instead.
   */
  fun clearWorkflowLedger(workflowId: String)

  fun fetchLedger(issueKey: String): List<UnaddressedFinding>

  fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding>

  fun workflowIdsForIssue(issueKey: String): List<String>

  fun issueExists(issueKey: String): Boolean
}

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

  override fun issueExists(issueKey: String): Boolean = false
}
