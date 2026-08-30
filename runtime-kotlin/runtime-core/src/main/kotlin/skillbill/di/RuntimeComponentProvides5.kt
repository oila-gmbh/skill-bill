package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence
import skillbill.infrastructure.fs.FileSystemRepoLocalConfig
import skillbill.infrastructure.fs.FileSystemScaffoldGateway
import skillbill.infrastructure.fs.FileSystemScaffoldGeneratedStaging
import skillbill.infrastructure.fs.FileSystemScaffoldInstallLink
import skillbill.infrastructure.fs.FileSystemScaffoldManifestPersistence
import skillbill.infrastructure.fs.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.fs.FileSystemScaffoldSourceLoader
import skillbill.infrastructure.fs.FileSystemUnsupportedScaffoldGateway
import skillbill.infrastructure.fs.GhGoalPullRequestPort
import skillbill.model.OptionalCallbacks

internal interface RuntimeComponentProvides5 {
  @Provides @JvmSynthetic
  fun goalPullRequestPort(callbacks: OptionalCallbacks, adapter: GhGoalPullRequestPort) =
    RuntimeComponentBindingsA6.goalPullRequestPort(callbacks, adapter)

  @Provides @JvmSynthetic
  fun installSelectionPersistencePort(adapter: FileSystemInstallSelectionPersistence) =
    RuntimeComponentBindingsA7.installSelectionPersistencePort(adapter)

  @Provides @JvmSynthetic
  fun repoLocalConfigPort(adapter: FileSystemRepoLocalConfig) = RuntimeComponentBindingsA7.repoLocalConfigPort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldGateway(gateway: FileSystemScaffoldGateway) = RuntimeComponentBindingsA7.scaffoldGateway(gateway)

  @Provides @JvmSynthetic
  fun scaffoldSourceLoaderPort(adapter: FileSystemScaffoldSourceLoader) =
    RuntimeComponentBindingsB1.scaffoldSourceLoaderPort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldManifestPersistencePort(adapter: FileSystemScaffoldManifestPersistence) =
    RuntimeComponentBindingsB1.scaffoldManifestPersistencePort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldGeneratedStagingPort(adapter: FileSystemScaffoldGeneratedStaging) =
    RuntimeComponentBindingsB1.scaffoldGeneratedStagingPort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldInstallLinkPort(adapter: FileSystemScaffoldInstallLink) =
    RuntimeComponentBindingsB1.scaffoldInstallLinkPort(adapter)

  @Provides @JvmSynthetic
  fun scaffoldRepoValidationPort(adapter: FileSystemScaffoldRepoValidation) =
    RuntimeComponentBindingsB1.scaffoldRepoValidationPort(adapter)

  @Provides @JvmSynthetic
  fun unsupportedScaffoldGateway(gateway: FileSystemUnsupportedScaffoldGateway) =
    RuntimeComponentBindingsB1.unsupportedScaffoldGateway(gateway)
}
