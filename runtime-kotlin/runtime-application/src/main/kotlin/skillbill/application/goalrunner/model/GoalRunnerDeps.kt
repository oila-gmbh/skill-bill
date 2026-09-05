package skillbill.application.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.GoalRunnerFinalization
import skillbill.application.goalrunner.GoalRunnerLaunchReconciler
import skillbill.application.goalrunner.GoalRunnerPauseBoundary
import skillbill.application.goalrunner.GoalRunnerProgressReader
import skillbill.application.goalrunner.GoalRunnerRunPreparation
import skillbill.application.goalrunner.GoalRunnerSubtaskLaunchPrepare
import skillbill.application.goalrunner.GoalRunnerWorkerRequestHandler

@Inject
data class GoalRunnerDeps(
  val runBoundaries: GoalRunnerRunBoundariesPort,
  val launchBoundaries: GoalRunnerSubtaskLaunchBoundariesPort,
  val workerRequestHandler: GoalRunnerWorkerRequestHandler,
  val reconciler: GoalRunnerLaunchReconciler,
  val progressReader: GoalRunnerProgressReader,
  val pauseBoundary: GoalRunnerPauseBoundary,
  val runPreparation: GoalRunnerRunPreparation,
  val launchPrepare: GoalRunnerSubtaskLaunchPrepare,
  val finalization: GoalRunnerFinalization,
)
