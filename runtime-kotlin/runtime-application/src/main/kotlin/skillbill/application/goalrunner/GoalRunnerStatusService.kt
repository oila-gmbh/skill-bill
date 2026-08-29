package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.goalrunner.planning.GoalPlanningStatusReasonCoherence
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
import skillbill.application.goalrunner.model.GoalRunnerStopVerbResult
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerAttemptLedgerStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.NoopGoalRunnerAttemptLedgerStore
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path
import java.time.Clock

@Inject
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  private val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  private val attemptLedgerStore: GoalRunnerAttemptLedgerStore = NoopGoalRunnerAttemptLedgerStore,
  private val clock: Clock = Clock.systemUTC(),
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  private val childRepairStore: GoalRunnerChildRepairStore = NoopGoalRunnerChildRepairStore,
  private val planningStatusReasonCoherence: GoalPlanningStatusReasonCoherence =
    GoalPlanningStatusReasonCoherence.NONE,
  private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  private val runtimeStatusService: FeatureTaskRuntimeStatusService? = null,
) {
  private val projectionAssembler = GoalRunnerStatusProjectionAssembler(
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
