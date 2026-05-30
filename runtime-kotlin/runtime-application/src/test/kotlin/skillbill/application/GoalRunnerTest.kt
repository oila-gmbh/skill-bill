package skillbill.application

import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

class GoalRunnerTest {
  @Test
  fun `happy path launches each subtask once and opens one final pr`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val pr = RecordingPullRequestPort()
    val runner = GoalRunner(store, launcher, outcomes, pr)

    val report = runner.run(runRequest())

    val completed = assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 2), completed.attemptedSubtasks)
    assertEquals(2, completed.subtasksCompleted)
    assertEquals("https://github.com/canonical/skill-bill/pull/56", completed.pullRequestUrl)
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(1, pr.requests.size)
    assertEquals("feat/SKILL-56-goal", pr.requests.single().headBranch)
    assertEquals("complete", store.manifest.status)
    assertEquals(listOf("sha-1", "sha-2"), store.manifest.subtasks.map { it.commitSha })
  }

  @Test
  fun `forced failure stops on current subtask and does not run later subtasks`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 3))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] =
        if (subtaskId == 2) {
          GoalRunnerStoredOutcome(
            status = GoalRunnerTerminalStatus.FAILED,
            workflowId = "wfl-2",
            blockedReason = "review failed",
            lastResumableStep = "review",
            suppressPr = true,
          )
        } else {
          completeOutcome(subtaskId)
        }
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(listOf(1, 2), stopped.attemptedSubtasks)
    assertEquals(2, stopped.stop.subtaskId)
    assertEquals(GoalRunnerStopReason.FAILED, stopped.stop.reason)
    assertEquals("review failed", stopped.stop.blockedReason)
    assertEquals(CurrentSubtaskIntent(subtaskId = 2, action = "blocked"), store.manifest.currentSubtaskIntent)
    assertEquals("blocked", store.manifest.subtasks.single { it.id == 2 }.status)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 3 }.status)
  }

  @Test
  fun `resume after stop skips completed subtasks and continues from blocked subtask`() {
    val initial = manifest(subtaskCount = 3)
      .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
      .withBlockedSubtask(2, workflowId = "wfl-2", reason = "validation failed")
    val store = InMemoryGoalManifestStore(manifest = initial)
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-2"] = completeOutcome(2)
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      if (subtaskId == 3) {
        store.mutate { current -> current.withWorkflowId(3, "wfl-3") }
        outcomes["wfl-3"] = completeOutcome(3)
      }
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(2, 3), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals("complete", store.manifest.status)
    assertEquals(listOf("sha-1", "sha-2", "sha-3"), store.manifest.subtasks.map { it.commitSha })
  }

  @Test
  fun `missing terminal workflow-store outcome stops on attempted subtask`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launchFacts()
    }
    val outcomes = RecordingOutcomeStore()
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(listOf(1), stopped.attemptedSubtasks)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertEquals(1, stopped.stop.subtaskId)
    assertEquals("wfl-1", stopped.stop.workflowId)
    assertEquals("blocked", store.manifest.subtasks.single { it.id == 1 }.status)
    assertEquals(listOf("wfl-1"), outcomes.blockedWorkflows.map { it.workflowId })
    assertEquals(5.minutes, launcher.requests.single().skillRunRequest.progressIdleTimeout)
    assertContains(requireNotNull(launcher.requests.single().skillRunRequest.progressProbe.progressToken()), "wfl-1")
    outcomes.progresses["wfl-1"] = GoalRunnerWorkflowProgress(
      workflowId = "wfl-1",
      currentStepId = "implement",
      progressToken = "child-progress-token",
    )
    assertContains(
      requireNotNull(launcher.requests.single().skillRunRequest.progressProbe.progressToken()),
      "child-progress-token",
    )
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `timed out child workflow is marked blocked`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launchFacts(timedOut = true)
    }
    val outcomes = RecordingOutcomeStore()
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.TIMEOUT, stopped.stop.reason)
    assertEquals("wfl-1", stopped.stop.workflowId)
    assertEquals(listOf("wfl-1"), outcomes.blockedWorkflows.map { it.workflowId })
    assertEquals("blocked", store.manifest.subtasks.single().status)
    assertEquals("implement", store.manifest.subtasks.single().lastResumableStep)
    assertEquals("implement", stopped.stop.lastResumableStep)
  }

  @Test
  fun `status projection reports counts current step and active agent`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 3)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .withBlockedSubtask(2, workflowId = "wfl-2", reason = "needs review"),
    )
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses["wfl-2"] = GoalRunnerWorkflowProgress(
      workflowId = "wfl-2",
      currentStepId = "implement",
      progressToken = "child-progress-token",
    )
    val service = GoalRunnerStatusService(store, outcomes)

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "claude",
        configuredAgentOverrideId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(1, status.pendingCount)
    assertEquals(1, status.blockedCount)
    assertEquals(2, status.currentSubtaskId)
    assertEquals("implement", status.currentStep)
    assertEquals("codex", status.activeAgent)
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

private class InMemoryGoalManifestStore(
  manifest: DecompositionManifest,
) : GoalRunnerManifestStore {
  var manifest: DecompositionManifest = manifest
    private set

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = dbPathOverride.orEmpty().ifBlank { "/tmp/skillbill-goal-runner/metrics.db" },
      manifest = manifest,
    ).takeIf { manifest.issueKey == issueKey }

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    manifest = state.manifest
    return state.copy(dbPath = dbPathOverride ?: state.dbPath, manifest = manifest)
  }

  fun mutate(block: (DecompositionManifest) -> DecompositionManifest) {
    manifest = block(manifest)
  }
}

private class RecordingOutcomeStore : GoalRunnerWorkflowOutcomeStore {
  private val outcomes: MutableMap<String, GoalRunnerStoredOutcome> = mutableMapOf()
  val progresses: MutableMap<String, GoalRunnerWorkflowProgress> = mutableMapOf()
  val blockedWorkflows: MutableList<BlockedWorkflow> = mutableListOf()

  operator fun set(workflowId: String, outcome: GoalRunnerStoredOutcome) {
    outcomes[workflowId] = outcome
  }

  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = outcomes[workflowId]

  override fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    dbPathOverride: String?,
  ): String {
    blockedWorkflows += BlockedWorkflow(workflowId, blockedReason, lastResumableStep)
    return "implement"
  }

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
    progresses[workflowId]
}

private data class BlockedWorkflow(
  val workflowId: String,
  val blockedReason: String,
  val lastResumableStep: String,
)

private class RecordingSubtaskLauncher(
  private val result: (GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
) : GoalRunnerSubtaskLauncher {
  val requests: MutableList<GoalRunnerSubtaskLaunchRequest> = mutableListOf()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return result(request)
  }
}

private class RecordingPullRequestPort : GoalPullRequestPort {
  val requests: MutableList<GoalPullRequestRequest> = mutableListOf()

  override fun open(request: GoalPullRequestRequest): GoalPullRequestResult {
    requests += request
    return GoalPullRequestResult.Opened("https://github.com/canonical/skill-bill/pull/56")
  }
}

private fun manifest(subtaskCount: Int): DecompositionManifest = DecompositionManifest(
  issueKey = "SKILL-56",
  featureName = "goal",
  parentSpecPath = ".feature-specs/SKILL-56-goal/spec.md",
  baseBranch = "main",
  featureBranch = "feat/SKILL-56-goal",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
  subtasks = (1..subtaskCount).map { id ->
    DecompositionSubtask(
      id = id,
      name = "Subtask $id",
      specPath = ".feature-specs/SKILL-56-goal/spec_subtask_$id.md",
      dependencies = if (id == 1) emptyList() else listOf(DecompositionDependency(id - 1)),
    )
  },
)

private fun completeOutcome(subtaskId: Int): GoalRunnerStoredOutcome = GoalRunnerStoredOutcome(
  status = GoalRunnerTerminalStatus.COMPLETE,
  workflowId = "wfl-$subtaskId",
  commitSha = "sha-$subtaskId",
  lastResumableStep = "commit_push",
  suppressPr = true,
)

private fun launchFacts(timedOut: Boolean = false): AgentRunLaunchFacts = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = if (timedOut) null else 0,
  stdout = "diagnostic only",
  stderr = "",
  timedOut = timedOut,
  spawnFailed = false,
)

private fun DecompositionManifest.withWorkflowId(subtaskId: Int, workflowId: String): DecompositionManifest = copy(
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(workflowId = workflowId)
    } else {
      subtask
    }
  },
)

private fun DecompositionManifest.withCompletedSubtask(
  subtaskId: Int,
  workflowId: String,
  commitSha: String,
): DecompositionManifest = copy(
  status = if (subtasks.all { it.id == subtaskId || it.status == "complete" }) "complete" else "in_progress",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "none"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "complete",
        workflowId = workflowId,
        commitSha = commitSha,
        lastResumableStep = "commit_push",
      )
    } else {
      subtask
    }
  },
)

private fun DecompositionManifest.withBlockedSubtask(
  subtaskId: Int,
  workflowId: String,
  reason: String,
): DecompositionManifest = copy(
  status = "blocked",
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = subtaskId, action = "blocked"),
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(
        status = "blocked",
        workflowId = workflowId,
        blockedReason = reason,
        lastResumableStep = "validate",
      )
    } else {
      subtask
    }
  },
)
