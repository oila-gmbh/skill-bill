package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.application.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.model.ParallelReviewParseResult

internal interface RuntimeComponentProvides11 {
  @Provides @JvmSynthetic
  fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore) =
    RuntimeComponentBindingsA6.goalRunnerManifestStore(adapter)

  @Provides @JvmSynthetic
  fun producerOutputEvidenceValidator() = RuntimeComponentBindingsB5.producerOutputEvidenceValidator()

  @Provides @JvmSynthetic
  fun goalRunnerWorkflowOutcomeStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerWorkflowOutcomeStore(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerAttemptLedgerStore(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeComponentBindingsA6.goalRunnerChildRepairStore(adapter)

  @Provides @JvmSynthetic
  fun parallelReviewParseRegister(): (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse
}
