package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.planning.ChildAwareGoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.planning.DurableGoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.DurableGoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.LaunchAlignedGoalPlanningStatusReasonCoherence
import skillbill.application.runtime.RuntimeSingleton
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery

internal interface RuntimeGoalRunnerPlanningProvides {
  @Provides @JvmSynthetic
  fun goalPlanningSweep(sweep: DefaultGoalPlanningSweep) = RuntimeLauncherGoalRunnerBindings.goalPlanningSweep(sweep)

  @Provides @JvmSynthetic
  fun goalPlanningRefreshLiveness(adapter: ChildAwareGoalPlanningRefreshLiveness) =
    RuntimeLauncherGoalRunnerBindings.goalPlanningRefreshLiveness(adapter)

  @Provides @JvmSynthetic
  fun goalPlanningStatusReasonCoherence(adapter: LaunchAlignedGoalPlanningStatusReasonCoherence) =
    RuntimeLauncherGoalRunnerBindings.goalPlanningStatusReasonCoherence(adapter)

  @Provides @JvmSynthetic
  fun goalRunnerExecutionCoordinator(coordinator: DefaultGoalRunnerExecutionCoordinator) =
    RuntimeLauncherGoalRunnerBindings.goalRunnerExecutionCoordinator(coordinator)

  @Provides @RuntimeSingleton @JvmSynthetic
  fun goalPlanningAttemptRecorder(recorder: DurableGoalPlanningAttemptRecorder) =
    RuntimeLauncherGoalRunnerBindings.goalPlanningAttemptRecorder(recorder)

  @Provides @JvmSynthetic
  fun goalPlanningRejectionRecorder(recorder: DurableGoalPlanningRejectionRecorder) =
    RuntimeGoalRunnerDiagnosticsBindings.goalPlanningRejectionRecorder(recorder)

  @Provides @JvmSynthetic
  fun goalPlanningContextDiscovery(adapter: FileSystemGoalPlanningContextDiscovery) =
    RuntimeGoalRunnerDiagnosticsBindings.goalPlanningContextDiscovery(adapter)

  @Provides @JvmSynthetic
  fun goalPlanningBoundaryBodyResolver(adapter: FileSystemGoalPlanningBoundaryBodyResolver) =
    RuntimeGoalRunnerDiagnosticsBindings.goalPlanningBoundaryBodyResolver(adapter)

  @Provides @JvmSynthetic
  fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService) =
    RuntimeGoalRunnerDiagnosticsBindings.goalLifecycleTelemetryEmitter(service)

  @Provides @JvmSynthetic
  fun runtimeClock() = RuntimeGoalRunnerPersistenceReviewBindings.runtimeClock()
}
