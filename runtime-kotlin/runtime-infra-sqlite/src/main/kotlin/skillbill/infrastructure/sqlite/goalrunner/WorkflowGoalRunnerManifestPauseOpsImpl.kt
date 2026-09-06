package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import skillbill.workflow.decomposition.model.IssueKey
import java.nio.file.Path

internal class WorkflowGoalRunnerManifestPauseOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestPauseOps {
  override fun requestPause(parentWorkflowId: String): GoalRunnerControlState? =
    ctx.controls.requestPause(parentWorkflowId)

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
  ): GoalRunnerControlState? = ctx.controls.pauseNow(parentWorkflowId, reason, pausedAt, overwriteExistingReason)

  override fun requestPauseByIssueKey(issueKey: IssueKey, repoRoot: Path?): GoalRunnerPausePersistenceResult? =
    ctx.controls.requestPauseByIssueKey(issueKey, repoRoot)

  override fun resume(parentWorkflowId: String): GoalRunnerManifestState? = ctx.controls.resume(parentWorkflowId)

  override fun pauseAtBoundary(state: GoalRunnerManifestState): GoalRunnerManifestState =
    ctx.controls.pauseAtBoundary(state)
}
