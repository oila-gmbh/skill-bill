package skillbill.infrastructure.sqlite.goalrunner

import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalParentProjectionWriter
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import java.time.Clock

internal class WorkflowGoalRunnerManifestStoreContext(
  val database: DatabaseSessionFactory,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestStore: DecompositionManifestStore,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val clock: Clock,
  private val decompositionManifestWriter: DecompositionManifestProjectionWriter,
  private val repositoryRoot: RepositoryRoot,
  val planningHydrator: GoalChildPlanningHydratorPort,
) {
  val engine: WorkflowEngine = WorkflowEngine(workflowSnapshotValidator)
  val parentProjection = GoalParentProjectionWriter(engine, decompositionManifestValidator)
  val manifestLoader = WorkflowGoalRunnerManifestLoader(
    database,
    decompositionManifestValidator,
    decompositionManifestStore,
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
      decompositionManifestStore,
    )
  }
}
