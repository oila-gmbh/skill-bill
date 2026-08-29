package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.goalrunner.model.GoalRunnerAcceptRequest
import skillbill.application.goalrunner.model.GoalRunnerAcceptResult
import skillbill.application.goalrunner.model.GoalRunnerPauseResult
import skillbill.application.goalrunner.model.GoalRunnerRepairRequest
import skillbill.application.goalrunner.model.GoalRunnerRepairResult
import skillbill.application.goalrunner.model.GoalRunnerReplanRequest
import skillbill.application.goalrunner.model.GoalRunnerReplanResult
import skillbill.application.goalrunner.model.GoalRunnerResetRequest
import skillbill.application.goalrunner.model.GoalRunnerResetResult
import skillbill.application.goalrunner.model.GoalRunnerResumeResult
import skillbill.application.goalrunner.model.GoalRunnerStatusRequest
import skillbill.application.goalrunner.model.GoalRunnerStatusServiceDeps
import skillbill.application.goalrunner.model.GoalRunnerStopVerbResult
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
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
    ),
  )

  private val controlVerbs = GoalRunnerStatusControlVerbs(
    manifestStore = manifestStore,
    clock = clock,
    workerSupervisor = workerSupervisor,
  )

  private val resetReplanCoordinator = GoalRunnerResetReplanCoordinator(
    manifestStore = manifestStore,
    outcomeStore = outcomeStore,
    gitOperations = gitOperations,
    diagnostics = diagnostics,
    projectionAssembler = projectionAssembler,
  )

  private val repairCoordinator = GoalRunnerRepairCoordinator(
    manifestStore = manifestStore,
    phaseRecorder = phaseRecorder,
    workerSupervisor = workerSupervisor,
    childRepairStore = childRepairStore,
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

  fun pause(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerPauseResult = controlVerbs.pause(issueKey, dbPathOverride, repoRoot)

  fun stop(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerStopVerbResult = controlVerbs.stop(issueKey, dbPathOverride, repoRoot)

  fun resume(
    issueKey: String,
    dbPathOverride: String?,
    repoRoot: Path = Path.of("").toAbsolutePath().normalize(),
  ): GoalRunnerResumeResult = controlVerbs.resume(issueKey, dbPathOverride, repoRoot)

  fun reset(request: GoalRunnerResetRequest): GoalRunnerResetResult? = resetReplanCoordinator.reset(request)

  fun replan(request: GoalRunnerReplanRequest): GoalRunnerReplanResult? = resetReplanCoordinator.replan(request)

  fun hardResetPreflight(issueKey: String, dbPathOverride: String?): List<GoalRunnerAcceptedSubtask> =
    resetReplanCoordinator.hardResetPreflight(issueKey, dbPathOverride)

  fun repair(request: GoalRunnerRepairRequest): GoalRunnerRepairResult = repairCoordinator.repair(request)

  fun accept(request: GoalRunnerAcceptRequest): GoalRunnerAcceptResult = acceptanceCoordinator.accept(request)
}
