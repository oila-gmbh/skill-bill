package skillbill.infrastructure.sqlite.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.persistence.model.WorkflowGoalRunnerManifestStoreDeps
import skillbill.ports.goalrunner.runner.GoalRunnerManifestControlOps
import skillbill.ports.goalrunner.runner.GoalRunnerManifestLeaseOps
import skillbill.ports.goalrunner.runner.GoalRunnerManifestLookup
import skillbill.ports.goalrunner.runner.GoalRunnerManifestPauseOps
import skillbill.ports.goalrunner.runner.GoalRunnerManifestReviewOps
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestWriteOps

class WorkflowGoalRunnerManifestStore private constructor(
  lookup: WorkflowGoalRunnerManifestLookupOps,
  leases: WorkflowGoalRunnerManifestLeaseOpsImpl,
  control: WorkflowGoalRunnerManifestControlOpsImpl,
  writes: WorkflowGoalRunnerManifestWriteOpsImpl,
  review: WorkflowGoalRunnerManifestReviewOpsImpl,
) : GoalRunnerManifestStore,
  GoalRunnerManifestLookup by lookup,
  GoalRunnerManifestLeaseOps by leases,
  GoalRunnerManifestPauseOps by control,
  GoalRunnerManifestControlOps by control,
  GoalRunnerManifestWriteOps by writes,
  GoalRunnerManifestReviewOps by review {
  @Inject
  constructor(deps: WorkflowGoalRunnerManifestStoreDeps) : this(
    buildParts(
      WorkflowGoalRunnerManifestStoreBuildPartsArgs(
        database = deps.database,
        workflowSnapshotValidator = deps.workflowSnapshotValidator,
        decompositionManifestValidator = deps.decompositionManifestValidator,
        decompositionManifestFileStore = deps.decompositionManifestFileStore,
        phaseOutputValidator = deps.phaseOutputValidator,
        planningProjectionValidator = deps.planningProjectionValidator,
        clock = deps.clock,
        decompositionManifestWriter = deps.decompositionManifestWriter,
        repositoryRoot = deps.repositoryRoot,
        planningHydrator = deps.planningHydrator,
      ),
    ),
  )

  private constructor(parts: ManifestStoreParts) : this(
    parts.lookup,
    parts.leases,
    parts.control,
    parts.writes,
    parts.review,
  )

  private class ManifestStoreParts(
    val lookup: WorkflowGoalRunnerManifestLookupOps,
    val leases: WorkflowGoalRunnerManifestLeaseOpsImpl,
    val control: WorkflowGoalRunnerManifestControlOpsImpl,
    val writes: WorkflowGoalRunnerManifestWriteOpsImpl,
    val review: WorkflowGoalRunnerManifestReviewOpsImpl,
  )

  private companion object {
    fun buildParts(args: WorkflowGoalRunnerManifestStoreBuildPartsArgs): ManifestStoreParts {
      val ctx = WorkflowGoalRunnerManifestStoreContext(
        WorkflowGoalRunnerManifestStoreContextDeps(
          database = args.database,
          decompositionManifestValidator = args.decompositionManifestValidator,
          decompositionManifestFileStore = args.decompositionManifestFileStore,
          phaseOutputValidator = args.phaseOutputValidator,
          planningProjectionValidator = args.planningProjectionValidator,
          workflowSnapshotValidator = args.workflowSnapshotValidator,
          clock = args.clock,
          decompositionManifestWriter = args.decompositionManifestWriter,
          repositoryRoot = args.repositoryRoot,
          planningHydrator = args.planningHydrator,
        ),
      )
      val writes = WorkflowGoalRunnerManifestWriteOpsImpl(ctx)
      return ManifestStoreParts(
        lookup = WorkflowGoalRunnerManifestLookupOps(ctx, writes::save),
        leases = WorkflowGoalRunnerManifestLeaseOpsImpl(ctx),
        control = WorkflowGoalRunnerManifestControlOpsImpl(ctx),
        writes = writes,
        review = WorkflowGoalRunnerManifestReviewOpsImpl(ctx),
      )
    }
  }
}
