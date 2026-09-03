package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.featuretask.model.DefaultFeatureTaskRuntimePhaseGateBranchPort
import skillbill.application.featuretask.model.DefaultFeatureTaskRuntimePhaseGateValidationPort
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseGateBranchPort
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseGateValidationPort
import skillbill.application.goalrunner.planning.model.DefaultGoalPlanningSweepCheckpointPort
import skillbill.application.goalrunner.planning.model.DefaultGoalPlanningSweepLaunchPort
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepCheckpointPort
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepLaunchPort
import skillbill.application.review.model.DefaultParallelCodeReviewRunnerLaneLaunchPort
import skillbill.application.review.model.DefaultParallelCodeReviewRunnerPlanningPort
import skillbill.application.review.model.ParallelCodeReviewRunnerLaneLaunchPort
import skillbill.application.review.model.ParallelCodeReviewRunnerPlanningPort
import skillbill.infrastructure.fs.JdkBoundedWorkFanOutPort

internal interface RuntimeComponentProvides13 {
  @Provides @JvmSynthetic
  fun parallelCodeReviewRunnerPlanningPort(
    port: DefaultParallelCodeReviewRunnerPlanningPort,
  ): ParallelCodeReviewRunnerPlanningPort = port

  @Provides @JvmSynthetic
  fun parallelCodeReviewRunnerLaneLaunchPort(
    port: DefaultParallelCodeReviewRunnerLaneLaunchPort,
  ): ParallelCodeReviewRunnerLaneLaunchPort = port

  @Provides @JvmSynthetic
  fun featureTaskRuntimePhaseGateBranchPort(
    port: DefaultFeatureTaskRuntimePhaseGateBranchPort,
  ): FeatureTaskRuntimePhaseGateBranchPort = port

  @Provides @JvmSynthetic
  fun featureTaskRuntimePhaseGateValidationPort(
    port: DefaultFeatureTaskRuntimePhaseGateValidationPort,
  ): FeatureTaskRuntimePhaseGateValidationPort = port

  @Provides @JvmSynthetic
  fun goalPlanningSweepCheckpointPort(port: DefaultGoalPlanningSweepCheckpointPort): GoalPlanningSweepCheckpointPort =
    port

  @Provides @JvmSynthetic
  fun goalPlanningSweepLaunchPort(port: DefaultGoalPlanningSweepLaunchPort): GoalPlanningSweepLaunchPort = port

  @Provides @JvmSynthetic
  fun boundedWorkFanOutPort(adapter: JdkBoundedWorkFanOutPort) =
    RuntimeComponentBindingsA7.boundedWorkFanOutPort(adapter)
}
