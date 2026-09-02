package skillbill.infrastructure.sqlite.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.persistence.model.WorkflowFamily
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.goal.GoalObservabilityEventValidator
import skillbill.workflow.goal.GoalProgressEventValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import java.time.Clock

@Inject
internal data class WorkflowGoalRunnerManifestStoreContextDeps(
  val database: DatabaseSessionFactory,
  val decompositionManifestValidator: DecompositionManifestValidator,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val clock: Clock,
  val decompositionManifestWriter: DecompositionManifestProjectionWriter,
  val repositoryRoot: RepositoryRoot,
  val planningHydrator: GoalChildPlanningHydratorPort,
)

internal data class RecoverMissingResultPrefixTerminalOutcomeArgs(
  val workflowStates: WorkflowStateRepository,
  val family: WorkflowFamily,
  val record: WorkflowStateSnapshot,
  val output: Map<String, Any?>,
  val issueKey: String,
  val subtaskId: Int,
  val workflowId: String,
)

internal data class CreateWorkflowGoalRunnerOutcomeStoreBridgesArgs(
  val database: DatabaseSessionFactory,
  val workflowSnapshotValidator: WorkflowSnapshotValidator,
  val goalObservabilityEventValidator: GoalObservabilityEventValidator,
  val goalProgressEventValidator: GoalProgressEventValidator,
  val gitOperations: WorkflowGitOperations,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val decompositionManifestValidator: DecompositionManifestValidator?,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val clock: Clock,
  val decompositionManifestWriter: DecompositionManifestProjectionWriter,
  val childRepairExecutor: GoalRunnerChildRepairRunnerPort,
)

internal data class WorkflowGoalRunnerOutcomeStoreBridgesArgs(
  val database: DatabaseSessionFactory,
  val engine: WorkflowEngine,
  val gitOperations: WorkflowGitOperations,
  val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val decompositionManifestValidator: DecompositionManifestValidator?,
  val decompositionManifestFileStore: DecompositionManifestFileStore,
  val outcomeReconcile: WorkflowGoalRunnerOutcomeReconcile,
  val blockWrites: WorkflowGoalRunnerBlockWrites,
  val terminalPersistence: WorkflowGoalRunnerOutcomeTerminalPersistence,
  val progressRecording: WorkflowGoalRunnerProgressRecording,
  val childRepair: GoalRunnerChildRepairRunnerPort,
  val decompositionManifestWriter: DecompositionManifestProjectionWriter,
)

internal data class WorkflowGoalRunnerManifestStoreBuildPartsArgs(
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
