package skillbill.ports.goalrunner.runner

import IssueKey
import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary

object NoopGoalRunnerAttemptLedgerStore : GoalRunnerAttemptLedgerStore {
  override fun readAttemptLedgerSummary(issueKey: IssueKey): GoalRunnerAttemptLedgerSummary {
    return GoalRunnerAttemptLedgerSummary()
  }
}
