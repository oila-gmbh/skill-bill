package skillbill.infrastructure.sqlite.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.persistence.model.WorkflowGoalRunnerManifestStoreDeps
import skillbill.ports.goalrunner.runner.GoalRunnerManifestControlCommands
import skillbill.ports.goalrunner.runner.GoalRunnerManifestExecutionLease
import skillbill.ports.goalrunner.runner.GoalRunnerManifestLookup
import skillbill.ports.goalrunner.runner.GoalRunnerManifestPauseOps
import skillbill.ports.goalrunner.runner.GoalRunnerManifestPersistenceCommands
import skillbill.ports.goalrunner.runner.GoalRunnerManifestReviewCommands
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore

class WorkflowGoalRunnerManifestStore @Inject constructor(
  deps: WorkflowGoalRunnerManifestStoreDeps,
) : GoalRunnerManifestStore by buildParts(deps)

private class ManifestStoreDelegate(
  lookup: WorkflowGoalRunnerManifestLookupOps,
  leases: WorkflowGoalRunnerManifestLeaseOpsImpl,
  control: WorkflowGoalRunnerManifestControlOpsImpl,
  writes: WorkflowGoalRunnerManifestWriteOpsImpl,
  review: WorkflowGoalRunnerManifestReviewOpsImpl,
) : GoalRunnerManifestStore,
  GoalRunnerManifestLookup by lookup,
  GoalRunnerManifestPauseOps by control,
  GoalRunnerManifestExecutionLease by leases,
  GoalRunnerManifestControlCommands by control,
  GoalRunnerManifestPersistenceCommands by writes,
  GoalRunnerManifestReviewCommands by review

private fun buildParts(deps: WorkflowGoalRunnerManifestStoreDeps): ManifestStoreDelegate {
  val ctx = WorkflowGoalRunnerManifestStoreContext(
    WorkflowGoalRunnerManifestStoreContextDeps(
      database = deps.database,
      decompositionManifestValidator = deps.decompositionManifestValidator,
      decompositionManifestStore = deps.decompositionManifestStore,
      phaseOutputValidator = deps.phaseOutputValidator,
      planningProjectionValidator = deps.planningProjectionValidator,
      workflowSnapshotValidator = deps.workflowSnapshotValidator,
      clock = deps.clock,
      decompositionManifestWriter = deps.decompositionManifestWriter,
      repositoryRoot = deps.repositoryRoot,
      planningHydrator = deps.planningHydrator,
    ),
  )
  val writes = WorkflowGoalRunnerManifestWriteOpsImpl(ctx)
  return ManifestStoreDelegate(
    lookup = WorkflowGoalRunnerManifestLookupOps(ctx, writes::save),
    leases = WorkflowGoalRunnerManifestLeaseOpsImpl(ctx),
    control = WorkflowGoalRunnerManifestControlOpsImpl(ctx),
    writes = writes,
    review = WorkflowGoalRunnerManifestReviewOpsImpl(ctx),
  )
}
