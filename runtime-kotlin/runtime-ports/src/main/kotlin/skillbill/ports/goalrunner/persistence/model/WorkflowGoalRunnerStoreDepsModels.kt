package skillbill.ports.goalrunner.persistence.model

import me.tatarka.inject.annotations.Inject
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import java.time.Clock

@Inject
data class WorkflowGoalRunnerManifestStoreDeps(
  val database: DatabaseSessionFactory,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val clock: Clock,
  val decompositionManifestWriter: DecompositionManifestProjectionWriter,
  val repositoryRoot: RepositoryRoot,
  val planningHydrator: GoalChildPlanningHydratorPort,
)

@Inject
data class WorkflowGoalRunnerOutcomeStoreDeps(
  val database: DatabaseSessionFactory,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  val goalProgressEventValidator: GoalProgressEventValidator,
  val gitOperations: WorkflowGitOperations,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val clock: Clock,
  val decompositionManifestWriter: DecompositionManifestProjectionWriter,
  val childRepairExecutor: GoalRunnerChildRepairRunnerPort,
)
