package skillbill.engine.goalrunner.model

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.GoalRunnerFinalization
import skillbill.engine.goalrunner.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.GoalRunnerPauseBoundary
import skillbill.engine.goalrunner.GoalRunnerProgressReader
import skillbill.engine.goalrunner.GoalRunnerRunPreparation
import skillbill.engine.goalrunner.GoalRunnerSubtaskLaunchPrepare
import skillbill.engine.goalrunner.GoalRunnerWorkerRequestHandler

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
