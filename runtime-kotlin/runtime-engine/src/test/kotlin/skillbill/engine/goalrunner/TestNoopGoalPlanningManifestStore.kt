package skillbill.engine.goalrunner

import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStoreDefaults
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import java.nio.file.Path

internal object TestNoopGoalPlanningManifestStore : GoalRunnerManifestStoreDefaults() {
  override fun loadByIssueKey(issueKey: String, repoRoot: Path?): GoalRunnerManifestState? = null

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(parentWorkflowId: String, lease: GoalRunnerExecutionLease): Boolean = true

  override fun releaseExecutionLease(parentWorkflowId: String, ownerToken: String, generation: Long): Boolean = true
}
