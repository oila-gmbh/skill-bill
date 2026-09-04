package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemDiffResolver
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSharedEvidenceStore
import skillbill.infrastructure.fs.FileSystemRepoValidationGateway
import skillbill.infrastructure.fs.FileSystemReviewInputSource
import skillbill.infrastructure.fs.FileSystemReviewSnapshotGateway
import skillbill.infrastructure.fs.FileSystemScaffoldCatalogGateway
import skillbill.infrastructure.fs.FileSystemUninstallFileSystemGateway
import skillbill.infrastructure.fs.validation.FileSystemValidationGateRunner

internal interface RuntimeScaffoldWorkflowProvides {
  @Provides @JvmSynthetic
  fun scaffoldCatalogGateway(gateway: FileSystemScaffoldCatalogGateway) =
    RuntimeScaffoldPipelineBindings.scaffoldCatalogGateway(gateway)

  @Provides @JvmSynthetic
  fun diffResolverPort(adapter: FileSystemDiffResolver) = RuntimeScaffoldPipelineBindings.diffResolverPort(adapter)

  @Provides @JvmSynthetic
  fun sharedEvidenceResolverPort(adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore) =
    RuntimeWorkflowReviewEvidenceBindings.sharedEvidenceResolverPort(adapter)

  @Provides @JvmSynthetic
  fun sharedEvidenceLocatorReadPort(adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore) =
    RuntimeWorkflowReviewEvidenceBindings.sharedEvidenceLocatorReadPort(adapter)

  @Provides @JvmSynthetic
  fun repoValidationGateway(gateway: FileSystemRepoValidationGateway) =
    RuntimeWorkflowReviewEvidenceBindings.repoValidationGateway(gateway)

  @Provides @JvmSynthetic
  fun validationGateRunner(runner: FileSystemValidationGateRunner) =
    RuntimeWorkflowReviewEvidenceBindings.validationGateRunner(runner)

  @Provides @JvmSynthetic
  fun uninstallPathsPort(gateway: FileSystemUninstallFileSystemGateway) =
    RuntimeWorkflowReviewEvidenceBindings.uninstallPathsPort(gateway)

  @Provides @JvmSynthetic
  fun reviewSnapshotGateway(gateway: FileSystemReviewSnapshotGateway) =
    RuntimeWorkflowReviewEvidenceBindings.reviewSnapshotGateway(gateway)

  @Provides @JvmSynthetic
  fun reviewInputSource(source: FileSystemReviewInputSource) =
    RuntimeWorkflowReviewEvidenceBindings.reviewInputSource(source)
}
