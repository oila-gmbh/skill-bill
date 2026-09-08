package skillbill.application.goalrunner

import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerOutcomeStoreBridgeBuilder
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.goal.NoopGoalObservabilityEventValidator
import skillbill.workflow.goal.NoopGoalProgressEventValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import java.time.Clock

data class OutcomeStoreTestArtifactPorts(
  val goalObservabilityEventValidator: GoalObservabilityEventValidator = NoopGoalObservabilityEventValidator,
  val goalProgressEventValidator: GoalProgressEventValidator = NoopGoalProgressEventValidator,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator = realFeatureTaskRuntimePhaseOutputValidator,
  val decompositionManifestStore: DecompositionManifestStore = UnavailableDecompositionManifestStore,
)

fun sqliteWorkflowGoalRunnerManifestStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  decompositionManifestValidator: DecompositionManifestValidator,
  decompositionManifestStore: DecompositionManifestStore,
  clock: Clock,
  decompositionManifestWriter: DecompositionManifestProjectionWriter,
  repositoryRoot: RepositoryRoot,
  planningHydrator: GoalChildPlanningHydratorPort,
): GoalRunnerManifestStore = WorkflowGoalRunnerManifestStore(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  decompositionManifestValidator = decompositionManifestValidator,
  decompositionManifestStore = decompositionManifestStore,
  clock = clock,
  decompositionManifestWriter = decompositionManifestWriter,
  repositoryRoot = repositoryRoot,
  planningHydrator = planningHydrator,
)

fun sqliteWorkflowGoalRunnerOutcomeStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  gitOperations: WorkflowGitOperations,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  clock: Clock,
  artifactPorts: OutcomeStoreTestArtifactPorts,
  decompositionManifestValidator: DecompositionManifestValidator,
  decompositionManifestWriter: DecompositionManifestProjectionWriter,
  childRepairExecutor: GoalRunnerChildRepairOperations,
): WorkflowGoalRunnerOutcomeStore = WorkflowGoalRunnerOutcomeStore(
  bridgeBuilder = WorkflowGoalRunnerOutcomeStoreBridgeBuilder(
    database = database,
    workflowSnapshotValidator = workflowSnapshotValidator,
    goalObservabilityEventValidator = artifactPorts.goalObservabilityEventValidator,
    goalProgressEventValidator = artifactPorts.goalProgressEventValidator,
    gitOperations = gitOperations,
    phaseOutputValidator = artifactPorts.phaseOutputValidator,
    workerSupervisor = workerSupervisor,
    clock = clock,
  ),
  decompositionManifestValidator = decompositionManifestValidator,
  decompositionManifestStore = artifactPorts.decompositionManifestStore,
  decompositionManifestWriter = decompositionManifestWriter,
  childRepairExecutor = childRepairExecutor,
)
