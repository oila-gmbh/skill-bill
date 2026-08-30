package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemDiffResolver
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSharedEvidenceStore
import skillbill.infrastructure.fs.FileSystemRepoSourceDiscoveryGateway
import skillbill.infrastructure.fs.FileSystemRepoValidationGateway
import skillbill.infrastructure.fs.FileSystemReviewInputSource
import skillbill.infrastructure.fs.FileSystemReviewSnapshotGateway
import skillbill.infrastructure.fs.FileSystemScaffoldCatalogGateway
import skillbill.infrastructure.fs.FileSystemUninstallFileSystemGateway
import skillbill.infrastructure.fs.validation.FileSystemValidationGateRunner

internal interface RuntimeComponentProvides6 {
  @Provides @JvmSynthetic
  fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway) =
    RuntimeComponentBindingsB1.scaffoldCatalogGateway(gateway)

  @Provides @JvmSynthetic
  fun diffResolverPort(adapter: FileSystemDiffResolver) = RuntimeComponentBindingsB1.diffResolverPort(adapter)

  @Provides @JvmSynthetic
  fun sharedEvidenceResolverPort(adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore) =
    RuntimeComponentBindingsB2.sharedEvidenceResolverPort(adapter)

  @Provides @JvmSynthetic
  fun sharedEvidenceLocatorReadPort(adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore) =
    RuntimeComponentBindingsB2.sharedEvidenceLocatorReadPort(adapter)

  @Provides @JvmSynthetic
  fun repoSourceDiscoveryGateway(gateway: FileSystemRepoSourceDiscoveryGateway) =
    RuntimeComponentBindingsB2.repoSourceDiscoveryGateway(gateway)

  @Provides @JvmSynthetic
  fun repoValidationGateway(gateway: FileSystemRepoValidationGateway) =
    RuntimeComponentBindingsB2.repoValidationGateway(gateway)

  @Provides @JvmSynthetic
  fun validationGateRunner(runner: FileSystemValidationGateRunner) =
    RuntimeComponentBindingsB2.validationGateRunner(runner)

  @Provides @JvmSynthetic
  fun uninstallFileSystemGateway(gateway: FileSystemUninstallFileSystemGateway) =
    RuntimeComponentBindingsB2.uninstallFileSystemGateway(gateway)

  @Provides @JvmSynthetic
  fun reviewSnapshotGateway(gateway: FileSystemReviewSnapshotGateway) =
    RuntimeComponentBindingsB2.reviewSnapshotGateway(gateway)

  @Provides @JvmSynthetic
  fun reviewInputSource(source: FileSystemReviewInputSource) = RuntimeComponentBindingsB2.reviewInputSource(source)
}
