package skillbill.infrastructure.sqlite.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestControlCommands
import skillbill.ports.goalrunner.runner.GoalRunnerManifestExecutionLease
import skillbill.ports.goalrunner.runner.GoalRunnerManifestLookup
import skillbill.ports.goalrunner.runner.GoalRunnerManifestPauseOps
import skillbill.ports.goalrunner.runner.GoalRunnerManifestPersistenceCommands
import skillbill.ports.goalrunner.runner.GoalRunnerManifestReviewCommands
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.time.Clock

class WorkflowGoalRunnerManifestStore @Inject constructor(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  decompositionManifestValidator: DecompositionManifestValidator,
  decompositionManifestStore: DecompositionManifestStore,
  clock: Clock,
  decompositionManifestWriter: DecompositionManifestProjectionWriter,
  repositoryRoot: RepositoryRoot,
  planningHydrator: GoalChildPlanningHydratorPort,
) : GoalRunnerManifestStore by buildParts(
  WorkflowGoalRunnerManifestStoreContext(
    database = database,
    decompositionManifestValidator = decompositionManifestValidator,
    decompositionManifestStore = decompositionManifestStore,
    workflowSnapshotValidator = workflowSnapshotValidator,
    clock = clock,
    decompositionManifestWriter = decompositionManifestWriter,
    repositoryRoot = repositoryRoot,
    planningHydrator = planningHydrator,
  ),
)

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

private fun buildParts(ctx: WorkflowGoalRunnerManifestStoreContext): ManifestStoreDelegate {
  val writes = WorkflowGoalRunnerManifestWriteOpsImpl(ctx)
  return ManifestStoreDelegate(
    lookup = WorkflowGoalRunnerManifestLookupOps(ctx, writes::save),
    leases = WorkflowGoalRunnerManifestLeaseOpsImpl(ctx),
    control = WorkflowGoalRunnerManifestControlOpsImpl(ctx),
    writes = writes,
    review = WorkflowGoalRunnerManifestReviewOpsImpl(ctx),
  )
}
