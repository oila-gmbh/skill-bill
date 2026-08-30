package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.infrastructure.fs.FileSystemCheckedOutBranchSource
import skillbill.model.OptionalCallbacks

internal interface RuntimeComponentProvides10 {
  @Provides @JvmSynthetic
  fun checkedOutBranchSource(source: FileSystemCheckedOutBranchSource) =
    RuntimeComponentBindingsB7.checkedOutBranchSource(source)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeReviewDriver(runner: ParallelCodeReviewRunner) =
    RuntimeComponentBindingsB7.featureTaskRuntimeReviewDriver(runner)

  @Provides @JvmSynthetic
  fun featureTaskPhaseSettlementRepository() = RuntimeComponentBindingsB7.featureTaskPhaseSettlementRepository()

  @Provides @JvmSynthetic
  fun agentAddonSelectionPort() = RuntimeComponentBindingsB3.agentAddonSelectionPort()

  @Provides @JvmSynthetic
  fun rejectedOutputDiagnosticMetadataValidator() =
    RuntimeComponentBindingsB5.rejectedOutputDiagnosticMetadataValidator()

  @Provides @JvmSynthetic
  fun executableLookup(callbacks: OptionalCallbacks) = RuntimeComponentBindingsA4.executableLookup(callbacks)
}
