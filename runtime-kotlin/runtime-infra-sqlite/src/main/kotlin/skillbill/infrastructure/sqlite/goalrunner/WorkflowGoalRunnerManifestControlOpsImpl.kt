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
  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    ctx.controls.controlState(parentWorkflowId)

  override fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState =
    ctx.controls.persistControlState(parentWorkflowId, state)

  override fun bindRepositoryIdentity(parentWorkflowId: String, repositoryIdentity: String): GoalRunnerControlState =
    ctx.controls.bindRepositoryIdentity(parentWorkflowId, repositoryIdentity)

  override fun authorizeSubtaskLaunch(state: GoalRunnerManifestState, subtaskId: Int): GoalRunnerLaunchAuthorization =
    ctx.controls.authorizeSubtaskLaunch(state, subtaskId)

  override fun authorizePlanningLaunch(parentWorkflowId: String): AgentRunSpawnAuthorization =
    ctx.controls.planningSpawnAuthorization(parentWorkflowId)

  override fun persistStopAfterSubtask(parentWorkflowId: String, subtaskId: Int): GoalRunnerControlState =
    ctx.controls.persistStopAfterSubtask(parentWorkflowId, subtaskId)
}
