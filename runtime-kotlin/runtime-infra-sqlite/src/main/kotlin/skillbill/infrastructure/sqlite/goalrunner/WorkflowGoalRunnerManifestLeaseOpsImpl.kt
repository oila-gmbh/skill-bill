package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerExecutionLease

internal class WorkflowGoalRunnerManifestLeaseOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestExecutionLease {
  override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? =
    ctx.controls.executionLease(parentWorkflowId)  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = ctx.controls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken)
  override fun heartbeatExecutionLease(parentWorkflowId: String, lease: GoalRunnerExecutionLease): Boolean =
    ctx.controls.heartbeatExecutionLease(parentWorkflowId, lease)
  override fun releaseExecutionLease(parentWorkflowId: String, ownerToken: String, generation: Long): Boolean =
    ctx.controls.releaseExecutionLease(parentWorkflowId, ownerToken, generation)
}
