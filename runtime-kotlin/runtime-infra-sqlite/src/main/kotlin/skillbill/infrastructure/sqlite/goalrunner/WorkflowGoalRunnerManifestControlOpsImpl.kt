package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.runner.GoalRunnerManifestPauseOps
import skillbill.ports.goalrunner.runner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState

internal class WorkflowGoalRunnerManifestControlOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestControlOps,
  GoalRunnerManifestPauseOps by WorkflowGoalRunnerManifestPauseOpsImpl(ctx) {
  override fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState =
    ctx.controls.controlState(parentWorkflowId, dbPathOverride)

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
    dbPathOverride: String?,
  ): GoalRunnerControlState = ctx.controls.persistControlState(parentWorkflowId, state, dbPathOverride)

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
}
