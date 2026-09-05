package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.application.goalrunner.model.GoalRunnerAcceptResult
import skillbill.application.goalrunner.model.GoalRunnerPauseResult
import skillbill.application.goalrunner.model.GoalRunnerRepairCoordinatorDeps
import skillbill.application.goalrunner.model.GoalRunnerRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairResult
import skillbill.application.goalrunner.model.GoalRunnerReplanRequest
import skillbill.application.goalrunner.model.GoalRunnerReplanResult
import skillbill.application.goalrunner.model.GoalRunnerResetReplanCoordinatorDeps
import skillbill.application.goalrunner.model.GoalRunnerResetRequest
import skillbill.application.goalrunner.model.GoalRunnerResetResult
import skillbill.application.goalrunner.model.GoalRunnerResumeResult
import skillbill.application.goalrunner.model.GoalRunnerStatusProjectionAssemblerDeps
import skillbill.application.goalrunner.model.GoalRunnerStatusRequest
import skillbill.application.goalrunner.model.GoalRunnerStatusServiceDeps
import skillbill.application.goalrunner.model.GoalRunnerStopVerbResult
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
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
  private val portableReviewBaselinePersistence = deps.portableReviewBaselinePersistence
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
    GoalRunnerRepairCoordinatorDeps(
      manifestStore = manifestStore,
      phaseRecorder = phaseRecorder,
      workerSupervisor = workerSupervisor,
      childRepairStore = childRepairStore,
      gitOperations = gitOperations,
      portableReviewBaselinePersistence = portableReviewBaselinePersistence,
      repositoryRoot = repositoryRoot,
      repositoryEnclosingRootPort = repositoryEnclosingRootPort,
    ),
  )

  private val acceptanceCoordinator = GoalRunnerAcceptanceCoordinator(
    manifestStore = manifestStore,
    outcomeStore = outcomeStore,
    gitOperations = gitOperations,
  )

  fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? {
    return manifestStore.readByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?.let { loadedState -> projectionAssembler.project(loadedState, request) }
  }

  fun statusRefresh(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection? = status(request)

  fun pause(issueKey: String, dbPathOverride: String?, repoRoot: Path? = null): GoalRunnerPauseResult =
    controlVerbs.pause(issueKey, dbPathOverride, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun stop(issueKey: String, dbPathOverride: String?, repoRoot: Path? = null): GoalRunnerStopVerbResult =
    controlVerbs.stop(issueKey, dbPathOverride, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun resume(issueKey: String, dbPathOverride: String?, repoRoot: Path? = null): GoalRunnerResumeResult =
    controlVerbs.resume(issueKey, dbPathOverride, effectiveGoalRepoRoot(repoRoot, repositoryRoot))

  fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? = resetReplanCoordinator.reset(request)

  fun replan(request: GoalRunnerReplanRequest): GoalRunnerReplanResult? = resetReplanCoordinator.replan(request)

  fun hardResetPreflight(issueKey: String, dbPathOverride: String?): List<GoalRunnerAcceptedSubtask> =
    resetReplanCoordinator.hardResetPreflight(issueKey, dbPathOverride)

  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult = repairCoordinator.repair(request)

  fun accept(request: GoalRunnerAcceptRequest): GoalRunnerAcceptResult = acceptanceCoordinator.accept(request)
}

fun effectiveGoalRepoRoot(repoRoot: Path?, repositoryRoot: RepositoryRoot): Path = repoRoot ?: repositoryRoot.path
