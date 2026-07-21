package skillbill.ports.persistence

import skillbill.goalrunner.model.UnaddressedFinding

interface UnaddressedFindingsRepository {
  fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>)

  /**
   * Review-generation invalidation restarts pass numbering at 1, so pass-scoped retraction can no
   * longer reach the superseded generation's rows; they must be dropped wholesale instead.
   */
  fun clearWorkflowLedger(workflowId: String)

  fun fetchLedger(issueKey: String): List<UnaddressedFinding>

  fun issueExists(issueKey: String): Boolean
}

object UnavailableUnaddressedFindingsRepository : UnaddressedFindingsRepository {
  override fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun clearWorkflowLedger(workflowId: String) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun fetchLedger(issueKey: String): List<UnaddressedFinding> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun issueExists(issueKey: String): Boolean = false
}
