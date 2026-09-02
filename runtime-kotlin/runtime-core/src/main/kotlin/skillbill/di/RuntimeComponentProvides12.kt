package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.goalrunner.model.DefaultGoalRunnerFinalizationBoundariesPort
import skillbill.application.goalrunner.model.DefaultGoalRunnerRunBoundariesPort
import skillbill.application.goalrunner.model.DefaultGoalRunnerSubtaskLaunchBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerFinalizationBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerRunBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerSubtaskLaunchBoundariesPort

internal interface RuntimeComponentProvides12 {
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
}
