package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.goalrunner.planning.ChildAwareGoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.application.goalrunner.planning.GoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.GoalPlanningSweep
import skillbill.application.goalrunner.planning.model.DefaultGoalPlanningSweepCheckpointPort
import skillbill.application.goalrunner.planning.model.DefaultGoalPlanningSweepLaunchPort
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepCheckpointPort
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepLaunchPort

internal interface RuntimeGoalPlanningSweepProvides {
  @Provides @JvmSynthetic
  fun goalPlanningSweep(sweep: DefaultGoalPlanningSweep): GoalPlanningSweep = sweep

  @Provides @JvmSynthetic
  fun goalPlanningRefreshLiveness(adapter: ChildAwareGoalPlanningRefreshLiveness): GoalPlanningRefreshLiveness = adapter

  @Provides @JvmSynthetic
  fun goalPlanningSweepCheckpointPort(port: DefaultGoalPlanningSweepCheckpointPort): GoalPlanningSweepCheckpointPort =
    port

  @Provides @JvmSynthetic
  fun goalPlanningSweepLaunchPort(port: DefaultGoalPlanningSweepLaunchPort): GoalPlanningSweepLaunchPort = port
}
