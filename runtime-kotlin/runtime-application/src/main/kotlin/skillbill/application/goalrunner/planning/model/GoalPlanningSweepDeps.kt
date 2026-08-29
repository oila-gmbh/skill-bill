package skillbill.application.goalrunner.planning.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.planning.GoalPlanningAttemptRecorder
import skillbill.application.goalrunner.planning.GoalPlanningRefreshLiveness
import skillbill.application.goalrunner.planning.GoalPlanningRejectionRecorder
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.workflow.decomposition.DecompositionManifestFileStore
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator

@Inject
data class GoalPlanningSweepDeps(
  val checkpoint: GoalPlanningPreparationCheckpoint,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val subtaskLauncher: GoalRunnerSubtaskLauncher,
  val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  val manifestFileStore: DecompositionManifestFileStore,
  val contextDiscovery: GoalPlanningContextDiscovery,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val planningAttemptRecorder: GoalPlanningAttemptRecorder,
  val manifestStore: GoalRunnerManifestStore,
  val planningRejectionRecorder: GoalPlanningRejectionRecorder,
  val timingPort: RuntimeTimingPort,
  val burstSchedule: GoalPlanningBurstSchedule,
  val refreshLiveness: GoalPlanningRefreshLiveness,
)
