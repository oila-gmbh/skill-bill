package skillbill.infrastructure.sqlite.goalrunner

import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalParentProjectionWriter
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.engine.WorkflowEngine

internal class WorkflowGoalRunnerManifestStoreContext(deps: WorkflowGoalRunnerManifestStoreContextDeps) {
  val database = deps.database
  val decompositionManifestValidator = deps.decompositionManifestValidator
  val decompositionManifestFileStore = deps.decompositionManifestFileStore
  private val phaseOutputValidator = deps.phaseOutputValidator
  private val planningProjectionValidator = deps.planningProjectionValidator
  private val workflowSnapshotValidator = deps.workflowSnapshotValidator
  private val clock = deps.clock
  private val decompositionManifestWriter = deps.decompositionManifestWriter
  private val repositoryRoot = deps.repositoryRoot
  val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  val planningHydrator: GoalChildPlanningHydratorPort = deps.planningHydrator
  val parentProjection = GoalParentProjectionWriter(engine, decompositionManifestValidator)
  val manifestLoader = WorkflowGoalRunnerManifestLoader(
    database,
    decompositionManifestValidator,
    decompositionManifestFileStore,
    engine,
    parentProjection,
  )
  val projectionPersistence = WorkflowGoalRunnerManifestProjectionPersistence(
    database,
    engine,
    parentProjection,
    decompositionManifestValidator,
  )
  val childWorkflowPersistence = WorkflowGoalRunnerChildWorkflowPersistence(
    engine,
    planningHydrator,
    parentProjection,
    decompositionManifestValidator,
  )
  val scopedReplanPersistence = WorkflowGoalRunnerScopedReplanPersistence(projectionPersistence)
  val controls = GoalRunnerControlCoordinator(
    database,
    decompositionManifestValidator,
    clock,
  ) { unitOfWork, state ->
    projectionPersistence.saveInTransaction(unitOfWork, state)
  }

  fun writeProjectionFile(state: GoalRunnerManifestState, projectionArtifactsJson: String) {
    decompositionManifestWriter.writeProjectionFromWorkflowState(
      state.repoRoot ?: repositoryRoot.path,
      projectionArtifactsJson,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
  }
}
