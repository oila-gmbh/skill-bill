package skillbill.application

import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.withBlockedSubtask
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.goalrunner.GoalRunnerLaunchReconciler
import skillbill.application.goalrunner.GoalRunnerLedgerContext
import skillbill.application.goalrunner.GoalRunnerLedgerRecorder
import skillbill.application.goalrunner.GoalRunnerProgressEventEmitter
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerRunEvent
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.workflow.repoRoot
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import skillbill.workflow.model.SpecSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

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
  fun `validation quality gate block resumes child once instead of stopping goal`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    var launches = 0
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      launches += 1
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = if (launches == 1) {
        GoalRunnerStoredOutcome(
          status = GoalRunnerTerminalStatus.BLOCKED,
          workflowId = "wfl-$subtaskId",
          blockedReason = "./gradlew check failed during :web:detekt, so the quality gate is not green.",
          lastResumableStep = "validate",
          suppressPr = true,
        )
      } else {
        completeOutcome(subtaskId)
      }
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 1), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals("validate", launcher.requests.last().skillRunRequest.goalContinuation?.lastResumableStep)
    assertEquals("complete", store.manifest.status)
    assertEquals("complete", store.manifest.subtasks.single().status)
    assertEquals("sha-1", store.manifest.subtasks.single().commitSha)
  }

  @Test
  fun `validation findings keep repairing instead of blocking goal`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    var launches = 0
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      launches += 1
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = if (launches < 3) {
        GoalRunnerStoredOutcome(
          status = if (launches == 1) GoalRunnerTerminalStatus.FAILED else GoalRunnerTerminalStatus.BLOCKED,
          workflowId = "wfl-$subtaskId",
          blockedReason = "Validation findings remain unresolved.",
          lastResumableStep = "validate",
          suppressPr = true,
        )
      } else {
        completeOutcome(subtaskId)
      }
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())
    val events = mutableListOf<GoalRunnerRunEvent>()

    val report = runner.run(runRequest().copy(eventSink = events::add))

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 1, 1), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(
      listOf(null, "validate", "validate"),
      launcher.requests.map { it.skillRunRequest.goalContinuation?.lastResumableStep },
    )
    assertTrue(events.none { event -> event is GoalRunnerRunEvent.SubtaskStopped && event.subtaskId == 1 })
    assertEquals("complete", store.manifest.status)
    assertEquals("complete", store.manifest.subtasks.single().status)
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
    assertContains(stopped.stop.blockedReason, "without a terminal workflow-store outcome")
    // Clean exit (status 0) is diagnosed specifically, not with a cause-agnostic guess.
    assertContains(stopped.stop.blockedReason, "exited cleanly (status 0)")
    assertContains(stopped.stop.blockedReason, "last_resumable_step")
    assertEquals("blocked", store.manifest.subtasks.single { it.id == 1 }.status)
    assertEquals(listOf("wfl-1"), outcomes.blockedWorkflows.map { it.workflowId })
    assertEquals(2, launcher.requests.size)
    assertEquals(null, launcher.requests.first().skillRunRequest.timeout)
    assertEquals(null, launcher.requests.first().skillRunRequest.progressIdleTimeout)
    // SKILL-64 Subtask 3 (F-PF01): the legacy progress probe and the declared
    // probe now share one per-tick read. A fresh launch request (= a fresh
    // per-tick reader) resolves the current store state in a single read, so set
    // the child progress before reading and assert the token folds both the
    // manifest subtask token and the child progress token together.
    outcomes.progresses["wfl-1"] = GoalRunnerWorkflowProgress(
      workflowId = "wfl-1",
      workflowStatus = "running",
      currentStepId = "implement",
      progressToken = "child-progress-token",
    )
    val freshProbeToken = requireNotNull(launcher.requests.last().skillRunRequest.progressProbe.progressToken())
    assertContains(freshProbeToken, "wfl-1")
    assertContains(freshProbeToken, "child-progress-token")
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
  fun `late terminal outcome recovery uses synthetic timing without retry launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val timing = RecordingTimingPort {
      outcomes["wfl-1"] = completeOutcome(1)
      RuntimeWaitResult.COMPLETED
    }
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launchFacts()
    }
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
      timing = timing,
    )

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, launcher.requests.size)
    assertEquals(listOf(200L), timing.delays.map { it.inWholeMilliseconds })
  }

  @Test
  fun `interrupted late terminal wait stops synthetic wait attempts`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val timing = RecordingTimingPort { RuntimeWaitResult.INTERRUPTED }
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launchFacts()
    }
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
      timing = timing,
    )

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(listOf(200L), timing.delays.map { it.inWholeMilliseconds })
    assertEquals(2, launcher.requests.size)
  }

  @Test
  fun `missing terminal retry uses retry launch output for worker requests`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    var launches = 0
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      if (subtaskId == 1) {
        launches += 1
        if (launches == 2) {
          outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        }
        launchFacts(
          stdout = workerSubtaskRequestJson(
            name = if (launches == 1) "Stale first follow up" else "Retry follow up",
            specPath = if (launches == 1) {
              ".feature-specs/SKILL-56-goal/spec_subtask_2_stale_first.md"
            } else {
              ".feature-specs/SKILL-56-goal/spec_subtask_2_retry_follow_up.md"
            },
          ),
        )
      } else {
        outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
        launchFacts()
      }
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val completed = assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 2), completed.attemptedSubtasks)
    assertEquals("Retry follow up", store.manifest.subtasks.single { it.id == 2 }.name)
    assertTrue(store.manifest.subtasks.none { it.name == "Stale first follow up" })
    val accepted = outcomes.workerSubtaskRequestOutcomes.first().outcomes.single()
    assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Accepted>(accepted)
    assertEquals("Retry follow up", accepted.request.name)
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
  fun `decomposed linear run deletes each subtask spec after its commit and the dir after the final pr`() {
    val repoRoot = Files.createTempDirectory("goal-linear-cleanup")
    val specDir = repoRoot.resolve(".feature-specs/SKILL-56-goal")
    Files.createDirectories(specDir)
    Files.writeString(specDir.resolve("spec.md"), "# Parent\n")
    val sub1 = specDir.resolve("spec_subtask_1.md").also { Files.writeString(it, "# 1\n") }
    val sub2 = specDir.resolve("spec_subtask_2.md").also { Files.writeString(it, "# 2\n") }
    Files.writeString(specDir.resolve("decomposition-manifest.yaml"), "x")
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2).copy(specSource = SpecSource.LINEAR))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val scratch = RecordingSpecScratchStore()
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort(), specScratchStore = scratch)

    val report = runner.run(linearRunRequest(repoRoot))

    assertIs<GoalRunnerRunReport.Completed>(report)
    // Each subtask spec is deleted after its own commit; the parent + manifest only via the final
    // directory deletion — and every subtask spec deletion precedes that directory deletion.
    assertEquals(listOf(sub1, sub2), scratch.deletedFiles)
    assertEquals(listOf(specDir), scratch.deletedDirectories)
    assertEquals(listOf(sub1, sub2, specDir), scratch.deletions)
    assertFalse(Files.exists(specDir), "linear goal scratch dir must be gone on success")
  }

  @Test
  fun `decomposed linear run that stops mid-goal leaves remaining scratch and manifest intact`() {
    val repoRoot = Files.createTempDirectory("goal-linear-abort")
    val specDir = repoRoot.resolve(".feature-specs/SKILL-56-goal")
    Files.createDirectories(specDir)
    val parentSpec = specDir.resolve("spec.md").also { Files.writeString(it, "# Parent\n") }
    val sub1 = specDir.resolve("spec_subtask_1.md").also { Files.writeString(it, "# 1\n") }
    val sub2 = specDir.resolve("spec_subtask_2.md").also { Files.writeString(it, "# 2\n") }
    val manifestFile = specDir.resolve("decomposition-manifest.yaml").also { Files.writeString(it, "x") }
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2).copy(specSource = SpecSource.LINEAR))
    val outcomes = RecordingOutcomeStore()
    // Subtask 1 records a terminal outcome; subtask 2 launches but never produces one, so the goal
    // stops before finalize.
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      if (subtaskId == 1) {
        outcomes["wfl-1"] = completeOutcome(1)
      }
      launchFacts()
    }
    val scratch = RecordingSpecScratchStore()
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort(), specScratchStore = scratch)

    assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))

    // Only the completed subtask's spec is deleted; nothing else and no directory deletion.
    assertEquals(listOf(sub1), scratch.deletedFiles)
    assertTrue(scratch.deletedDirectories.isEmpty(), "a stopped goal must not delete the scratch dir")
    assertTrue(Files.exists(sub2), "the incomplete subtask spec must survive")
    assertTrue(Files.exists(parentSpec), "the parent spec must survive a stopped goal")
    assertTrue(Files.exists(manifestFile), "the manifest must survive a stopped goal")
  }

  @Test
  fun `local decomposed run deletes nothing`() {
    val repoRoot = Files.createTempDirectory("goal-local-no-delete")
    val specDir = repoRoot.resolve(".feature-specs/SKILL-56-goal")
    Files.createDirectories(specDir)
    Files.writeString(specDir.resolve("spec.md"), "# Parent\n")
    Files.writeString(specDir.resolve("spec_subtask_1.md"), "# 1\n")
    Files.writeString(specDir.resolve("spec_subtask_2.md"), "# 2\n")
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val scratch = RecordingSpecScratchStore()
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort(), specScratchStore = scratch)

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))

    assertTrue(scratch.deletions.isEmpty(), "local mode must not delete any spec scratch")
    assertTrue(Files.exists(specDir.resolve("spec.md")), "local mode keeps the committed parent spec")
  }

  @Test
  fun `linear finalize does not flag the untracked manifest as a blocking projection delta`() {
    val repoRoot = Files.createTempDirectory("goal-linear-finalize")
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .copy(specSource = SpecSource.LINEAR),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      RecordingPullRequestPort(),
      specScratchStore = RecordingSpecScratchStore(),
      gitOperations = DirtyManifestGitOperations(".feature-specs/SKILL-56-goal/decomposition-manifest.yaml"),
    )

    // The manifest is reported dirty, but in linear mode it is never staged, so finalize must not
    // block on it — the goal completes and opens its PR.
    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
  }

  @Test
  fun `local finalize still blocks on an uncommitted manifest projection delta`() {
    val repoRoot = Files.createTempDirectory("goal-local-finalize")
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      RecordingPullRequestPort(),
      specScratchStore = RecordingSpecScratchStore(),
      gitOperations = DirtyManifestGitOperations(".feature-specs/SKILL-56-goal/decomposition-manifest.yaml"),
    )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "uncommitted decomposition projection delta")
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )

  private fun linearRunRequest(repoRoot: Path): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = repoRoot,
    invokedAgentId = "claude",
    dbPathOverride = null,
  )
}

// Focused coverage for the no-terminal-outcome diagnosis (kept out of GoalRunnerTest to avoid
// growing that already-large class). Asserts the reason string reflects the child exit status and
// carries the captured stderr tail instead of a cause-agnostic guess.
class GoalRunnerNoTerminalOutcomeDiagnosisTest {
  @Test
  fun `non-zero child exit reports exit status and stderr tail without retry`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      AgentRunLaunchFacts(
        agent = InstallAgent.CLAUDE,
        exitStatus = 1,
        stdout = "diagnostic only",
        stderr = "Error: usage limit reached before persisting terminal outcome",
        timedOut = false,
        interrupted = false,
        spawnFailed = false,
      )
    }
    val outcomes = RecordingOutcomeStore()
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    // A non-zero exit is diagnosed as the child erroring out, with the captured stderr tail.
    assertContains(stopped.stop.blockedReason, "exited with status 1")
    assertContains(stopped.stop.blockedReason, "Child stderr tail:")
    assertContains(stopped.stop.blockedReason, "usage limit reached before persisting terminal outcome")
    // A non-zero exit is not eligible for the no-terminal-outcome retry: exactly one launch.
    assertEquals(1, launcher.requests.size)
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

// Reports the decomposition manifest as a dirty worktree path so finalizationError's manifest-delta
// guard can be exercised; everything else returns ok.
private class DirtyManifestGitOperations(
  private val dirtyManifestPath: String,
) : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "feat/SKILL-56-goal")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-test")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-test")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = " M $dirtyManifestPath")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = "ok")

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = "ok")
}

class GoalRunnerStatusProjectionTest {
  @Test
  fun `status projection includes latest observability and requested diff stat when present`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
        .withWorkflowId(1, "wfl-1"),
    )
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses["wfl-1"] = GoalRunnerWorkflowProgress(
      workflowId = "wfl-1",
      workflowStatus = "running",
      currentStepId = "implement",
      progressToken = "child-progress-token",
      latestGoalObservabilityEvent = GoalObservabilityProgressEvent(
        issueKey = "SKILL-56",
        subtaskId = 1,
        workflowPhase = "implement",
        workerRole = "phase_subagent",
        livenessClass = "durable_progress",
        activitySummary = "editing runtime files",
        sequenceNumber = 42,
        timestamp = "2026-06-01T00:00:00Z",
      ),
    )
    val service = GoalRunnerStatusService(
      manifestStore = store,
      outcomeStore = outcomes,
      gitOperations = StatusDiffGitOperations,
    )

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
        repoRoot = Path.of("."),
        includeDiffStat = true,
      ),
    )

    requireNotNull(status)
    assertEquals("implement", status.latestObservabilityEvent?.get("workflow_phase"))
    assertEquals(42, status.latestObservabilityEvent?.get("sequence_number"))
    assertEquals(2, status.requestedDiffStat?.filesChanged)
    assertEquals(5, status.requestedDiffStat?.insertions)
    assertEquals(1, status.requestedDiffStat?.deletions)
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
    assertEquals(ReconcileRequest("SKILL-56", setOf("wfl-1"), false, null, null), outcomes.lastReconcileRequest)
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
  fun `status reconciliation preserves completed manifest subtask when child workflow has stale blocked outcome`() {
    val completedManifest = manifest(subtaskCount = 1)
      .copy(
        status = "complete",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "none"),
        subtasks = listOf(
          DecompositionSubtask(
            id = 1,
            name = "Subtask 1",
            specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
            status = "complete",
            workflowId = "wfl-1",
            commitSha = "sha-1",
            lastResumableStep = "commit_push",
          ),
        ),
      )
    val store = InMemoryGoalManifestStore(manifest = completedManifest)
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] = GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
      workflowId = "wfl-1",
      blockedReason = "stale no-terminal outcome",
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
    assertEquals(1, status.completeCount)
    assertEquals(0, status.pendingCount)
    assertEquals(0, status.blockedCount)
    assertEquals(null, status.currentSubtaskId)
    assertEquals(null, status.currentStep)
    assertEquals("complete", store.manifest.status)
    assertEquals("none", store.manifest.currentSubtaskIntent.action)
    val subtask = store.manifest.subtasks.single()
    assertEquals("complete", subtask.status)
    assertEquals(null, subtask.blockedReason)
    assertEquals("sha-1", subtask.commitSha)
    assertEquals("commit_push", subtask.lastResumableStep)
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
}

class GoalRunnerObservabilityTest {
  @Test
  fun `runner records lifecycle observability from runtime supervision`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts(stdout = "worker summary")
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    val classes = outcomes.observabilityRecords.map { it.livenessClass }
    assertContains(classes, "subtask_start")
    assertContains(classes, "worker_output_summary")
    assertContains(classes, "completion")
    assertEquals(setOf("wfl-1"), outcomes.observabilityRecords.map { it.workflowId }.toSet())
  }

  @Test
  fun `observability store false does not block terminal completion`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.observabilityRecordResult = false
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts(stdout = "worker summary")
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, launcher.requests.size)
    assertEquals("complete", store.manifest.status)
  }

  @Test
  fun `observability store exception does not block terminal completion`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.throwOnObservabilityRecord = true
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts(stdout = "worker summary")
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, launcher.requests.size)
    assertEquals("complete", store.manifest.status)
  }

  @Test
  fun `observability progress exception does not block terminal completion`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.throwOnProgress = true
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts(stdout = "worker summary")
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(1, launcher.requests.size)
    assertEquals("complete", store.manifest.status)
  }

  @Test
  fun `accepted worker subtask request becomes visible sibling work`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      val stdout = if (subtaskId == 1) {
        workerSubtaskRequestJson(
          name = "Worker follow up",
          specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2_worker_follow_up.md",
        )
      } else {
        ""
      }
      launchFacts(stdout = stdout)
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val completed = assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 2), completed.attemptedSubtasks)
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals("Worker follow up", store.manifest.subtasks.single { it.id == 2 }.name)
    val outcome = outcomes.workerSubtaskRequestOutcomes.single().outcomes.single()
    assertIs<GoalRunnerWorkerSubtaskRequestOutcome.Accepted>(outcome)
  }

  @Test
  fun `accepted worker subtask request is not made visible when audit persistence fails`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.workerSubtaskRequestOutcomeRecordResult = false
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts(
        stdout = workerSubtaskRequestJson(
          name = "Unaudited follow up",
          specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2_unaudited_follow_up.md",
        ),
      )
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "audit could not be recorded")
    assertEquals(1, store.manifest.subtasks.size)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    assertTrue(outcomes.workerSubtaskRequestOutcomes.isEmpty())
  }

  @Test
  fun `confirmation-required worker request blocks without creating hidden child state`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts(
        stdout = workerSubtaskRequestJson(
          name = "Needs approval",
          specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2_needs_approval.md",
          requiresOperatorConfirmation = true,
        ),
      )
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "operator confirmation")
    assertEquals(1, store.manifest.subtasks.size)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    val outcome = outcomes.workerSubtaskRequestOutcomes.single().outcomes.single()
    assertIs<GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation>(outcome)
  }

  @Test
  fun `reset reconciles active worker state before clearing manifest projection`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
        .withWorkflowId(1, "wfl-active"),
    )
    val outcomes = RecordingOutcomeStore()
    val service = GoalRunnerStatusService(store, outcomes)

    val reset = service.reset(
      GoalRunnerResetRequest(
        issueKey = "SKILL-56",
        hard = false,
      ),
    )

    requireNotNull(reset)
    assertEquals(ReconcileRequest("SKILL-56", emptySet(), true, null, null), outcomes.lastReconcileRequest)
    assertEquals("pending", store.manifest.subtasks.single().status)
    assertEquals(null, store.manifest.subtasks.single().workflowId)
    assertEquals(CurrentSubtaskIntent(subtaskId = 1, action = "start"), store.manifest.currentSubtaskIntent)
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

internal class InMemoryGoalManifestStore(
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

// SKILL-64 Subtask 3 (F-D01): the append-only attempt ledger and best-effort
// session accounting must not restart sequence numbers at 0 on resume. The
// recorder seeds its monotonic counters from the persisted watermarks.
class GoalRunnerLedgerRecorderSeedingTest {
  @Test
  fun `recorder seeds ledger and accounting sequences from persisted watermarks`() {
    val outcomes = RecordingOutcomeStore()
    outcomes.ledgerSequenceWatermarks =
      GoalRunnerLedgerSequenceWatermarks(maxLedgerSequence = 7, maxAccountingSequence = 3)
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest())

    recorder.recordLedgerEntry(
      GoalRunnerLedgerContext(
        workflowId = "wfl-child",
        action = GoalAttemptLedgerAction.CHILD_ACTIVATION,
        issueKey = "SKILL-56",
        subtaskId = 1,
      ),
    )
    recorder.recordAccounting("wfl-child", subtaskId = 1, phase = "implement", launchOutcome = launchFacts())

    assertEquals(8, outcomes.attemptLedgerRecords.single().entry.sequenceNumber)
    assertEquals(4, outcomes.sessionAccountingRecords.single().accounting.sequenceNumber)
  }

  @Test
  fun `recorder starts at zero when no durable entries exist`() {
    val outcomes = RecordingOutcomeStore()
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest())

    recorder.recordLedgerEntry(
      GoalRunnerLedgerContext(
        workflowId = "wfl-child",
        action = GoalAttemptLedgerAction.CHILD_ACTIVATION,
        issueKey = "SKILL-56",
        subtaskId = 1,
      ),
    )

    assertEquals(0, outcomes.attemptLedgerRecords.single().entry.sequenceNumber)
  }

  @Test
  fun `accounting is available with session path and id when launch facts expose them`() {
    // SKILL-64 Subtask 3 (AC6, AC11): provider-neutral child session path/id from
    // launch facts make accounting available=true and populate the ledger entry.
    val outcomes = RecordingOutcomeStore()
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest())
    val facts = launchFacts().copy(childSessionPath = "/work/child", childSessionId = "claude:SKILL-56:subtask-1")

    recorder.recordAccounting("wfl-child", subtaskId = 1, phase = "implement", launchOutcome = facts)
    recorder.recordLedgerEntry(
      GoalRunnerLedgerContext(
        workflowId = "wfl-child",
        action = GoalAttemptLedgerAction.CHILD_ACTIVATION,
        issueKey = "SKILL-56",
        subtaskId = 1,
        launchOutcome = facts,
      ),
    )

    val accounting = outcomes.sessionAccountingRecords.single().accounting
    assertTrue(accounting.available)
    assertEquals("/work/child", accounting.childSessionPath)
    assertEquals("claude:SKILL-56:subtask-1", accounting.childSessionId)
    val ledgerEntry = outcomes.attemptLedgerRecords.single().entry
    assertEquals("/work/child", ledgerEntry.childSessionPath)
    assertEquals("claude:SKILL-56:subtask-1", ledgerEntry.childSessionId)
  }

  @Test
  fun `accounting is unavailable with reason when no session path id or tokens exist`() {
    val outcomes = RecordingOutcomeStore()
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest())
    val facts = launchFacts().copy(childSessionPath = null, childSessionId = null)

    recorder.recordAccounting("wfl-child", subtaskId = 1, phase = "implement", launchOutcome = facts)

    val accounting = outcomes.sessionAccountingRecords.single().accounting
    assertTrue(!accounting.available)
    assertNull(accounting.childSessionPath)
    assertNull(accounting.childSessionId)
    assertContains(requireNotNull(accounting.unavailableReason), "not available")
  }

  @Test
  fun `best-effort accounting write failure never throws`() {
    val outcomes = RecordingOutcomeStore().apply { throwOnSessionAccountingRecord = true }
    val recorder = GoalRunnerLedgerRecorder(outcomes, ledgerRunRequest())

    recorder.recordAccounting("wfl-child", subtaskId = 1, phase = "implement", launchOutcome = launchFacts())
  }

  private fun ledgerRunRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

// SKILL-64 Subtask 3 (AC21, AC25, AC20, AC22, AC23): the supervisor-side
// declared-progress emitter is the production driver of the declared
// operation_* events. It persists into the durable goal_progress run history via
// recordProgressEvent WITHOUT the child phase-agent self-reporting, mints the
// timestamp in the adapter layer, and seeds a monotonic sequence from the
// persisted goal_progress watermark so resume runs stay monotonic.
class GoalRunnerProgressEventEmitterTest {
  @Test
  fun `emitter persists declared operation events into goal_progress run history`() {
    val outcomes = RecordingOutcomeStore()
    val emitter = GoalRunnerProgressEventEmitter(
      outcomeStore = outcomes,
      request = emitterRunRequest(),
      resolveWorkflowId = { "wfl-child" },
      watermarkSeed = null,
    )

    emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    emitter.emit(emission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))
    emitter.emit(
      emission(
        GoalProgressEventKind.OPERATION_COMPLETED,
        processAlive = false,
        outcome = GoalProgressOutcome.SUCCEEDED,
      ),
    )

    val recorded = outcomes.progressEventRecords
    assertEquals(3, recorded.size)
    assertEquals("wfl-child", recorded.first().workflowId)
    assertEquals(GoalProgressEventKind.OPERATION_STARTED, recorded[0].event.eventKind)
    assertEquals(GoalProgressEventKind.OPERATION_HEARTBEAT, recorded[1].event.eventKind)
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, recorded[2].event.eventKind)
    assertEquals("child_agent_run", recorded[0].event.operationName)
    assertTrue(recorded[0].event.expectedLong)
    assertTrue(recorded[0].event.processAlive)
    assertTrue(!recorded[2].event.processAlive)
    // Monotonic sequence space seeded from 0.
    assertEquals(listOf(0, 1, 2), recorded.map { it.event.sequenceNumber })
  }

  @Test
  fun `emitter seeds a monotonic sequence from the persisted goal_progress watermark on resume`() {
    val outcomes = RecordingOutcomeStore()
    val emitter = GoalRunnerProgressEventEmitter(
      outcomeStore = outcomes,
      request = emitterRunRequest(),
      resolveWorkflowId = { "wfl-child" },
      watermarkSeed = 41,
    )

    emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    emitter.emit(emission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))

    assertEquals(listOf(42, 43), outcomes.progressEventRecords.map { it.event.sequenceNumber })
  }

  @Test
  fun `emitter is a no-op until the child workflow id is known`() {
    val outcomes = RecordingOutcomeStore()
    var workflowId: String? = null
    val emitter = GoalRunnerProgressEventEmitter(
      outcomeStore = outcomes,
      request = emitterRunRequest(),
      resolveWorkflowId = { workflowId },
      watermarkSeed = null,
    )

    emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    assertTrue(outcomes.progressEventRecords.isEmpty())

    workflowId = "wfl-child"
    emitter.emit(emission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))
    assertEquals(1, outcomes.progressEventRecords.size)
    // First persisted event still anchors the sequence space at 0.
    assertEquals(0, outcomes.progressEventRecords.single().event.sequenceNumber)
  }

  @Test
  fun `emitter write failure never throws`() {
    val outcomes = RecordingOutcomeStore().apply { throwOnProgressEventRecord = true }
    val emitter = GoalRunnerProgressEventEmitter(
      outcomeStore = outcomes,
      request = emitterRunRequest(),
      resolveWorkflowId = { "wfl-child" },
      watermarkSeed = null,
    )

    emitter.emit(emission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
  }

  private fun emission(
    kind: GoalProgressEventKind,
    processAlive: Boolean,
    outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  ): AgentRunProgressEmission = AgentRunProgressEmission(
    eventKind = kind,
    processAlive = processAlive,
    operationName = "child_agent_run",
    operationKind = "long_child_run",
    expectedLong = true,
    outcome = outcome,
  )

  private fun emitterRunRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

// SKILL-64 Subtask 3 (F-NT02): the launch-reconciler wiring that builds the
// production declared-progress emitter (seeded from the persisted
// maxProgressSequence watermark, resolving the child workflow id mid-run) and
// threads it into the SkillRunRequest. Prior tests used RecordingSubtaskLauncher
// which discarded the emitter and never invoked the process loop, leaving this
// wiring with zero coverage. These tests drive the SkillRunRequest's emitter
// directly so the reconciler's wiring is exercised end-to-end.
class GoalRunnerLaunchReconcilerWiringTest {
  @Test
  fun `reconciler threads a watermark-seeded emitter that persists declared events for the resolved workflow`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wfl-1"),
    )
    val outcomes = RecordingOutcomeStore().apply {
      // The production emitter seeds its monotonic sequence from this watermark.
      ledgerSequenceWatermarks = GoalRunnerLedgerSequenceWatermarks(maxProgressSequence = 41)
    }
    val reconciler = GoalRunnerLaunchReconciler(
      manifestStore = store,
      subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
      outcomeStore = outcomes,
    )

    val launchRequest = reconciler.subtaskLaunchRequest("SKILL-56", subtaskId = 1, request = wiringRunRequest())

    // Drive the supervisor lifecycle through the emitter the reconciler actually
    // wired into the SkillRunRequest (what the process loop would call).
    val emitter = launchRequest.skillRunRequest.progressEmitter
    emitter.emit(supervisorEmission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    emitter.emit(supervisorEmission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))
    emitter.emit(
      supervisorEmission(
        GoalProgressEventKind.OPERATION_COMPLETED,
        processAlive = false,
        outcome = GoalProgressOutcome.SUCCEEDED,
      ),
    )

    val recorded = outcomes.progressEventRecords
    assertEquals(3, recorded.size, "wired emitter must persist every declared event via recordProgressEvent")
    // Workflow id resolved mid-run from the per-tick reader, not NONE.
    assertTrue(recorded.all { it.workflowId == "wfl-1" })
    assertTrue(recorded.all { it.event.workflowId == "wfl-1" })
    // Seeded from the persisted watermark (41), so the first sequence is 42 — a
    // raw AgentRunProgressEmitter.NONE would persist nothing, and seeding from 0
    // would produce 0,1,2. Both regressions fail this assertion.
    assertEquals(listOf(42, 43, 44), recorded.map { it.event.sequenceNumber })
    assertEquals(GoalProgressEventKind.OPERATION_STARTED, recorded[0].event.eventKind)
    assertEquals(GoalProgressEventKind.OPERATION_COMPLETED, recorded[2].event.eventKind)
    assertEquals(GoalProgressOutcome.SUCCEEDED, recorded[2].event.outcome)
  }

  @Test
  fun `reconciler emitter is a no-op until the child workflow id is resolvable`() {
    // No workflowId on the subtask yet: resolveWorkflowId returns null, so the
    // wired emitter must persist nothing (matching the production no-op-until-known
    // contract) rather than recording an event with a blank workflow id.
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val reconciler = GoalRunnerLaunchReconciler(
      manifestStore = store,
      subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
      outcomeStore = outcomes,
    )

    val launchRequest = reconciler.subtaskLaunchRequest("SKILL-56", subtaskId = 1, request = wiringRunRequest())
    launchRequest.skillRunRequest.progressEmitter.emit(
      supervisorEmission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true),
    )

    assertTrue(outcomes.progressEventRecords.isEmpty())
  }

  private fun supervisorEmission(
    kind: GoalProgressEventKind,
    processAlive: Boolean,
    outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  ): AgentRunProgressEmission = AgentRunProgressEmission(
    eventKind = kind,
    processAlive = processAlive,
    operationName = "child_agent_run",
    operationKind = "long_child_run",
    expectedLong = true,
    outcome = outcome,
  )

  private fun wiringRunRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

internal class RecordingOutcomeStore : GoalRunnerWorkflowOutcomeStore {
  private val outcomes: MutableMap<String, GoalRunnerStoredOutcome> = mutableMapOf()
  val progresses: MutableMap<String, GoalRunnerWorkflowProgress> = mutableMapOf()
  val blockedWorkflows: MutableList<BlockedWorkflow> = mutableListOf()
  val observabilityRecords: MutableList<GoalRunnerObservabilityRecordRequest> = mutableListOf()
  val workerSubtaskRequestOutcomes: MutableList<WorkerSubtaskRequestOutcomeRecord> = mutableListOf()
  val authoritativeOutcomesBySubtask: MutableMap<Int, GoalRunnerStoredOutcome> = mutableMapOf()
  val recoveredMissingResultPrefixOutputs: MutableList<RecoveredMissingResultPrefixOutput> = mutableListOf()
  var observabilityRecordResult: Boolean = true
  var throwOnObservabilityRecord: Boolean = false
  var throwOnProgress: Boolean = false
  var workerSubtaskRequestOutcomeRecordResult: Boolean = true
  var throwOnWorkerSubtaskRequestOutcomeRecord: Boolean = false
  var lastReconcileRequest: ReconcileRequest? = null

  operator fun set(workflowId: String, outcome: GoalRunnerStoredOutcome) {
    outcomes[workflowId] = outcome
  }

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    allowInactiveReconciliation: Boolean,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    lastReconcileRequest =
      ReconcileRequest(issueKey, activeWorkflowIds, allowInactiveReconciliation, repoRoot, dbPathOverride)
    return authoritativeOutcomesBySubtask.toMap()
  }

  override fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = outcomes[workflowId]

  override fun recoverAndPersistTerminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? = outcomes[workflowId]

  override fun recoverMissingResultPrefixOutput(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    output: Map<String, Any?>,
    dbPathOverride: String?,
  ): GoalRunnerStoredOutcome? {
    recoveredMissingResultPrefixOutputs += RecoveredMissingResultPrefixOutput(
      workflowId = workflowId,
      issueKey = issueKey,
      subtaskId = subtaskId,
      output = output,
      dbPathOverride = dbPathOverride,
    )
    return outcomes[workflowId]
  }

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

  override fun progress(workflowId: String, dbPathOverride: String?): GoalRunnerWorkflowProgress? {
    if (throwOnProgress) {
      error("progress read failed")
    }
    return progresses[workflowId]
  }

  override fun recordObservabilityEvent(
    request: GoalRunnerObservabilityRecordRequest,
    dbPathOverride: String?,
  ): Boolean {
    if (throwOnObservabilityRecord) {
      error("observability persistence failed")
    }
    observabilityRecords += request
    return observabilityRecordResult
  }

  override fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String?,
  ): Boolean {
    if (throwOnWorkerSubtaskRequestOutcomeRecord) {
      error("worker subtask request outcome persistence failed")
    }
    if (!workerSubtaskRequestOutcomeRecordResult) {
      return false
    }
    workerSubtaskRequestOutcomes += WorkerSubtaskRequestOutcomeRecord(workflowId, outcomes)
    return workerSubtaskRequestOutcomeRecordResult
  }

  val progressEventRecords: MutableList<GoalRunnerProgressEventRecordRequest> = mutableListOf()
  val sessionAccountingRecords: MutableList<GoalRunnerSessionAccountingRecordRequest> = mutableListOf()
  val attemptLedgerRecords: MutableList<GoalRunnerAttemptLedgerRecordRequest> = mutableListOf()
  var throwOnProgressEventRecord: Boolean = false
  var throwOnSessionAccountingRecord: Boolean = false

  override fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest, dbPathOverride: String?): Boolean {
    if (throwOnProgressEventRecord) {
      error("progress event persistence failed")
    }
    progressEventRecords += request
    return true
  }

  override fun recordSessionAccounting(
    request: GoalRunnerSessionAccountingRecordRequest,
    dbPathOverride: String?,
  ): Boolean {
    if (throwOnSessionAccountingRecord) {
      error("session accounting persistence failed")
    }
    sessionAccountingRecords += request
    return true
  }

  override fun recordAttemptLedgerEntry(
    request: GoalRunnerAttemptLedgerRecordRequest,
    dbPathOverride: String?,
  ): Boolean {
    attemptLedgerRecords += request
    return true
  }

  var ledgerSequenceWatermarks: GoalRunnerLedgerSequenceWatermarks = GoalRunnerLedgerSequenceWatermarks()

  override fun ledgerSequenceWatermarks(
    issueKey: String,
    dbPathOverride: String?,
  ): GoalRunnerLedgerSequenceWatermarks = ledgerSequenceWatermarks
}

internal data class BlockedWorkflow(
  val workflowId: String,
  val blockedReason: String,
  val lastResumableStep: String,
  val supervisionEvent: GoalRunnerSupervisionEvent?,
)

internal data class RecoveredMissingResultPrefixOutput(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
  val output: Map<String, Any?>,
  val dbPathOverride: String?,
)

internal data class ReconcileRequest(
  val issueKey: String,
  val activeWorkflowIds: Set<String>,
  val allowInactiveReconciliation: Boolean,
  val repoRoot: Path?,
  val dbPathOverride: String?,
)

internal data class WorkerSubtaskRequestOutcomeRecord(
  val workflowId: String,
  val outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
)

internal class RecordingSubtaskLauncher(
  private val result: (GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
) : GoalRunnerSubtaskLauncher {
  val requests: MutableList<GoalRunnerSubtaskLaunchRequest> = mutableListOf()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return result(request)
  }
}

internal class RecordingPullRequestPort : GoalPullRequestPort {
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

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-test")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
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

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = "ok")
}

private object StatusDiffGitOperations : WorkflowGitOperations {
  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "main")

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-test")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-test")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult = WorkflowWorktreeActivityResult(
    status = "ok",
    diffStat = GoalObservabilityDiffStat(filesChanged = 2, insertions = 5, deletions = 1),
  )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = "ok")
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

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = currentBranch)

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-test")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
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

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = "ok")
}

private class RecordingTimingPort(
  private val result: () -> RuntimeWaitResult,
) : RuntimeTimingPort {
  val delays: MutableList<Duration> = mutableListOf()

  override fun wait(duration: Duration): RuntimeWaitResult {
    delays += duration
    return result()
  }
}

internal fun manifest(subtaskCount: Int): DecompositionManifest = DecompositionManifest(
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

internal fun completeOutcome(subtaskId: Int): GoalRunnerStoredOutcome = GoalRunnerStoredOutcome(
  status = GoalRunnerTerminalStatus.COMPLETE,
  workflowId = "wfl-$subtaskId",
  commitSha = "sha-$subtaskId",
  lastResumableStep = "commit_push",
  suppressPr = true,
)

private fun workerSubtaskRequestJson(
  name: String,
  specPath: String,
  requiresOperatorConfirmation: Boolean = false,
): String {
  val payload = listOf(
    """"kind":"skill_bill_subtask_request"""",
    """"name":"$name"""",
    """"spec_path":"$specPath"""",
    """"requires_operator_confirmation":$requiresOperatorConfirmation""",
  ).joinToString(prefix = "{", postfix = "}")
  return "SKILL_BILL_SUBTASK_REQUEST: $payload"
}

internal fun launchFacts(
  timedOut: Boolean = false,
  interrupted: Boolean = false,
  stdout: String = "diagnostic only",
  stderr: String = "",
): AgentRunLaunchFacts = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = if (timedOut || interrupted) null else 0,
  stdout = stdout,
  stderr = stderr,
  timedOut = timedOut,
  interrupted = interrupted,
  spawnFailed = false,
)

internal fun DecompositionManifest.withWorkflowId(subtaskId: Int, workflowId: String): DecompositionManifest = copy(
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
