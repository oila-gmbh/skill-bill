package skillbill.application

import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
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
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
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
    assertEquals(0, completed.subtasksPending)
    assertEquals(0, completed.subtasksBlocked)
    assertEquals("opened", completed.pullRequestStatus)
    assertEquals("https://github.com/canonical/skill-bill/pull/56", completed.pullRequestUrl)
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(1, pr.requests.size)
    assertEquals("feat/SKILL-56-goal", pr.requests.single().headBranch)
    assertEquals("complete", store.manifest.status)
    assertEquals(listOf("sha-1", "sha-2"), store.manifest.subtasks.map { it.commitSha })
  }

  @Test
  fun `done path saves final manifest projection before opening pr`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val outcomes = RecordingOutcomeStore()
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
    )

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, store.saveCount)
    assertEquals("complete", store.manifest.status)
    assertEquals("SKILL-56", outcomes.lastReconcileRequest?.issueKey)
    assertEquals(emptySet(), outcomes.lastReconcileRequest?.activeWorkflowIds)
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
    assertContains(stopped.stop.blockedReason, "review failed")
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
    assertEquals(2, launcher.requests.size)
    assertEquals(null, launcher.requests.first().skillRunRequest.timeout)
    assertEquals(30.minutes, launcher.requests.first().skillRunRequest.progressIdleTimeout)
    assertContains(requireNotNull(launcher.requests.first().skillRunRequest.progressProbe.progressToken()), "wfl-1")
    outcomes.progresses["wfl-1"] = GoalRunnerWorkflowProgress(
      workflowId = "wfl-1",
      workflowStatus = "running",
      currentStepId = "implement",
      progressToken = "child-progress-token",
    )
    assertContains(
      requireNotNull(launcher.requests.first().skillRunRequest.progressProbe.progressToken()),
      "child-progress-token",
    )
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `missing terminal outcome retries once and can recover`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    var launches = 0
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launches += 1
      if (launches == 2) {
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      }
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val completed = assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1), completed.attemptedSubtasks)
    assertEquals(2, launcher.requests.size)
    assertEquals("complete", store.manifest.status)
    assertEquals("sha-1", store.manifest.subtasks.single().commitSha)
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
  fun `same-branch run blocks before launch when feature branch resolves to protected main`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1).copy(featureBranch = "main"),
    )
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val outcomes = RecordingOutcomeStore()
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
      gitOperations = FixedBranchGitOperations("main"),
    )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.POLICY_BLOCKED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "protected branch 'main'")
    assertEquals(emptyList(), launcher.requests)
    assertEquals("blocked", store.manifest.status)
    assertEquals("blocked", store.manifest.currentSubtaskIntent.action)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    assertContains(store.manifest.subtasks.single().blockedReason.orEmpty(), "protected branch")
  }

  @Test
  fun `same-branch goal checks out feature branch from protected current branch before launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val git = RecordingGitOperations(currentBranch = "main")
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
      gitOperations = git,
    )

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf("feat/SKILL-56-goal@main"), git.checkouts)
    assertEquals(listOf(1), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals("complete", store.manifest.status)
  }

  @Test
  fun `same-branch goal blocks at create branch when feature branch checkout fails`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = RecordingOutcomeStore(),
      pullRequestPort = RecordingPullRequestPort(),
      gitOperations = RecordingGitOperations(currentBranch = "main", checkoutError = "cannot create feature branch"),
    )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "cannot create feature branch")
    assertEquals("create_branch", stopped.stop.lastResumableStep)
    assertEquals(emptyList(), launcher.requests)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    assertEquals("create_branch", store.manifest.subtasks.single().lastResumableStep)
  }

  @Test
  fun `same-branch policy guard does not demote already completed goals`() {
    val completeManifest = manifest(subtaskCount = 1)
      .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
      .copy(status = "complete", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "none"))
    val store = InMemoryGoalManifestStore(manifest = completeManifest.copy(featureBranch = "main"))
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = RecordingOutcomeStore(),
      pullRequestPort = RecordingPullRequestPort(),
      gitOperations = FixedBranchGitOperations("main"),
    )

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(emptyList(), launcher.requests)
    assertEquals("complete", store.manifest.status)
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
      workflowStatus = "running",
      currentStepId = "implement",
      progressToken = "child-progress-token",
      latestLivenessSignal = "durable_progress step=implement attempt=1",
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
    assertEquals("durable_progress step=implement attempt=1", status.latestLivenessSignal)
  }

  @Test
  fun `status projection reflects terminal child outcome before parent projection catches up`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
        .withWorkflowId(1, "wfl-1"),
    )
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] = completeOutcome(1)
    val service = GoalRunnerStatusService(store, outcomes)

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(0, status.blockedCount)
    assertEquals(null, status.currentSubtaskId)
    assertEquals(null, status.currentStep)
    assertEquals("complete", store.manifest.status)
    assertEquals("none", store.manifest.currentSubtaskIntent.action)
    assertEquals("complete", store.manifest.subtasks.single().status)
    assertEquals(ReconcileRequest("SKILL-56", setOf("wfl-1"), false, null), outcomes.lastReconcileRequest)
  }

  @Test
  fun `status reconciliation saves from refreshed manifest snapshot to avoid clobbering concurrent updates`() {
    val stale = manifest(subtaskCount = 2)
      .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
      .withWorkflowId(1, "wfl-1")
    val concurrentlyUpdated = stale.copy(
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "resume"),
      subtasks = stale.subtasks.map { subtask ->
        if (subtask.id == 2) {
          subtask.copy(status = "in_progress", workflowId = "wfl-2", branch = "feat/SKILL-56-goal")
        } else {
          subtask
        }
      },
    )
    val store = SequencedLoadGoalManifestStore(listOf(stale, concurrentlyUpdated))
    val outcomes = RecordingOutcomeStore().apply {
      authoritativeOutcomesBySubtask[1] = completeOutcome(1).copy(workflowId = "wfl-1")
    }
    val service = GoalRunnerStatusService(store, outcomes)

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    val subtask1 = store.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", subtask1.status)
    assertEquals("wfl-1", subtask1.workflowId)
    val subtask2 = store.manifest.subtasks.single { it.id == 2 }
    assertEquals("in_progress", subtask2.status)
    assertEquals("wfl-2", subtask2.workflowId)
    assertEquals("feat/SKILL-56-goal", subtask2.branch)
    assertEquals(CurrentSubtaskIntent(subtaskId = 2, action = "resume"), store.manifest.currentSubtaskIntent)
  }

  @Test
  fun `status projection prefers authoritative complete child outcome over stale blocked projection`() {
    val staleManifest = manifest(subtaskCount = 1)
      .copy(status = "blocked", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"))
      .withBlockedSubtask(1, workflowId = "wfl-stale", reason = "stale blocked projection")
    val store = InMemoryGoalManifestStore(manifest = staleManifest)
    val outcomes = RecordingOutcomeStore().apply {
      authoritativeOutcomesBySubtask[1] = completeOutcome(1).copy(workflowId = "wfl-authoritative")
    }
    val service = GoalRunnerStatusService(store, outcomes)

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(0, status.blockedCount)
    assertEquals(null, status.currentSubtaskId)
    assertEquals("complete", store.manifest.status)
    assertEquals("none", store.manifest.currentSubtaskIntent.action)
    val subtask = store.manifest.subtasks.single()
    assertEquals("complete", subtask.status)
    assertEquals("wfl-authoritative", subtask.workflowId)
  }

  @Test
  fun `status reconciliation preserves active retry when sibling blocked outcome exists`() {
    val activeManifest = manifest(subtaskCount = 1)
      .copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
        subtasks = listOf(
          DecompositionSubtask(
            id = 1,
            name = "Subtask 1",
            specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
            status = "in_progress",
            workflowId = "wfl-active",
          ),
        ),
      )
    val store = InMemoryGoalManifestStore(manifest = activeManifest)
    val outcomes = RecordingOutcomeStore().apply {
      authoritativeOutcomesBySubtask[1] = GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.BLOCKED,
        workflowId = "wfl-blocked",
        blockedReason = "old sibling blocked",
        lastResumableStep = "review",
        suppressPr = true,
      )
      progresses["wfl-active"] = GoalRunnerWorkflowProgress(
        workflowId = "wfl-active",
        workflowStatus = "running",
        currentStepId = "implement",
        progressToken = "tok",
        latestDurableProgressEvent = null,
        latestLivenessSignal = "running",
        lastSnapshotUpdatedAt = "2026-05-30 00:00:00",
      )
    }
    val service = GoalRunnerStatusService(store, outcomes)

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(0, status.completeCount)
    assertEquals(0, status.blockedCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("implement", status.currentStep)
    val subtask = store.manifest.subtasks.single()
    assertEquals("in_progress", subtask.status)
    assertEquals("wfl-active", subtask.workflowId)
  }

  @Test
  fun `status reconciliation persists blocked terminal outcome to manifest state`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
        .withWorkflowId(1, "wfl-1"),
    )
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] = GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.FAILED,
      workflowId = "wfl-1",
      blockedReason = "review failed",
      lastResumableStep = "review",
      suppressPr = true,
    )
    val service = GoalRunnerStatusService(store, outcomes)

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(0, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(1, status.blockedCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("review", status.currentStep)
    assertEquals("blocked", store.manifest.status)
    assertEquals("blocked", store.manifest.currentSubtaskIntent.action)
    val subtask = store.manifest.subtasks.single()
    assertEquals("blocked", subtask.status)
    assertEquals("review failed", subtask.blockedReason)
    assertEquals("review", subtask.lastResumableStep)
  }

  @Test
  fun `status projection marks blocked terminal child outcome from workflow store when parent is stale`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
        .withWorkflowId(1, "wfl-1"),
    )
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] = GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.BLOCKED,
      workflowId = "wfl-1",
      blockedReason = "preplan blocked",
      lastResumableStep = "preplan",
      suppressPr = true,
    )
    val service = GoalRunnerStatusService(store, outcomes)

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(0, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(1, status.blockedCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("preplan", status.currentStep)
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
  var saveCount: Int = 0
    private set

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = dbPathOverride.orEmpty().ifBlank { "/tmp/skillbill-goal-runner/metrics.db" },
      manifest = manifest,
    ).takeIf { manifest.issueKey == issueKey }

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    saveCount += 1
    manifest = state.manifest
    return state.copy(dbPath = dbPathOverride ?: state.dbPath, manifest = manifest)
  }

  fun mutate(block: (DecompositionManifest) -> DecompositionManifest) {
    manifest = block(manifest)
  }
}

private class SequencedLoadGoalManifestStore(
  manifestsByLoadOrder: List<DecompositionManifest>,
) : GoalRunnerManifestStore {
  private val snapshots: List<DecompositionManifest> = manifestsByLoadOrder
  private var loadIndex: Int = 0
  var manifest: DecompositionManifest = manifestsByLoadOrder.last()
    private set

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? {
    val selected = snapshots.getOrNull(loadIndex).also { loadIndex += 1 } ?: manifest
    if (selected.issueKey != issueKey) {
      return null
    }
    manifest = selected
    return GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = dbPathOverride.orEmpty().ifBlank { "/tmp/skillbill-goal-runner/metrics.db" },
      manifest = selected,
    )
  }

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    manifest = state.manifest
    return state.copy(dbPath = dbPathOverride ?: state.dbPath, manifest = manifest)
  }
}

private class RecordingOutcomeStore : GoalRunnerWorkflowOutcomeStore {
  private val outcomes: MutableMap<String, GoalRunnerStoredOutcome> = mutableMapOf()
  val progresses: MutableMap<String, GoalRunnerWorkflowProgress> = mutableMapOf()
  val blockedWorkflows: MutableList<BlockedWorkflow> = mutableListOf()
  val authoritativeOutcomesBySubtask: MutableMap<Int, GoalRunnerStoredOutcome> = mutableMapOf()
  var lastReconcileRequest: ReconcileRequest? = null

  operator fun set(workflowId: String, outcome: GoalRunnerStoredOutcome) {
    outcomes[workflowId] = outcome
  }

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    allowInactiveReconciliation: Boolean,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    lastReconcileRequest = ReconcileRequest(issueKey, activeWorkflowIds, allowInactiveReconciliation, dbPathOverride)
    return authoritativeOutcomesBySubtask.toMap()
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
    supervisionEvent: GoalRunnerSupervisionEvent?,
    dbPathOverride: String?,
  ): String {
    blockedWorkflows += BlockedWorkflow(workflowId, blockedReason, lastResumableStep, supervisionEvent)
    return "implement"
  }

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? =
    progresses[workflowId]
}

private data class BlockedWorkflow(
  val workflowId: String,
  val blockedReason: String,
  val lastResumableStep: String,
  val supervisionEvent: GoalRunnerSupervisionEvent?,
)

private data class ReconcileRequest(
  val issueKey: String,
  val activeWorkflowIds: Set<String>,
  val allowInactiveReconciliation: Boolean,
  val dbPathOverride: String?,
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

private class FixedBranchGitOperations(
  private val branch: String,
) : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-test")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = "ok")
}

private class RecordingGitOperations(
  private val currentBranch: String = "",
  private val checkoutError: String? = null,
  private val validationError: String? = null,
) : WorkflowGitOperations {
  val checkouts: MutableList<String> = mutableListOf()
  val validations: MutableList<String> = mutableListOf()

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    checkouts += "$branch@${baseBranch.orEmpty()}"
    return checkoutError?.let { WorkflowGitOperationResult(status = "error", error = it) }
      ?: WorkflowGitOperationResult(status = "ok", value = branch)
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = currentBranch)

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-test")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult {
    validations += "$branch@$expectedBaseBranch"
    return validationError?.let { WorkflowGitOperationResult(status = "error", error = it) }
      ?: WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)
  }

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = "ok")
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
