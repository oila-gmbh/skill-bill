package skillbill.application.goalrunner

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.goalrunner.planning.GoalChildPlanningHydrator
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.workflow.engine.WorkflowEngine
import java.nio.file.Path

internal class WorkflowGoalRunnerManifestStoreContext(deps: WorkflowGoalRunnerManifestStoreContextDeps) {
  val database = deps.database
  val decompositionManifestValidator = deps.decompositionManifestValidator
  val decompositionManifestFileStore = deps.decompositionManifestFileStore
  private val phaseOutputValidator = deps.phaseOutputValidator
  private val planningProjectionValidator = deps.planningProjectionValidator
  private val workflowSnapshotValidator = deps.workflowSnapshotValidator
  private val clock = deps.clock
  val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  val planningHydrator = GoalChildPlanningHydrator(phaseOutputValidator, planningProjectionValidator)
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
    DecompositionManifestWriter.writeProjectionFromWorkflowState(
      state.repoRoot ?: Path.of("").toAbsolutePath(),
      projectionArtifactsJson,
      decompositionManifestValidator,
      decompositionManifestFileStore,
    )
  }
}
