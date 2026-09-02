package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject

@Inject
class GoalRunnerExecutionCollaborators(
  val workerRequestHandler: GoalRunnerWorkerRequestHandler,
  val reconciler: GoalRunnerLaunchReconciler,
  val progressReader: GoalRunnerProgressReader,
)

@Inject
class GoalRunnerLifecycleCollaborators(
  val pauseBoundary: GoalRunnerPauseBoundary,
  val runPreparation: GoalRunnerRunPreparation,
  val launchPrepare: GoalRunnerSubtaskLaunchPrepare,
  val finalization: GoalRunnerFinalization,
)

@Inject
class GoalRunnerLoopCollaborators(
  val execution: GoalRunnerExecutionCollaborators,
  val lifecycle: GoalRunnerLifecycleCollaborators,
)
