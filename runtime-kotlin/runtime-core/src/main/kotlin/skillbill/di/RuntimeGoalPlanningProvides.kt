package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.GoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.planning.DurableGoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.DurableGoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.GoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.GoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.planning.LaunchAlignedGoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.application.runtime.RuntimeSingleton
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery

internal interface RuntimeGoalPlanningProvides {
  @Provides @JvmSynthetic
  fun goalPlanningStatusReasonCoherence(
    adapter: LaunchAlignedGoalPlanningStatusReasonCoherence,
  ): GoalPlanningStatusReasonCoherence = adapter

  @Provides @JvmSynthetic
  fun goalRunnerExecutionCoordinator(
    coordinator: DefaultGoalRunnerExecutionCoordinator,
  ): GoalRunnerExecutionCoordinator = coordinator

  @Provides @RuntimeSingleton @JvmSynthetic
  fun goalPlanningAttemptRecorder(recorder: DurableGoalPlanningAttemptRecorder): GoalPlanningAttemptRecorder = recorder

  @Provides @JvmSynthetic
  fun goalPlanningRejectionRecorder(recorder: DurableGoalPlanningRejectionRecorder): GoalPlanningRejectionRecorder =
    recorder

  @Provides @JvmSynthetic
  fun goalPlanningContextDiscovery(adapter: FileSystemGoalPlanningContextDiscovery): GoalPlanningContextDiscovery =
    adapter

  @Provides @JvmSynthetic
  fun goalPlanningBoundaryBodyResolver(
    adapter: FileSystemGoalPlanningBoundaryBodyResolver,
  ): GoalPlanningBoundaryBodyResolver {
    GoalPlanningDiscoveryExclusions.excludedRoots
    return adapter
  }

  @Provides @JvmSynthetic
  fun goalPlanningBurstSchedule(): GoalPlanningBurstSchedule = GoalPlanningBurstSchedule(
    planFanOutCap = GoalPlanningBurstSchedule.DEFAULT_PLAN_FAN_OUT_CAP,
    emptyTurnBackoffBase = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_BASE,
    emptyTurnBackoffFactor = GoalPlanningBurstSchedule.DEFAULT_EMPTY_TURN_BACKOFF_FACTOR,
    waitSlice = GoalPlanningBurstSchedule.DEFAULT_WAIT_SLICE,
  )
}
