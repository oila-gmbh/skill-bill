package skillbill.ports.goalrunner.runner

import skillbill.goalrunner.model.GoalRunnerAttemptLedgerSummary

object NoopGoalRunnerAttemptLedgerStore : GoalRunnerAttemptLedgerStore {
  override fun readAttemptLedgerSummary(issueKey: String, dbPathOverride: String?): GoalRunnerAttemptLedgerSummary {
    return GoalRunnerAttemptLedgerSummary()
  }
}
