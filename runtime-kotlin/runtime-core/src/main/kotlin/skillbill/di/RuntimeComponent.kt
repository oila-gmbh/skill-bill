package skillbill.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.application.InstallAgentService
import skillbill.application.InstallService
import skillbill.application.LearningService
import skillbill.application.LifecycleTelemetryService
import skillbill.application.McpRegistrationService
import skillbill.application.NativeAgentInstallService
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
import skillbill.application.WorkflowService
import skillbill.domain.skillremove.SkillRemoveFileSystem
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.FileSystemInstallAgentGateway
import skillbill.infrastructure.fs.FileSystemInstallGateway
import skillbill.infrastructure.fs.FileSystemMcpRegistrationGateway
import skillbill.infrastructure.fs.FileSystemNativeAgentInstallGateway
import skillbill.infrastructure.fs.FileSystemRepoSourceDiscoveryGateway
import skillbill.infrastructure.fs.FileSystemRepoValidationGateway
import skillbill.infrastructure.fs.FileSystemReviewInputSource
import skillbill.infrastructure.fs.FileSystemScaffoldCatalogGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGateway
import skillbill.infrastructure.fs.FileSystemSkillRemoveFileSystem
import skillbill.infrastructure.fs.FileSystemUnsupportedScaffoldGateway
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.model.RuntimeContext
import skillbill.ports.install.InstallAgentGateway
import skillbill.ports.install.InstallPlanGateway
import skillbill.ports.install.McpRegistrationGateway
import skillbill.ports.install.NativeAgentInstallGateway
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.scaffold.RepoSourceDiscoveryGateway
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.telemetry.UnconfiguredHttpRequester
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.telemetry.DefaultTelemetrySettingsProvider

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
  fun runtimeContext(): RuntimeContext = if (inputRuntimeContext.requester === UnconfiguredHttpRequester) {
    inputRuntimeContext.copy(requester = JdkHttpRequester)
  } else {
    inputRuntimeContext
  }

  @Provides
  internal fun databaseSessionFactory(factory: SQLiteDatabaseSessionFactory): DatabaseSessionFactory = factory

  @Provides
  internal fun telemetryConfigStore(store: FileTelemetryConfigStore): TelemetryConfigStore = store

  @Provides
  internal fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider): TelemetrySettingsProvider =
    provider

  @Provides
  internal fun telemetryClient(client: HttpTelemetryClient): TelemetryClient = client

  @Provides
  internal fun telemetryLevelMutator(service: TelemetryLevelMutationService): TelemetryLevelMutator = service

  @Provides
  internal fun installPlanGateway(gateway: FileSystemInstallGateway): InstallPlanGateway = gateway

  @Provides
  internal fun installAgentGateway(gateway: FileSystemInstallAgentGateway): InstallAgentGateway = gateway

  @Provides
  internal fun nativeAgentInstallGateway(gateway: FileSystemNativeAgentInstallGateway): NativeAgentInstallGateway =
    gateway

  @Provides
  internal fun mcpRegistrationGateway(gateway: FileSystemMcpRegistrationGateway): McpRegistrationGateway = gateway

  @Provides
  internal fun scaffoldGateway(gateway: FileSystemScaffoldGateway): ScaffoldGateway = gateway

  @Provides
  internal fun unsupportedScaffoldGateway(gateway: FileSystemUnsupportedScaffoldGateway): UnsupportedScaffoldGateway =
    gateway

  @Provides
  internal fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway): ScaffoldCatalogGateway = gateway

  @Provides
  internal fun repoSourceDiscoveryGateway(gateway: FileSystemRepoSourceDiscoveryGateway): RepoSourceDiscoveryGateway =
    gateway

  @Provides
  internal fun repoValidationGateway(gateway: FileSystemRepoValidationGateway): RepoValidationGateway = gateway

  @Provides
  internal fun reviewInputSource(source: FileSystemReviewInputSource): ReviewInputSource = source

  @Provides
  internal fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem): SkillRemoveFileSystem = fileSystem

  @Provides
  internal fun workflowGitOperations(context: RuntimeContext, git: GitWorkflowGitOperations): WorkflowGitOperations =
    if (context.workflowGitOperations === NoopWorkflowGitOperations) git else context.workflowGitOperations

  @Provides
  internal fun decompositionManifestFileStore(
    store: FileSystemDecompositionManifestFileStore,
  ): DecompositionManifestFileStore = store

  abstract val installService: InstallService
  abstract val installAgentService: InstallAgentService
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
