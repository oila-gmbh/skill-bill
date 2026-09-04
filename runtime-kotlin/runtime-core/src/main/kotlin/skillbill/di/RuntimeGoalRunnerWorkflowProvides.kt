package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.goalrunner.GoalRunnerChildRepairOperations
import skillbill.application.goalrunner.planning.GoalChildPlanningHydratorPortAdapter
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.review.ParallelReviewFindingParser
import skillbill.review.model.ParallelReviewParseResult

internal interface RuntimeGoalRunnerWorkflowProvides {
  @Provides @JvmSynthetic
  fun decompositionManifestProjectionWriter(
    writer: DecompositionManifestWriter,
  ): DecompositionManifestProjectionWriter = writer

  @Provides @JvmSynthetic
  fun goalChildPlanningHydratorPort(adapter: GoalChildPlanningHydratorPortAdapter): GoalChildPlanningHydratorPort =
    adapter

  @Provides @JvmSynthetic
  fun goalRunnerChildRepairExecutorPort(operations: GoalRunnerChildRepairOperations): GoalRunnerChildRepairRunnerPort =
    operations

  @Provides @JvmSynthetic
  fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore) =
    RuntimeGoalRunnerPersistenceReviewBindings.goalRunnerManifestStore(adapter)

  @Provides @JvmSynthetic
  fun producerOutputEvidenceValidator() = RuntimeFeatureTaskRuntimeValidatorBindings.producerOutputEvidenceValidator()

  @Provides @JvmSynthetic
  fun goalRunnerWorkflowOutcomeStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeGoalRunnerPersistenceReviewBindings.goalRunnerWorkflowOutcomeStore(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore) =
    RuntimeGoalRunnerPersistenceReviewBindings.goalRunnerAttemptLedgerStore(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerChildRepairStore =
    RuntimeGoalRunnerPersistenceReviewBindings.goalRunnerChildRepairStore(adapter)

  @Provides @JvmSynthetic
  fun parallelReviewParseRegister(): (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse
}
