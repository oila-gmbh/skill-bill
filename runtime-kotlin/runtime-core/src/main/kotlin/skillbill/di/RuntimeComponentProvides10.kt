package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.SkillBillVersion
import skillbill.application.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.application.review.ParallelCodeReviewRunner
import skillbill.featurespec.FeatureSpecPreparationPolicy
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationIntake
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

  @Provides @JvmSynthetic
  fun skillBillVersion(): String = SkillBillVersion.VALUE

  @Provides @JvmSynthetic
  fun goalPlanningBurstSchedule(): GoalPlanningBurstSchedule = GoalPlanningBurstSchedule(
    planLaunchPace = GoalPlanningBurstSchedule.DEFAULT_PLAN_LAUNCH_PACE,
    emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
    emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
    waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
  )

  @Provides @JvmSynthetic
  fun featureSpecPreparationCore(): (FeatureSpecPreparationIntake) -> FeatureSpecPreparationDecision =
    FeatureSpecPreparationPolicy::prepare
}
