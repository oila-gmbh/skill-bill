package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence
import skillbill.infrastructure.fs.FileSystemRepoLocalConfig
import skillbill.infrastructure.fs.FileSystemScaffoldGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGeneratedStaging
import skillbill.infrastructure.fs.FileSystemScaffoldInstallLink
import skillbill.infrastructure.fs.FileSystemScaffoldManifestPersistence
import skillbill.infrastructure.fs.FileSystemUnsupportedScaffoldGateway
import skillbill.infrastructure.fs.GhGoalPullRequestPort
import skillbill.model.OptionalCallbacks
import skillbill.scaffold.adapters.FileSystemScaffoldRepoValidation
import skillbill.scaffold.adapters.FileSystemScaffoldSourceLoader

internal interface RuntimeGoalRunnerScaffoldProvides {
  @Provides @JvmSynthetic
  fun goalPullRequestPort(callbacks: OptionalCallbacks, adapter: GhGoalPullRequestPort) =
    RuntimeGoalRunnerPersistenceReviewBindings.goalPullRequestPort(callbacks, adapter)

  @Provides @JvmSynthetic
  fun installSelectionPersistencePort(adapter: FileSystemInstallSelectionPersistence) =
    RuntimeInstallScaffoldBindings.installSelectionPersistencePort(adapter)

  @Provides @JvmSynthetic
  fun repoLocalConfigPort(adapter: FileSystemRepoLocalConfig) =
    RuntimeInstallScaffoldBindings.repoLocalConfigPort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldGateway(gateway: FileSystemScaffoldGateway) = RuntimeInstallScaffoldBindings.scaffoldGateway(gateway)

  @Provides @JvmSynthetic
  fun scaffoldSourceLoaderPort(adapter: FileSystemScaffoldSourceLoader) =
    RuntimeScaffoldPipelineBindings.scaffoldSourceLoaderPort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldManifestPersistencePort(adapter: FileSystemScaffoldManifestPersistence) =
    RuntimeScaffoldPipelineBindings.scaffoldManifestPersistencePort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldGeneratedStagingPort(adapter: FileSystemScaffoldGeneratedStaging) =
    RuntimeScaffoldPipelineBindings.scaffoldGeneratedStagingPort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldInstallLinkPort(adapter: FileSystemScaffoldInstallLink) =
    RuntimeScaffoldPipelineBindings.scaffoldInstallLinkPort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldRepoValidationPort(adapter: FileSystemScaffoldRepoValidation) =
    RuntimeScaffoldPipelineBindings.scaffoldRepoValidationPort(adapter)

  @Provides @JvmSynthetic
  fun unsupportedScaffoldGateway(gateway: FileSystemUnsupportedScaffoldGateway) =
    RuntimeScaffoldPipelineBindings.unsupportedScaffoldGateway(gateway)
}
