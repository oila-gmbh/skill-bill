package skillbill.di

import skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.infrastructure.fs.FileSystemCheckedOutBranchSource
import skillbill.infrastructure.fs.IdeStatusValidatorAdapter
import skillbill.infrastructure.sqlite.SqliteFeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.workflow.idestatus.IdeStatusValidator

internal object RuntimeComponentBindingsB7 {
  internal fun ideStatusValidator(adapter: IdeStatusValidatorAdapter): IdeStatusValidator = adapter

  internal fun checkedOutBranchSource(source: FileSystemCheckedOutBranchSource): CheckedOutBranchSource = source

  internal fun featureTaskRuntimeReviewDriver(runner: ParallelCodeReviewRunner): FeatureTaskRuntimeReviewDriver =
    FeatureTaskRuntimeReviewDriver(runner::run)

  fun featureTaskPhaseSettlementRepository(): FeatureTaskPhaseSettlementRepository =
    SqliteFeatureTaskPhaseSettlementRepository()
}
