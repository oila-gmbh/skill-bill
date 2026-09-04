package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.SkillBillVersion
import skillbill.application.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.featurespec.FeatureSpecPreparationPolicy
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.infrastructure.fs.FileSystemCheckedOutBranchSource
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.model.OptionalCallbacks

internal interface RuntimeCompositionMiscProvides {
  @Provides @JvmSynthetic
  fun checkedOutBranchSource(source: FileSystemCheckedOutBranchSource) =
    RuntimeFeatureTaskReviewIntegrationBindings.checkedOutBranchSource(source)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeReviewDriver(runner: ParallelCodeReviewRunner) =
    RuntimeFeatureTaskReviewIntegrationBindings.featureTaskRuntimeReviewDriver(runner)

  @Provides @JvmSynthetic
  fun featureTaskPhaseSettlementRepository() =
    RuntimeFeatureTaskReviewIntegrationBindings.featureTaskPhaseSettlementRepository()

  @Provides @JvmSynthetic
  fun agentAddonSelectionPort() = RuntimeReviewFeatureTaskAgentAddonBindings.agentAddonSelectionPort()

  @Provides @JvmSynthetic
  fun rejectedOutputDiagnosticMetadataValidator() =
    RuntimeFeatureTaskRuntimeValidatorBindings.rejectedOutputDiagnosticMetadataValidator()

  @Provides @JvmSynthetic
  fun executableLookup(callbacks: OptionalCallbacks) = RuntimeLauncherGoalRunnerBindings.executableLookup(callbacks)

  @Provides @JvmSynthetic
  fun skillBillVersion(): String = SkillBillVersion.VALUE

  @Provides @JvmSynthetic
  fun goalPlanningBurstSchedule(): GoalPlanningBurstSchedule = GoalPlanningBurstSchedule(
    planFanOutCap = GoalPlanningBurstSchedule.DEFAULT_PLAN_FAN_OUT_CAP,
    emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
    emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
    waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
  )

  @Provides @JvmSynthetic
  fun featureSpecPreparationCore(): (FeatureSpecPreparationIntake) -> FeatureSpecPreparationDecision =
    FeatureSpecPreparationPolicy::prepare

  @Provides @JvmSynthetic
  fun gitWorkflowGitOperations(): GitWorkflowGitOperations = GitWorkflowGitOperations()
}
