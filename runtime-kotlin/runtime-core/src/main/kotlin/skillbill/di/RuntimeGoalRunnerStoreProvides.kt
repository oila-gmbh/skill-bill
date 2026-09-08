package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.goalrunner.GoalRunnerChildRepairOperations
import skillbill.application.goalrunner.planning.GoalChildPlanningHydratorPortAdapter
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerManifestStore
import skillbill.infrastructure.sqlite.goalrunner.WorkflowGoalRunnerOutcomeStore
import skillbill.ports.goalrunner.persistence.GoalChildPlanningHydratorPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairRunnerPort
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore

internal interface RuntimeGoalRunnerStoreProvides {
  @Provides @JvmSynthetic
  fun goalRunnerManifestStore(adapter: WorkflowGoalRunnerManifestStore): GoalRunnerManifestStore = adapter

  @Provides @JvmSynthetic
  fun goalRunnerWorkflowOutcomeStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerWorkflowOutcomeStore = adapter

  @Provides @JvmSynthetic
  fun goalRunnerAttemptLedgerStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerAttemptLedgerStore = adapter

  @Provides @JvmSynthetic
  fun goalRunnerChildRepairStore(adapter: WorkflowGoalRunnerOutcomeStore): GoalRunnerChildRepairStore = adapter

  @Provides @JvmSynthetic
  fun goalRunnerChildRepairExecutorPort(operations: GoalRunnerChildRepairOperations): GoalRunnerChildRepairRunnerPort =
    operations

  @Provides @JvmSynthetic
  fun goalChildPlanningHydratorPort(adapter: GoalChildPlanningHydratorPortAdapter): GoalChildPlanningHydratorPort =
    adapter
}
