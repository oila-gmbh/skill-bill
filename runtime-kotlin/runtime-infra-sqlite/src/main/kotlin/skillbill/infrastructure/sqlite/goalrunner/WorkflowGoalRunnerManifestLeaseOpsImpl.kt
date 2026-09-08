package skillbill.infrastructure.sqlite.goalrunner

import skillbill.goalrunner.model.GoalRunnerExecutionLease

internal class WorkflowGoalRunnerManifestLeaseOpsImpl(
  private val ctx: WorkflowGoalRunnerManifestStoreContext,
) : GoalRunnerManifestLeaseOps {
  override fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease? =
    ctx.controls.executionLease(parentWorkflowId, dbPathOverride)
  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = ctx.controls.acquireExecutionLease(parentWorkflowId, lease, expectedOwnerToken, dbPathOverride)
  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = ctx.controls.heartbeatExecutionLease(parentWorkflowId, lease, dbPathOverride)
  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = ctx.controls.releaseExecutionLease(parentWorkflowId, ownerToken, generation, dbPathOverride)
}
