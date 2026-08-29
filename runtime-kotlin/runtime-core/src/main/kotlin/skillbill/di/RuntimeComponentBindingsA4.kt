package skillbill.di

import skillbill.application.agentrun.AgentRunGoalRunnerSubtaskLauncher
import skillbill.application.goalrunner.DefaultGoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.GoalRunnerExecutionCoordinator
import skillbill.application.goalrunner.planning.ChildAwareGoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.planning.DurableGoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.GoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.GoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.application.goalrunner.planning.LaunchAlignedGoalPlanningStatusReasonCoherence
import skillbill.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.launcher.agentrun.PathExecutableLookup
import skillbill.model.OptionalCallbacks
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher

internal object RuntimeComponentBindingsA4 {
  internal fun agentRunLauncher(callbacks: OptionalCallbacks, adapter: FileSystemAgentRunLauncher): AgentRunLauncher =
    callbacks.agentRunLauncher ?: adapter

  fun executableLookup(callbacks: OptionalCallbacks): ExecutableLookup =
    callbacks.executableLookup ?: PathExecutableLookup()

  internal fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher): GoalRunnerSubtaskLauncher =
    adapter

  internal fun goalPlanningSweep(sweep: DefaultGoalPlanningSweep): GoalPlanningSweep = sweep

  internal fun goalPlanningRefreshLiveness(
    adapter: ChildAwareGoalPlanningRefreshLiveness,
  ): GoalPlanningRefreshLiveness = adapter

  internal fun goalPlanningStatusReasonCoherence(
    adapter: LaunchAlignedGoalPlanningStatusReasonCoherence,
  ): GoalPlanningStatusReasonCoherence = adapter

  internal fun goalRunnerExecutionCoordinator(
    coordinator: DefaultGoalRunnerExecutionCoordinator,
  ): GoalRunnerExecutionCoordinator = coordinator

  internal fun goalPlanningAttemptRecorder(recorder: DurableGoalPlanningAttemptRecorder): GoalPlanningAttemptRecorder =
    recorder
}
