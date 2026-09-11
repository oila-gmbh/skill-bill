package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.runtime.RuntimeSingleton
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.engine.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.GoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.planning.DurableGoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.DurableGoalPlanningRejectionRecorder
import skillbill.engine.goalrunner.planning.GoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.GoalPlanningRejectionRecorder
import skillbill.engine.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.engine.goalrunner.planning.LaunchAlignedGoalPlanningStatusReasonCoherence
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.infrastructure.fs.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.infrastructure.fs.goalplanning.FileSystemGoalPlanningContextDiscovery
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
