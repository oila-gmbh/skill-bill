package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
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
import skillbill.model.RepositoryRoot
import skillbill.ports.goalrunner.persistence.GoalRunnerChildRepairStore
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path
import java.time.Clock

@Inject
class GoalRunnerStatusService(
  private val manifestStore: GoalRunnerManifestStore,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  phaseRecorder: FeatureTaskRuntimePhaseRecorder,
  gitOperations: WorkflowGitOperations,
  clock: Clock,
  workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  childRepairStore: GoalRunnerChildRepairStore,
  private val repositoryRoot: RepositoryRoot,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val projectionAssembler: GoalRunnerStatusProjectionAssembler,
  private val resetReplanCoordinator: GoalRunnerResetReplanCoordinator,
) {
  private val controlVerbs = GoalRunnerStatusControlVerbs(
    manifestStore = manifestStore,
    clock = clock,
    workerSupervisor = workerSupervisor,
    repositoryEnclosingRootPort = repositoryEnclosingRootPort,
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
