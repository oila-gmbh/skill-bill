package skillbill.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.application.AgentRunGoalRunnerSubtaskLauncher
import skillbill.application.AgentRunService
import skillbill.application.ConfigResolutionService
import skillbill.application.FeatureTaskRuntimePhaseRecorder
import skillbill.application.FeatureTaskRuntimeRunner
import skillbill.application.FeatureTaskRuntimeStatusService
import skillbill.application.GoalLifecycleTelemetryEmitter
import skillbill.application.GoalRunner
import skillbill.application.GoalRunnerStatusService
import skillbill.application.InstallAgentService
import skillbill.application.InstallService
import skillbill.application.LearningService
import skillbill.application.LifecycleTelemetryService
import skillbill.application.McpRegistrationService
import skillbill.application.NativeAgentInstallService
import skillbill.application.ParallelCodeReviewRunner
import skillbill.application.RepoSourceDiscoveryService
import skillbill.application.RepoValidationService
import skillbill.application.ReviewService
import skillbill.application.ScaffoldCatalogService
import skillbill.application.ScaffoldService
import skillbill.application.SkillRemoveService
import skillbill.application.SystemService
import skillbill.application.TelemetryLevelMutationService
import skillbill.application.TelemetryService
import skillbill.application.UnsupportedScaffoldService
import skillbill.application.WorkflowGoalRunnerManifestStore
import skillbill.application.WorkflowGoalRunnerOutcomeStore
import skillbill.application.WorkflowService
import skillbill.domain.skillremove.SkillRemoveFileSystem
import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.FileSystemDiffResolver
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.fs.FileSystemInstallAgentTargets
import skillbill.infrastructure.fs.FileSystemInstallApplyExecution
import skillbill.infrastructure.fs.FileSystemInstallMcpRegistration
import skillbill.infrastructure.fs.FileSystemInstallNativeAgentLinks
import skillbill.infrastructure.fs.FileSystemInstallPlanningFacts
import skillbill.infrastructure.fs.FileSystemInstallPlatformSkillMaterialization
import skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence
import skillbill.infrastructure.fs.FileSystemInstallSkillLink
import skillbill.infrastructure.fs.FileSystemInstallStagingIntent
import skillbill.infrastructure.fs.FileSystemRepoLocalConfig
import skillbill.infrastructure.fs.FileSystemRepoSourceDiscoveryGateway
import skillbill.infrastructure.fs.FileSystemRepoValidationGateway
import skillbill.infrastructure.fs.FileSystemReviewInputSource
import skillbill.infrastructure.fs.FileSystemReviewRubricAdapter
import skillbill.infrastructure.fs.FileSystemScaffoldCatalogGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGeneratedStaging
import skillbill.infrastructure.fs.FileSystemScaffoldInstallLink
import skillbill.infrastructure.fs.FileSystemScaffoldManifestPersistence
import skillbill.infrastructure.fs.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.fs.FileSystemScaffoldSourceLoader
import skillbill.infrastructure.fs.FileSystemSkillRemoveFileSystem
import skillbill.infrastructure.fs.FileSystemSpecScratchStore
import skillbill.infrastructure.fs.FileSystemUnsupportedScaffoldGateway
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.fs.GhGoalPullRequestPort
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.infrastructure.fs.GoalObservabilityEventValidatorAdapter
import skillbill.infrastructure.fs.GoalProgressEventValidatorAdapter
import skillbill.infrastructure.fs.InstallPlanWireValidatorAdapter
import skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.install.model.InstallPlanWireValidator
import skillbill.launcher.FileSystemAgentRunLauncher
import skillbill.model.RuntimeContext
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.review.ReviewRubricPort
import skillbill.ports.scaffold.RepoSourceDiscoveryGateway
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.scaffold.install.ScaffoldInstallLinkPort
import skillbill.ports.scaffold.manifest.ScaffoldManifestPersistencePort
import skillbill.ports.scaffold.repo.ScaffoldRepoValidationPort
import skillbill.ports.scaffold.source.ScaffoldSourceLoaderPort
import skillbill.ports.scaffold.staging.ScaffoldGeneratedStagingPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.SpecScratchStore
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.telemetry.DefaultTelemetrySettingsProvider
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.GoalObservabilityEventValidator
import skillbill.workflow.GoalProgressEventValidator
import skillbill.workflow.WorkflowSnapshotValidator
import java.nio.file.Path

@Component
@Suppress("TooManyFunctions")
abstract class RuntimeComponent(
  private val inputRuntimeContext: RuntimeContext,
) {
  /*
   * Composition-root exception: RuntimeComponent is the only runtime-core surface allowed to
   * know concrete filesystem, HTTP, and SQLite adapters. Downstream adapters consume the
   * application services and ports exposed below instead of importing these implementations
   * through runtime-core as an umbrella module.
   */
  @Provides
  fun runtimeContext(): RuntimeContext {
    val resolvedContext =
      if (inputRuntimeContext.userHome == RuntimeContext.UnspecifiedUserHome) {
        inputRuntimeContext.copy(userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
      } else {
        inputRuntimeContext
      }
    val environmentContext =
      if (resolvedContext.environment === RuntimeContext.UnspecifiedEnvironment) {
        resolvedContext.copy(environment = System.getenv())
      } else {
        resolvedContext
      }
    return if (environmentContext.requester === UnconfiguredHttpRequester) {
      environmentContext.copy(requester = JdkHttpRequester)
    } else {
      environmentContext
    }
  }

  @Provides
  @JvmSynthetic
  internal fun databaseSessionFactory(factory: SQLiteDatabaseSessionFactory): DatabaseSessionFactory = factory

  @Provides
  @JvmSynthetic
  internal fun telemetryConfigStore(store: FileTelemetryConfigStore): TelemetryConfigStore = store

  @Provides
  @JvmSynthetic
  internal fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider): TelemetrySettingsProvider =
    provider

  @Provides
  @JvmSynthetic
  internal fun telemetryClient(client: HttpTelemetryClient): TelemetryClient = client

  @Provides
  @JvmSynthetic
  internal fun telemetryLevelMutator(service: TelemetryLevelMutationService): TelemetryLevelMutator = service

  @Provides
  @JvmSynthetic
  internal fun installPlanningFactsPort(adapter: FileSystemInstallPlanningFacts): InstallPlanningFactsPort = adapter

  @Provides
  @JvmSynthetic
  internal fun installPlatformSkillMaterializationPort(
    adapter: FileSystemInstallPlatformSkillMaterialization,
  ): InstallPlatformSkillMaterializationPort = adapter

  @Provides
  @JvmSynthetic
  internal fun installStagingIntentPort(adapter: FileSystemInstallStagingIntent): InstallStagingIntentPort = adapter

  @Provides
  @JvmSynthetic
  internal fun installApplyExecutionPort(adapter: FileSystemInstallApplyExecution): InstallApplyExecutionPort = adapter

  @Provides
  @JvmSynthetic
  internal fun installSkillLinkPort(adapter: FileSystemInstallSkillLink): InstallSkillLinkPort = adapter

  @Provides
  @JvmSynthetic
  internal fun installAgentTargetPort(adapter: FileSystemInstallAgentTargets): InstallAgentTargetPort = adapter

  @Provides
  @JvmSynthetic
  internal fun installNativeAgentLinkPort(adapter: FileSystemInstallNativeAgentLinks): InstallNativeAgentLinkPort =
    adapter

  @Provides
  @JvmSynthetic
  internal fun installMcpRegistrationPort(adapter: FileSystemInstallMcpRegistration): InstallMcpRegistrationPort =
    adapter

  @Provides
  @JvmSynthetic
  internal fun agentRunLauncher(context: RuntimeContext, adapter: FileSystemAgentRunLauncher): AgentRunLauncher =
    context.agentRunLauncher ?: adapter

  @Provides
  @JvmSynthetic
  internal fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher): GoalRunnerSubtaskLauncher =
    adapter

  // SKILL-66 Subtask 3: GoalRunner reaches lifecycle-telemetry emission only
  // through the application-owned GoalLifecycleTelemetryEmitter seam (backed by
  // LifecycleTelemetryService) and times every payload off this injected clock.
  @Provides
  @JvmSynthetic
  internal fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService): GoalLifecycleTelemetryEmitter =
    service

  @Provides
  @JvmSynthetic
  internal fun runtimeClock(): java.time.Clock = java.time.Clock.systemUTC()

  @Provides
  @JvmSynthetic
  internal fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore): GoalRunnerManifestStore = adapter

  @Provides
  @JvmSynthetic
  internal fun goalRunnerWorkflowOutcomeStore(
    adapter: WorkflowGoalRunnerOutcomeStore,
  ): GoalRunnerWorkflowOutcomeStore = adapter

  @Provides
  @JvmSynthetic
  internal fun goalPullRequestPort(context: RuntimeContext, adapter: GhGoalPullRequestPort): GoalPullRequestPort =
    context.goalPullRequestPort ?: adapter

  @Provides
  @JvmSynthetic
  internal fun installSelectionPersistencePort(
    adapter: FileSystemInstallSelectionPersistence,
  ): InstallSelectionPersistencePort = adapter

  @Provides
  @JvmSynthetic
  internal fun repoLocalConfigPort(adapter: FileSystemRepoLocalConfig): RepoLocalConfigPort = adapter

  @Provides
  @JvmSynthetic
  internal fun scaffoldGateway(gateway: FileSystemScaffoldGateway): ScaffoldGateway = gateway

  // SKILL-52.1 subtask 2: typed capability ports for the scaffold pipeline. These are wired
  // alongside the legacy `ScaffoldGateway` raw-map adapter so subtask 3 can migrate the
  // application-layer scaffold service over without further DI churn. The legacy
  // `ScaffoldGateway` binding above intentionally stays.
  @Provides
  @JvmSynthetic
  internal fun scaffoldSourceLoaderPort(adapter: FileSystemScaffoldSourceLoader): ScaffoldSourceLoaderPort = adapter

  @Provides
  @JvmSynthetic
  internal fun scaffoldManifestPersistencePort(
    adapter: FileSystemScaffoldManifestPersistence,
  ): ScaffoldManifestPersistencePort = adapter

  @Provides
  @JvmSynthetic
  internal fun scaffoldGeneratedStagingPort(
    adapter: FileSystemScaffoldGeneratedStaging,
  ): ScaffoldGeneratedStagingPort = adapter

  @Provides
  @JvmSynthetic
  internal fun scaffoldInstallLinkPort(adapter: FileSystemScaffoldInstallLink): ScaffoldInstallLinkPort = adapter

  @Provides
  @JvmSynthetic
  internal fun scaffoldRepoValidationPort(adapter: FileSystemScaffoldRepoValidation): ScaffoldRepoValidationPort =
    adapter

  @Provides
  @JvmSynthetic
  internal fun unsupportedScaffoldGateway(gateway: FileSystemUnsupportedScaffoldGateway): UnsupportedScaffoldGateway =
    gateway

  @Provides
  @JvmSynthetic
  internal fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway): ScaffoldCatalogGateway = gateway

  @Provides
  @JvmSynthetic
  internal fun diffResolverPort(adapter: FileSystemDiffResolver): DiffResolverPort = adapter

  @Provides
  @JvmSynthetic
  internal fun reviewRubricPort(adapter: FileSystemReviewRubricAdapter): ReviewRubricPort = adapter

  @Provides
  @JvmSynthetic
  internal fun repoSourceDiscoveryGateway(gateway: FileSystemRepoSourceDiscoveryGateway): RepoSourceDiscoveryGateway =
    gateway

  @Provides
  @JvmSynthetic
  internal fun repoValidationGateway(gateway: FileSystemRepoValidationGateway): RepoValidationGateway = gateway

  @Provides
  @JvmSynthetic
  internal fun reviewInputSource(source: FileSystemReviewInputSource): ReviewInputSource = source

  @Provides
  @JvmSynthetic
  internal fun featureTaskRuntimeRunInvariantsSource(
    adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource,
  ): FeatureTaskRuntimeRunInvariantsSource = adapter

  @Provides
  @JvmSynthetic
  internal fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem): SkillRemoveFileSystem = fileSystem

  @Provides
  @JvmSynthetic
  internal fun workflowGitOperations(context: RuntimeContext, git: GitWorkflowGitOperations): WorkflowGitOperations =
    if (context.workflowGitOperations === NoopWorkflowGitOperations) git else context.workflowGitOperations

  @Provides
  @JvmSynthetic
  internal fun decompositionManifestFileStore(
    store: FileSystemDecompositionManifestFileStore,
  ): DecompositionManifestFileStore = store

  @Provides
  @JvmSynthetic
  internal fun specScratchStore(store: FileSystemSpecScratchStore): SpecScratchStore = store

  // SKILL-52.3 Subtask 1: validator ports now bind to infra-fs adapters
  // (the module that owns the concrete networknt + Jackson schema
  // validators). `runtime-domain` install policy and the application
  // decomposition + workflow seams reach the validators only through
  // these ports, wired exactly like every other infra adapter above.
  @Provides
  @JvmSynthetic
  internal fun installPlanWireValidator(adapter: InstallPlanWireValidatorAdapter): InstallPlanWireValidator = adapter

  @Provides
  @JvmSynthetic
  internal fun decompositionManifestValidator(
    adapter: DecompositionManifestValidatorAdapter,
  ): DecompositionManifestValidator = adapter

  @Provides
  @JvmSynthetic
  internal fun workflowSnapshotValidator(adapter: WorkflowSnapshotValidatorInfraAdapter): WorkflowSnapshotValidator =
    adapter

  @Provides
  @JvmSynthetic
  internal fun featureTaskRuntimePhaseOutputValidator(
    adapter: FeatureTaskRuntimePhaseOutputValidatorAdapter,
  ): FeatureTaskRuntimePhaseOutputValidator = adapter

  @Provides
  @JvmSynthetic
  internal fun goalObservabilityEventValidator(
    adapter: GoalObservabilityEventValidatorAdapter,
  ): GoalObservabilityEventValidator = adapter

  // SKILL-64 Subtask 3: declared goal-progress event schema validator port,
  // bound to the infra-fs adapter that owns the networknt JSON-Schema check.
  // The goal-runner outcome store calls this port at the durable
  // declared-progress write seam, mirroring goalObservabilityEventValidator.
  @Provides
  @JvmSynthetic
  internal fun goalProgressEventValidator(adapter: GoalProgressEventValidatorAdapter): GoalProgressEventValidator =
    adapter

  abstract val parallelCodeReviewRunner: ParallelCodeReviewRunner

  // Exposed as a pre-built object so the CLI consumer need not resolve the infra-fs
  // RepoLocalConfigPort adapter type, which is not on the CLI module's compile classpath.
  abstract val configResolutionService: ConfigResolutionService
  abstract val installService: InstallService
  abstract val agentRunService: AgentRunService
  abstract val featureTaskRuntimePhaseRecorder: FeatureTaskRuntimePhaseRecorder
  abstract val featureTaskRuntimeRunner: FeatureTaskRuntimeRunner
  abstract val featureTaskRuntimeStatusService: FeatureTaskRuntimeStatusService

  // Exposed as a pre-built object so the CLI consumer need not resolve the infra-fs adapter type,
  // which is not on the CLI module's compile classpath.
  abstract val featureTaskRuntimeRunInvariantsSource: FeatureTaskRuntimeRunInvariantsSource
  abstract val goalRunner: GoalRunner
  abstract val goalRunnerStatusService: GoalRunnerStatusService
  abstract val installAgentService: InstallAgentService
  abstract val installSelectionPersistencePort: InstallSelectionPersistencePort
  abstract val learningService: LearningService
  abstract val lifecycleTelemetryService: LifecycleTelemetryService
  abstract val mcpRegistrationService: McpRegistrationService
  abstract val nativeAgentInstallService: NativeAgentInstallService
  abstract val repoValidationService: RepoValidationService
  abstract val repoSourceDiscoveryService: RepoSourceDiscoveryService
  abstract val reviewService: ReviewService
  abstract val scaffoldCatalogService: ScaffoldCatalogService
  abstract val scaffoldService: ScaffoldService
  abstract val skillRemoveService: SkillRemoveService
  abstract val systemService: SystemService
  abstract val telemetryLevelMutator: TelemetryLevelMutator
  abstract val telemetryService: TelemetryService
  abstract val unsupportedScaffoldService: UnsupportedScaffoldService
  abstract val workflowService: WorkflowService
}
