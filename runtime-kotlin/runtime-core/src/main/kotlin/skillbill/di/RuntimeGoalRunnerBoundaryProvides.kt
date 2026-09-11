package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.agentrun.AgentRunGoalRunnerSubtaskLauncher
import skillbill.engine.goalrunner.model.DefaultGoalRunnerFinalizationBoundariesPort
import skillbill.engine.goalrunner.model.DefaultGoalRunnerRunBoundariesPort
import skillbill.engine.goalrunner.model.DefaultGoalRunnerSubtaskLaunchBoundariesPort
import skillbill.engine.goalrunner.model.GoalRunnerFinalizationBoundariesPort
import skillbill.engine.goalrunner.model.GoalRunnerRunBoundariesPort
import skillbill.engine.goalrunner.model.GoalRunnerSubtaskLaunchBoundariesPort
import skillbill.infrastructure.fs.GhGoalPullRequestPort
import skillbill.infrastructure.fs.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.infrastructure.fs.launcher.agentrun.PathExecutableLookupimport skillbill.model.OptionalCallbacks
import skillbill.ports.repository.RepositoryEnclosingRootPort

internal interface RuntimeGoalRunnerBoundaryProvides {
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
  fun hostPlatformPort(callbacks: OptionalCallbacks) =
    RuntimeGoalRunnerPersistenceReviewBindings.hostPlatformPort(callbacks)

  @Provides @JvmSynthetic
  fun repositoryEnclosingRootPort(): RepositoryEnclosingRootPort =
    RuntimeBootstrapBindings.repositoryEnclosingRootPort()
}
