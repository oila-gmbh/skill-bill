package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.engine.goalrunner.planning.ChildAwareGoalPlanningRefreshLiveness
import skillbill.engine.goalrunner.planning.DefaultGoalPlanningSweep
import skillbill.engine.goalrunner.planning.GoalPlanningRefreshLiveness
import skillbill.engine.goalrunner.planning.GoalPlanningSweep
import skillbill.engine.goalrunner.planning.model.DefaultGoalPlanningSweepCheckpointPort
import skillbill.engine.goalrunner.planning.model.DefaultGoalPlanningSweepLaunchPort
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepCheckpointPort
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepLaunchPort

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
