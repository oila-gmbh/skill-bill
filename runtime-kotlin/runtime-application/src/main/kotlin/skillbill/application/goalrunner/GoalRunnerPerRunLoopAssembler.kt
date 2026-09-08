package skillbill.application.goalrunner
import skillbill.application.goalrunner.model.GoalRunnerDeps

internal class GoalRunnerPerRunLoopAssembler(
  private val deps: GoalRunnerDeps,
) {
  internal fun assemble(pendingState: GoalRunnerIterationPendingState): GoalRunnerGoalLoop {
    val iterationOutcome = GoalRunnerIterationOutcome(
      GoalRunnerIterationOutcomeDeps(
        manifestStore = deps.runBoundaries.manifestStore,
        outcomeStore = deps.runBoundaries.outcomeStore,
        finalization = deps.finalization,
        unaddressedFindingsLedgerService = deps.runBoundaries.unaddressedFindingsLedgerService,
        progressReader = deps.progressReader,
        clock = deps.runBoundaries.clock,
        phaseRecorder = deps.runBoundaries.phaseRecorder,
      ),
      pendingState,
    )
    val selectedSubtaskLoop = GoalRunnerSelectedSubtaskLoop(
      GoalRunnerSelectedSubtaskLoopDeps(
        manifestStore = deps.runBoundaries.manifestStore,
        subtaskLauncher = deps.launchBoundaries.subtaskLauncher,
        reconciler = deps.reconciler,
        workerRequestHandler = deps.workerRequestHandler,
        iterationOutcome = iterationOutcome,
        pauseBoundary = deps.pauseBoundary,
        launchPrepare = deps.launchPrepare,
        clock = deps.runBoundaries.clock,
        pendingState = pendingState,
      ),
    )
    return GoalRunnerGoalLoop(
      deps.runBoundaries.manifestStore,
      deps.runBoundaries.goalPlanningSweep,
      deps.finalization,
      selectedSubtaskLoop,
      deps.pauseBoundary,
      deps.progressReader,
    )
  }
}
