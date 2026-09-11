package skillbill.engine.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.engine.goalrunner.model.GoalRunnerAcceptResult
import skillbill.engine.goalrunner.model.GoalRunnerPauseResult
import skillbill.engine.goalrunner.model.GoalRunnerRepairRequest
import skillbill.engine.goalrunner.model.GoalRunnerRepairResult
import skillbill.engine.goalrunner.model.GoalRunnerReplanRequest
import skillbill.engine.goalrunner.model.GoalRunnerReplanResult
import skillbill.engine.goalrunner.model.GoalRunnerResetRequest
import skillbill.engine.goalrunner.model.GoalRunnerResetResult
import skillbill.engine.goalrunner.model.GoalRunnerResumeResult
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.model.GoalRunnerStopVerbResultimport skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.model.RepositoryRoot
import java.nio.file.Path

@Inject
class GoalRunnerStatusService(deps: GoalRunnerStatusServiceDeps) {
  private val manifestStore = deps.manifestStore
  private val outcomeStore = deps.outcomeStore
  private val phaseRecorder = deps.phaseRecorder
  private val gitOperations = deps.gitOperations
  private val attemptLedgerStore = deps.attemptLedgerStore
  private val clock = deps.clock
  private val workerSupervisor = deps.workerSupervisor
  private val childRepairStore = deps.childRepairStore
  private val planningStatusReasonCoherence = deps.planningStatusReasonCoherence
  private val diagnostics = deps.diagnostics
  private val runtimeStatusService = deps.runtimeStatusService
  private val repositoryRoot = deps.repositoryRoot
  private val repositoryEnclosingRootPort = deps.repositoryEnclosingRootPort
  private val projectionAssembler = GoalRunnerStatusProjectionAssembler(
    GoalRunnerStatusProjectionAssemblerDeps(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      phaseRecorder = phaseRecorder,
      gitOperations = gitOperations,
      attemptLedgerStore = attemptLedgerStore,
      clock = clock,
      workerSupervisor = workerSupervisor,
      planningStatusReasonCoherence = planningStatusReasonCoherence,
      diagnostics = diagnostics,
      runtimeStatusService = runtimeStatusService,
      repositoryRoot = repositoryRoot,
    ),
  )

  private val controlVerbs = GoalRunnerStatusControlVerbs(
    manifestStore = manifestStore,
    clock = clock,
    workerSupervisor = workerSupervisor,
    repositoryEnclosingRootPort = repositoryEnclosingRootPort,
  )

  private val resetReplanCoordinator = GoalRunnerResetReplanCoordinator(
    GoalRunnerResetReplanCoordinatorDeps(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      gitOperations = gitOperations,
      diagnostics = diagnostics,
      projectionAssembler = projectionAssembler,
      repositoryRoot = repositoryRoot,
      repositoryEnclosingRootPort = repositoryEnclosingRootPort,
    ),
  )

  private val repairCoordinator = GoalRunnerRepairCoordinator(
    manifestStore = manifestStore,
    phaseRecorder = phaseRecorder,
    workerSupervisor = workerSupervisor,
    childRepairStore = childRepairStore,
    repositoryRoot = repositoryRoot,
    repositoryEnclosingRootPort = repositoryEnclosingRootPort,
  )

  private val acceptanceCoordinator = GoalRunnerAcceptanceCoordinator(
    manifestStore = manifestStore,
    outcomeStore = outcomeStore,
    gitOperations = gitOperations,
  )

  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    return manifestStore.readByIssueKey(request.issueKey, request.repoRoot)
      ?.let { loadedState -> projectionAssembler.project(loadedState, request) }
  }

  fun statusRefresh(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? = status(request)

  fun pause(issueKey: String, repoRoot: Path? = null): GoalRunnerPauseResult =
    controlVerbs.pause(issueKey, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun stop(issueKey: String, repoRoot: Path? = null): GoalRunnerStopVerbResult =
    controlVerbs.stop(issueKey, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun resume(issueKey: String, repoRoot: Path? = null): GoalRunnerResumeResult =
    controlVerbs.resume(issueKey, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? = resetReplanCoordinator.reset(request)

  fun replan(request: GoalRunnerReplanRequest): GoalRunnerReplanResult? = resetReplanCoordinator.replan(request)

  fun hardResetPreflight(issueKey: String): List<GoalRunnerAcceptedSubtask> =
    resetReplanCoordinator.hardResetPreflight(issueKey)

  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult = repairCoordinator.repair(request)

  fun accept(request: GoalRunnerAcceptRequest): GoalRunnerAcceptResult = acceptanceCoordinator.accept(request)
}

fun effectiveGoalRepoRoot(repoRoot: Path?, repositoryRoot: RepositoryRoot): Path = repoRoot ?: repositoryRoot.path
