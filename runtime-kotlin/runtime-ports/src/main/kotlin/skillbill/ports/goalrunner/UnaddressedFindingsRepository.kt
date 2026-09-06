package skillbill.ports.goalrunner

import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.model.WorkflowId

interface UnaddressedFindingsRepository {
  fun replaceLedgerForPass(workflowId: WorkflowId, reviewPassNumber: Int, findings: List<UnaddressedFinding>)

  /**
   * Records the terminal accepted/rejected/carried disposition for findings a run produced. Survives
   * ledger retraction, so coverage does not depend on the ledger row still existing.
   */
  fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>)

  fun fetchOutcomes(workflowId: WorkflowId): List<ReviewFindingOutcomeRecord>

  /**
   * Review-generation invalidation restarts pass numbering at 1, so pass-scoped retraction can no
   * longer reach the superseded generation's rows; they must be dropped wholesale instead.
   */
  fun clearWorkflowLedger(workflowId: WorkflowId)

  fun fetchLedger(issueKey: IssueKey): List<UnaddressedFinding>

  fun fetchWorkflowLedger(workflowId: WorkflowId): List<UnaddressedFinding>

  fun workflowIdsForIssue(issueKey: IssueKey): List<String>

  fun issueExists(issueKey: IssueKey): Boolean
}
