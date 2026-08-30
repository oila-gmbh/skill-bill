package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.planning.ChildAwareGoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.planning.DurableGoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.DurableGoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.LaunchAlignedGoalPlanningStatusReasonCoherence
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery

internal interface RuntimeComponentProvides3 {
  @Provides @JvmSynthetic
  fun goalPlanningSweep(sweep: DefaultGoalPlanningSweep) = RuntimeComponentBindingsA4.goalPlanningSweep(sweep)

  @Provides @JvmSynthetic
  fun goalPlanningRefreshLiveness(adapter: ChildAwareGoalPlanningRefreshLiveness) =
    RuntimeComponentBindingsA4.goalPlanningRefreshLiveness(adapter)

  @Provides @JvmSynthetic
  fun goalPlanningStatusReasonCoherence(adapter: LaunchAlignedGoalPlanningStatusReasonCoherence) =
    RuntimeComponentBindingsA4.goalPlanningStatusReasonCoherence(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerExecutionCoordinator(coordinator: DefaultGoalRunnerExecutionCoordinator) =
    RuntimeComponentBindingsA4.goalRunnerExecutionCoordinator(coordinator)

  @Provides @JvmSynthetic
  fun goalPlanningAttemptRecorder(recorder: DurableGoalPlanningAttemptRecorder) =
    RuntimeComponentBindingsA4.goalPlanningAttemptRecorder(recorder)

  @Provides @JvmSynthetic
  fun goalPlanningRejectionRecorder(recorder: DurableGoalPlanningRejectionRecorder) =
    RuntimeComponentBindingsA5.goalPlanningRejectionRecorder(recorder)

  @Provides @JvmSynthetic
  fun goalPlanningContextDiscovery(adapter: FileSystemGoalPlanningContextDiscovery) =
    RuntimeComponentBindingsA5.goalPlanningContextDiscovery(adapter)

  @Provides @JvmSynthetic
  fun goalPlanningBoundaryBodyResolver(adapter: FileSystemGoalPlanningBoundaryBodyResolver) =
    RuntimeComponentBindingsA5.goalPlanningBoundaryBodyResolver(adapter)

  @Provides @JvmSynthetic
  fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService) =
    RuntimeComponentBindingsA5.goalLifecycleTelemetryEmitter(service)

  @Provides @JvmSynthetic
  fun runtimeClock() = RuntimeComponentBindingsA5.runtimeClock()
}
