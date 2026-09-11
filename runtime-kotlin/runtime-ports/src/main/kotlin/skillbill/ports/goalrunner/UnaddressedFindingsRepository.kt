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
