package skillbill.ports.persistence

import skillbill.goalrunner.model.UnaddressedFinding

interface UnaddressedFindingsRepository {
  fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>)

  fun fetchLedger(issueKey: String): List<UnaddressedFinding>

  fun issueExists(issueKey: String): Boolean
}

object UnavailableUnaddressedFindingsRepository : UnaddressedFindingsRepository {
  override fun replaceLedgerForPass(workflowId: String, reviewPassNumber: Int, findings: List<UnaddressedFinding>) {
    error("Unaddressed-findings persistence is unavailable.")
  }

  override fun fetchLedger(issueKey: String): List<UnaddressedFinding> =
    error("Unaddressed-findings persistence is unavailable.")

  override fun issueExists(issueKey: String): Boolean = false
}
