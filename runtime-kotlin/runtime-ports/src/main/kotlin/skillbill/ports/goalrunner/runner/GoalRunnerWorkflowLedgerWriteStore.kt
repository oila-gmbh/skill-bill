package skillbill.ports.goalrunner.runner

import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.model.WorkflowId

interface GoalRunnerWorkflowLedgerWriteStore {
  fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest): Boolean

  fun recordWorkerSubtaskRequestOutcomes(
    workflowId: WorkflowId,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
  ): Boolean

  fun ledgerSequenceWatermarks(issueKey: IssueKey): GoalRunnerLedgerSequenceWatermarks

  fun childWorkflowLoopIterations(workflowId: WorkflowId): Map<String, Int>
}
