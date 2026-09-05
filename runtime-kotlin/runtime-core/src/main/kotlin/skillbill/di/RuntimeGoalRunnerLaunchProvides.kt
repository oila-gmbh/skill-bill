package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.agentrun.AgentRunGoalRunnerSubtaskLauncher
import skillbill.application.goalrunner.model.DefaultGoalRunnerFinalizationBoundariesPort
import skillbill.application.goalrunner.model.DefaultGoalRunnerRunBoundariesPort
import skillbill.application.goalrunner.model.DefaultGoalRunnerSubtaskLaunchBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerFinalizationBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerRunBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerSubtaskLaunchBoundariesPort
import skillbill.infrastructure.fs.GhGoalPullRequestPort
import skillbill.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.launcher.agentrun.PathExecutableLookup
import skillbill.model.OptionalCallbacks
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.ExecutableLookup
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher

internal interface RuntimeGoalRunnerLaunchProvides {
  @Provides @JvmSynthetic
  fun goalRunnerRunBoundariesPort(port: DefaultGoalRunnerRunBoundariesPort): GoalRunnerRunBoundariesPort = port

  @Provides @JvmSynthetic
  fun goalRunnerSubtaskLaunchBoundariesPort(
    port: DefaultGoalRunnerSubtaskLaunchBoundariesPort,
  ): GoalRunnerSubtaskLaunchBoundariesPort = port

  @Provides @JvmSynthetic
  fun goalRunnerFinalizationBoundariesPort(
    port: DefaultGoalRunnerFinalizationBoundariesPort,
  ): GoalRunnerFinalizationBoundariesPort = port

  @Provides @JvmSynthetic
  fun goalPullRequestPort(callbacks: OptionalCallbacks, adapter: GhGoalPullRequestPort): GoalPullRequestPort =
    callbacks.goalPullRequestPort ?: adapter

  @Provides @JvmSynthetic
  fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher): GoalRunnerSubtaskLauncher = adapter

  @Provides @JvmSynthetic
  fun agentRunLauncher(callbacks: OptionalCallbacks, adapter: FileSystemAgentRunLauncher): AgentRunLauncher =
    callbacks.agentRunLauncher ?: adapter

  @Provides @JvmSynthetic
  fun executableLookup(callbacks: OptionalCallbacks): ExecutableLookup =
    callbacks.executableLookup ?: PathExecutableLookup()
}
