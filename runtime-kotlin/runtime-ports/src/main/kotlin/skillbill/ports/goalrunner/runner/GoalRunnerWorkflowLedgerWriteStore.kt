package skillbill.ports.goalrunner.runner

import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.goalrunner.runner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerLedgerSequenceWatermarks

interface GoalRunnerWorkflowLedgerWriteStore {
  fun recordAttemptLedgerEntry(request: GoalRunnerAttemptLedgerRecordRequest, dbPathOverride: String? = null): Boolean

  fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String? = null,
  ): Boolean

  fun ledgerSequenceWatermarks(issueKey: String, dbPathOverride: String? = null): GoalRunnerLedgerSequenceWatermarks

  fun childWorkflowLoopIterations(workflowId: String, dbPathOverride: String? = null): Map<String, Int>
}
