package skillbill.application.goalrunner.planning.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.application.goalrunner.planning.GoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.GoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.GoalPlanningRejectionRecorder
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator

@Inject
data class DefaultGoalPlanningSweepCheckpointPort(
  override val checkpoint: GoalPlanningPreparationCheckpoint,
  override val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  override val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  override val manifestFileStore: DecompositionManifestFileStore,
  override val contextDiscovery: GoalPlanningContextDiscovery,
  override val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
) : GoalPlanningSweepCheckpointPort

@Inject
data class DefaultGoalPlanningSweepLaunchPort(
  override val subtaskLauncher: GoalRunnerSubtaskLauncher,
  override val manifestStore: GoalRunnerManifestStore,
  override val planningAttemptRecorder: GoalPlanningAttemptRecorder,
  override val planningRejectionRecorder: GoalPlanningRejectionRecorder,
  override val timingPort: RuntimeTimingPort,
  override val fanOutPort: BoundedWorkFanOutPort,
  override val burstSchedule: GoalPlanningBurstSchedule,
  override val refreshLiveness: GoalPlanningRefreshLiveness,
) : GoalPlanningSweepLaunchPort
