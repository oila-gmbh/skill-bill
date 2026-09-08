package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.GoalRunnerManifestPauseOps
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import java.nio.file.Path

internal class WorkflowGoalRunnerManifestPauseOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestPauseOps {
  override fun requestPause(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState? =
    ctx.controls.requestPause(parentWorkflowId, dbPathOverride)

  override fun pauseNow(
    parentWorkflowId: String,
    reason: String,
    pausedAt: String,
    overwriteExistingReason: Boolean,
    dbPathOverride: String?,
  ): GoalRunnerControlState? =
    ctx.controls.pauseNow(parentWorkflowId, reason, pausedAt, overwriteExistingReason, dbPathOverride)

  override fun requestPauseByIssueKey(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path?,
  ): GoalRunnerPausePersistenceResult? = ctx.controls.requestPauseByIssueKey(issueKey, dbPathOverride, repoRoot)

  override fun resume(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerManifestState? =
    ctx.controls.resume(parentWorkflowId, dbPathOverride)

  override fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState =
    ctx.controls.pauseAtBoundary(state, dbPathOverride)
}
