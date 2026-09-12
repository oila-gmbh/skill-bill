package skillbill.engine.goalrunner

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.realFeatureTaskRuntimePhaseOutputValidator
import skillbill.application.realPlanningProjectionValidator
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.application.testRepositoryRoot
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.config.model.RepoLocalConfig
import skillbill.engine.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.engine.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.FeatureTaskRuntimeStatusService
import skillbill.engine.featuretask.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.goalrunner.planning.GoalChildPlanningHydratorPortAdapter
import skillbill.engine.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.NoopGoalRunnerAttemptLedgerStore
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.scaffold.model.PlatformManifest
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import java.time.Clock

fun goalRunnerDefaultPhaseRecorder(): FeatureTaskRuntimePhaseRecorder = testPhaseRecorder(
  FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
  testWorkflowSnapshotValidator,
)

data class GoalRunnerStatusTestPorts(
  val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  val childRepairStore: GoalRunnerChildRepairStore = NoopGoalRunnerChildRepairStore,
  val runtimeStatusService: FeatureTaskRuntimeStatusService? = null,
  val validationGatePlatformManifests: List<PlatformManifest> = emptyList(),
  val repoLocalConfig: RepoLocalConfigPort = object : RepoLocalConfigPort {
    override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
      ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
  },
)

fun testGoalRunnerStatusService(
  manifestStore: GoalRunnerManifestStore,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder = goalRunnerDefaultPhaseRecorder(),
  clock: Clock = testHarnessClock,
  ports: GoalRunnerStatusTestPorts = GoalRunnerStatusTestPorts(),
): GoalRunnerStatusService {
  val projectionAssembler = GoalRunnerStatusProjectionAssembler(
    dataSources = GoalRunnerStatusProjectionDataSources(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      phaseRecorder = phaseRecorder,
      attemptLedgerStore = NoopGoalRunnerAttemptLedgerStore,
    ),
    gitOperations = ports.gitOperations,
    clock = clock,
    workerSupervisor = ports.workerSupervisor,
    planningStatusReasonCoherence = GoalPlanningStatusReasonCoherence.NONE,
    diagnostics = NoopRuntimeDiagnostics,
    runtimeStatusService = ports.runtimeStatusService,
    repositoryRoot = testRepositoryRoot,
    validationDependencies = GoalRunnerStatusProjectionValidationDependencies(
      validationGateResolver = ValidationGateResolver(
        InstalledPlatformPackCatalogPort { ports.validationGatePlatformManifests },
      ),
      repoLocalConfig = ports.repoLocalConfig,
    ),
  )
  return GoalRunnerStatusService(
    manifestStore = manifestStore,
    outcomeStore = outcomeStore,
    phaseRecorder = phaseRecorder,
    gitOperations = ports.gitOperations,
    clock = clock,
    workerSupervisor = ports.workerSupervisor,
    childRepairStore = ports.childRepairStore,
    repositoryRoot = testRepositoryRoot,
    repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
    projectionAssembler = projectionAssembler,
    resetReplanCoordinator = GoalRunnerResetReplanCoordinator(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      gitOperations = ports.gitOperations,
      diagnostics = NoopRuntimeDiagnostics,
      projectionAssembler = projectionAssembler,
      repositoryRoot = testRepositoryRoot,
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
    ),
  )
}

private val testGoalChildPlanningHydratorPort = GoalChildPlanningHydratorPortAdapter(
  realFeatureTaskRuntimePhaseOutputValidator,
  realPlanningProjectionValidator,
  testHarnessClock,
)

fun testGoalRunnerChildRepairExecutor(
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
): GoalRunnerChildRepairOperations = GoalRunnerChildRepairOperations(
  testWorkflowSnapshotValidator,
  gitOperations,
  testDecompositionManifestValidator,
  testHarnessClock,
)

fun testWorkflowGoalRunnerManifestStore(
  database: DatabaseSessionFactory,
  decompositionManifestStore: DecompositionManifestStore,
  clock: Clock,
  decompositionManifestValidator: DecompositionManifestValidator = testDecompositionManifestValidator,
): GoalRunnerManifestStore = sqliteWorkflowGoalRunnerManifestStore(
  database = database,
  workflowSnapshotValidator = testWorkflowSnapshotValidator,
  decompositionManifestValidator = decompositionManifestValidator,
  decompositionManifestStore = decompositionManifestStore,
  clock = clock,
  decompositionManifestWriter = DecompositionManifestWriter(),
  repositoryRoot = testRepositoryRoot,
  planningHydrator = testGoalChildPlanningHydratorPort,
)

fun testWorkflowGoalRunnerOutcomeStore(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator = testWorkflowSnapshotValidator,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  artifactPorts: OutcomeStoreTestArtifactPorts = OutcomeStoreTestArtifactPorts(),
) = sqliteWorkflowGoalRunnerOutcomeStore(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  gitOperations = gitOperations,
  workerSupervisor = workerSupervisor,
  clock = testHarnessClock,
  artifactPorts = artifactPorts,
  decompositionManifestValidator = testDecompositionManifestValidator,
  decompositionManifestWriter = testDecompositionManifestWriter,
  childRepairExecutor = testGoalRunnerChildRepairExecutor(gitOperations),
)

fun testPhaseRecorder(
  database: DatabaseSessionFactory,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  handoffEnvelopeValidator: FeatureTaskRuntimeHandoffEnvelopeValidator =
    AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
  handoffFoundationValidator: FeatureTaskRuntimeHandoffFoundationValidator =
    AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): FeatureTaskRuntimePhaseRecorder = featureTaskRuntimePhaseRecorder(
  database = database,
  workflowSnapshotValidator = workflowSnapshotValidator,
  handoffEnvelopeValidator = handoffEnvelopeValidator,
  handoffFoundationValidator = handoffFoundationValidator,
  clock = testHarnessClock,
  diagnostics = diagnostics,
)
