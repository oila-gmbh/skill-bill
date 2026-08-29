package skillbill.application.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.GoalRunnerManifestControlOps
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerPausePersistenceResult
import java.nio.file.Path

internal class WorkflowGoalRunnerManifestControlOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestControlOps {
  override fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    ctx.controls.controlState(parentWorkflowId, dbPathOverride)
  override fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
    dbPathOverride: String?,
  ): GoalRunnerControlState = ctx.controls.bindRepositoryIdentity(parentWorkflowId, repositoryIdentity, dbPathOverride)
  override fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerLaunchAuthorization = ctx.controls.authorizeSubtaskLaunch(state, subtaskId, dbPathOverride)
  override fun authorizePlanningLaunch(parentWorkflowId: String, dbPathOverride: String?): AgentRunSpawnAuthorization =
    ctx.controls.planningSpawnAuthorization(parentWorkflowId, dbPathOverride)
  override fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerControlState = ctx.controls.persistStopAfterSubtask(parentWorkflowId, subtaskId, dbPathOverride)
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
