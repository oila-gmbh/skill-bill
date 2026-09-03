package skillbill.di

import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSharedEvidenceStore
import skillbill.infrastructure.fs.FileSystemRepoValidationGateway
import skillbill.infrastructure.fs.FileSystemReviewInputSource
import skillbill.infrastructure.fs.FileSystemReviewSnapshotGateway
import skillbill.infrastructure.fs.FileSystemUninstallFileSystemGateway
import skillbill.infrastructure.fs.validation.FileSystemValidationGateRunner
import skillbill.ports.review.ReviewInputSource
import skillbill.ports.review.ReviewSnapshotGateway
import skillbill.ports.system.UninstallPathsPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.ValidationGateRunner

internal object RuntimeComponentBindingsB2 {
  internal fun sharedEvidenceResolverPort(
    adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore,
  ): FeatureTaskRuntimeSharedEvidenceResolverPort = adapter

  internal fun sharedEvidenceLocatorReadPort(
    adapter: FileSystemFeatureTaskRuntimeSharedEvidenceStore,
  ): FeatureTaskRuntimeSharedEvidenceLocatorReadPort = adapter

  internal fun repoValidationGateway(gateway: FileSystemRepoValidationGateway): RepoValidationGateway = gateway

  internal fun validationGateRunner(runner: FileSystemValidationGateRunner): ValidationGateRunner = runner

  internal fun uninstallPathsPort(gateway: FileSystemUninstallFileSystemGateway): UninstallPathsPort = gateway

  internal fun reviewSnapshotGateway(gateway: FileSystemReviewSnapshotGateway): ReviewSnapshotGateway = gateway

  internal fun reviewInputSource(source: FileSystemReviewInputSource): ReviewInputSource = source
}
