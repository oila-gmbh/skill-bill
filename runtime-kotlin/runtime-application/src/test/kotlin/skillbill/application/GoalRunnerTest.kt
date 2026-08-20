package skillbill.application

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.withBlockedSubtask
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.goalrunner.GoalRunnerLaunchReconciler
import skillbill.application.goalrunner.GoalRunnerLedgerContext
import skillbill.application.goalrunner.GoalRunnerLedgerRecorder
import skillbill.application.goalrunner.GoalRunnerProgressEventEmitter
import skillbill.application.goalrunner.GoalRunnerStatusService
import skillbill.application.goalrunner.UnaddressedFindingsLedgerService
import skillbill.application.goalrunner.cascadeEligiblePlanSubtaskIds
import skillbill.application.goalrunner.goalRepositoryIdentity
import skillbill.application.model.GoalRunnerAcceptRequest
import skillbill.application.model.GoalRunnerAcceptResult
import skillbill.application.model.GoalRunnerEventSink
import skillbill.application.model.GoalRunnerResetRequest
import skillbill.application.model.GoalRunnerRunEvent
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.application.model.GoalRunnerStatusRequest
import skillbill.application.workflow.repoRoot
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerAcceptedSubtask
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.model.GoalRunnerCompletionPersistenceResult
import skillbill.ports.goalrunner.model.GoalRunnerLaunchAuthorization
import skillbill.ports.goalrunner.model.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.ports.goalrunner.model.GoalRunnerLedgerSequenceWatermarks
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.model.GoalRunnerReviewPolicy
import skillbill.ports.goalrunner.model.GoalRunnerSessionAccountingRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.workflow.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.ScopedStagingGitOperations
import skillbill.ports.workflow.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionDependency
import skillbill.workflow.model.DecompositionExecutionModel
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome
import skillbill.workflow.model.SpecSource
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    assertEquals(listOf(1, 2), store.newChildWorkflowSetups.map { it.subtaskId })
    assertTrue(store.newChildWorkflowSetups.all { it.reviewBaseline.reviewBaseSha == "0".repeat(40) })
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
    // SKILL-87 (AC4): finalize reconciles with the empty active set but demands staleness evidence,
    // so it can never false-kill a still-running subtask.
    assertEquals(true, outcomes.lastReconcileRequest?.gate?.requireStalenessEvidence)
  }

  @Test
  fun `goal review summaries are acknowledged only after their event is emitted`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    outcomes.unemittedReviewPasses["wfl-1"] = listOf(
      GoalSubtaskReviewPassResult(
        passNumber = 1,
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        reviewResultArtifact = "goal_subtask_review_results.1",
        unresolvedFindingCount = 1,
        findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
      ),
    )
    val launcher = RecordingSubtaskLauncher { request ->
      store.mutate { current -> current.withWorkflowId(requireNotNull(request.skillRunRequest.subtaskId), "wfl-1") }
      outcomes["wfl-1"] = completeOutcome(1)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())
    var emittedBeforeAcknowledgement = false

    val report = runner.run(
      runRequest().copy(
        eventSink = GoalRunnerEventSink { event ->
          if (event is GoalRunnerRunEvent.SubtaskReviewSummary) {
            emittedBeforeAcknowledgement = outcomes.acknowledgedReviewPasses.isEmpty()
          }
        },
      ),
    )

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertTrue(emittedBeforeAcknowledgement)
    assertEquals(listOf("wfl-1" to 1), outcomes.acknowledgedReviewPasses)
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
  fun `resume after stop reconciles a terminal child before continuing`() {
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
    assertEquals(listOf(3), launcher.requests.map { it.skillRunRequest.subtaskId })
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
    assertContains(stopped.stop.blockedReason, "exited cleanly (status 0)")
    assertContains(stopped.stop.blockedReason, "last_resumable_step")
    assertEquals("blocked", store.manifest.subtasks.single { it.id == 1 }.status)
    assertEquals(listOf("wfl-1"), outcomes.blockedWorkflows.map { it.workflowId })
    assertEquals(1, launcher.requests.size)
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
  fun `missing terminal outcome does not retry after the process result`() {
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

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertEquals(1, launcher.requests.size)
    assertEquals(1, launches)
    assertEquals("blocked", store.manifest.status)
  }

  @Test
  fun `late terminal outcome is not polled after the child process exits`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
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
    )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `an interrupted child result does not trigger a late retry launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launchFacts(interrupted = true)
    }
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
    )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.INTERRUPTED, stopped.stop.reason)
    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `missing terminal result does not retry a worker request launch`() {
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

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)
    assertEquals(1, launches)
    assertEquals(1, launcher.requests.size)
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

    assertTrue(report is GoalRunnerRunReport.Completed, report.toString())
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
  fun `goal baseline capture failure blocks before opening or launching a child`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = RecordingOutcomeStore(),
      pullRequestPort = RecordingPullRequestPort(),
      gitOperations = RecordingGitOperations(
        currentBranch = "feat/SKILL-56-goal",
        baselineError = "staged tracked changes are present",
      ),
    )

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertContains(stopped.stop.blockedReason, "staged tracked changes are present")
    assertEquals(emptyList(), launcher.requests)
    assertEquals(emptyList(), store.newChildWorkflowSetups)
    assertNull(store.manifest.subtasks.single().workflowId)
  }

  @Test
  fun `same-branch policy guard does not demote already completed goals`() {
    val completeManifest = manifest(subtaskCount = 1)
      .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
      .copy(status = "complete", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"))
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

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

// SKILL-173: validation_depth stamping for non-skipped children. Kept outside [GoalRunnerTest]
// so that suite stays under the detekt LargeClass threshold.
class GoalRunnerValidationDepthTest {
  @Test
  fun `three non-skipped children stamp build_only then build_only then full`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 3))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(
      listOf(ValidationDepth.BUILD_ONLY, ValidationDepth.BUILD_ONLY, ValidationDepth.FULL),
      launcher.requests.map { requireNotNull(it.skillRunRequest.goalContinuation).validationDepth },
    )
  }

  @Test
  fun `single-subtask goal stamps full validation depth`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(
      ValidationDepth.FULL,
      requireNotNull(launcher.requests.single().skillRunRequest.goalContinuation).validationDepth,
    )
  }

  @Test
  fun `ordinal-last skipped promotes previous last non-skipped to full`() {
    val initial = manifest(subtaskCount = 3).copy(
      subtasks = manifest(subtaskCount = 3).subtasks.map { subtask ->
        if (subtask.id == 3) subtask.copy(status = "skipped") else subtask
      },
    )
    val store = InMemoryGoalManifestStore(manifest = initial)
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    assertIs<GoalRunnerRunReport.Completed>(runner.run(runRequest()))

    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(
      listOf(ValidationDepth.BUILD_ONLY, ValidationDepth.FULL),
      launcher.requests.map { requireNotNull(it.skillRunRequest.goalContinuation).validationDepth },
    )
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

// Linear/local spec-scratch finalize behaviour. Kept outside [GoalRunnerTest] so that suite
// stays under the detekt LargeClass threshold.
class GoalRunnerLinearScratchFinalizeTest {
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
    // Each subtask spec is deleted after its own commit; the parent + manifest only via directory
    // deletion — once before commit-all and again after PR open (idempotent).
    assertEquals(listOf(sub1, sub2), scratch.deletedFiles)
    assertEquals(listOf(specDir, specDir), scratch.deletedDirectories)
    assertEquals(listOf(sub1, sub2, specDir, specDir), scratch.deletions)
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
    assertTrue(Files.exists(specDir.resolve("spec.md")), "local mode keeps the parent spec on disk")
  }

  @Test
  fun `linear finalize deletes scratch before commit-all and completes when remaining dirt is swept`() {
    val repoRoot = Files.createTempDirectory("goal-linear-finalize")
    val scratch = RecordingSpecScratchStore()
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = " M src/Extra.kt",
      currentBranch = "feat/SKILL-56-goal",
    )
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .copy(specSource = SpecSource.LINEAR, executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      RecordingPullRequestPort(),
      specScratchStore = scratch,
      gitOperations = git,
    )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    // Once before commit-all, once after PR open (idempotent delete).
    assertEquals(2, scratch.deletedDirectories.size, "linear scratch must be deleted before commit-all")
    assertEquals(listOf(listOf("src/Extra.kt")), git.stagePathsCalls)
    assertEquals(
      listOf("chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal'"),
      git.commitMessages,
    )
    assertEquals(0, git.stageAllCalls)
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
  }

  @Test
  fun `local finalize skips commit when only the collapsed feature-specs directory is dirty`() {
    val repoRoot = Files.createTempDirectory("goal-local-finalize-specs-only")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = "?? .feature-specs/",
      currentBranch = "feat/SKILL-56-goal",
      unpushedCommits = true,
    )
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
      gitOperations = git,
    )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls)
    assertTrue(git.stagePathsCalls.isEmpty())
    assertTrue(git.commitMessages.isEmpty())
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
  }

  @Test
  fun `local finalize commit-all stages implementation paths but excludes the manifest`() {
    val repoRoot = Files.createTempDirectory("goal-local-finalize")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = " M .feature-specs/SKILL-56-goal/decomposition-manifest.yaml\n M src/Extra.kt",
      currentBranch = "feat/SKILL-56-goal",
    )
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .copy(executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      RecordingPullRequestPort(),
      specScratchStore = RecordingSpecScratchStore(),
      gitOperations = git,
    )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls)
    assertEquals(listOf(listOf("src/Extra.kt")), git.stagePathsCalls)
    assertEquals(
      listOf("chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal'"),
      git.commitMessages,
    )
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
  }

  @Test
  fun `local finalize commit-all ignores leftover collapsed feature-specs dirt`() {
    val repoRoot = Files.createTempDirectory("goal-local-finalize-collapsed-specs")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = "?? .feature-specs/\n M src/Extra.kt",
      currentBranch = "feat/SKILL-56-goal",
    )
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .copy(executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      RecordingPullRequestPort(),
      specScratchStore = RecordingSpecScratchStore(),
      gitOperations = git,
    )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls)
    assertEquals(listOf(listOf("src/Extra.kt")), git.stagePathsCalls)
    assertEquals(
      listOf("chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal'"),
      git.commitMessages,
    )
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
  }

  @Test
  fun `finalize ignores spec dirt when porcelain omits the leading dot`() {
    val repoRoot = Files.createTempDirectory("goal-spec-dot-finalize")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = " M feature-specs/SKILL-56-goal/decomposition-manifest.yaml",
      currentBranch = "feat/SKILL-56-goal",
    )
    val pullRequests = RecordingPullRequestPort()
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      pullRequests,
      specScratchStore = RecordingSpecScratchStore(),
      gitOperations = git,
    )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertTrue(git.stagePathsCalls.isEmpty())
    assertTrue(git.commitMessages.isEmpty())
    assertEquals(1, pullRequests.openCount)
  }

  @Test
  fun `finalize continues when commit-all has nothing to commit`() {
    val repoRoot = Files.createTempDirectory("goal-empty-commit-finalize")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = " M .feature-specs/SKILL-56-goal/decomposition-manifest.yaml\n M src/Extra.kt",
      currentBranch = "feat/SKILL-56-goal",
      commitError =
      "git commit -m chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal' " +
        "failed with exit code 1: On branch feat/SKILL-56-goal\n" +
        "Changes not staged for commit:\n" +
        "\tmodified:   .feature-specs/SKILL-56-goal/decomposition-manifest.yaml\n" +
        "no changes added to commit (use \"git add\" and/or \"git commit -a\")",
    )
    val pullRequests = RecordingPullRequestPort()
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .copy(executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      pullRequests,
      specScratchStore = RecordingSpecScratchStore(),
      gitOperations = git,
    )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(
      listOf("chore(SKILL-56): goal finalization commit-all on 'feat/SKILL-56-goal'"),
      git.commitMessages,
    )
    assertTrue(git.pushedBranches.isEmpty())
    assertEquals(1, pullRequests.openCount)
  }

  @Test
  fun `same-branch finalize blocks leftover implementation paths instead of goal-level commit`() {
    val repoRoot = Files.createTempDirectory("goal-same-branch-finalize-block")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = " M src/Extra.kt",
      currentBranch = "feat/SKILL-56-goal",
    )
    val pullRequests = RecordingPullRequestPort()
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      pullRequests,
      specScratchStore = RecordingSpecScratchStore(),
      gitOperations = git,
    )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "same-branch mode refuses to commit leftover implementation paths")
    assertTrue(git.commitMessages.isEmpty())
    assertEquals(0, pullRequests.openCount)
  }

  @Test
  fun `finalize with a clean worktree skips commit-all and still opens the PR`() {
    val repoRoot = Files.createTempDirectory("goal-clean-finalize")
    val git = CommitAllRecordingGitOperations(dirtyPorcelain = "", currentBranch = "feat/SKILL-56-goal")
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      RecordingPullRequestPort(),
      gitOperations = git,
    )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls)
    assertTrue(git.commitMessages.isEmpty())
    assertTrue(git.pushedBranches.isEmpty())
  }

  @Test
  fun `finalize re-pushes when worktree is clean but local tip is ahead of origin`() {
    val repoRoot = Files.createTempDirectory("goal-unpushed-finalize")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = "",
      currentBranch = "feat/SKILL-56-goal",
      unpushedCommits = true,
    )
    val pullRequests = RecordingPullRequestPort()
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      pullRequests,
      gitOperations = git,
    )

    assertIs<GoalRunnerRunReport.Completed>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(0, git.stageAllCalls, "clean worktree must not stage or commit again")
    assertTrue(git.commitMessages.isEmpty())
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
    assertEquals(1, pullRequests.openCount)
  }

  @Test
  fun `finalize blocks when clean but unpushed tip cannot be pushed`() {
    val repoRoot = Files.createTempDirectory("goal-unpushed-push-fail")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = "",
      currentBranch = "feat/SKILL-56-goal",
      unpushedCommits = true,
      pushError = "remote rejected",
    )
    val pullRequests = RecordingPullRequestPort()
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      pullRequests,
      gitOperations = git,
    )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "unpushed commits")
    assertEquals(listOf("feat/SKILL-56-goal"), git.pushedBranches)
    assertEquals(0, pullRequests.openCount, "must not open a PR from a stale remote tip")
  }

  @Test
  fun `finalize commit-all blocks when the worktree is not on the feature branch`() {
    val repoRoot = Files.createTempDirectory("goal-wrong-branch-finalize")
    val git = CommitAllRecordingGitOperations(
      dirtyPorcelain = " M leftover.kt",
      currentBranch = "feat/other-branch",
    )
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .copy(executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      RecordingPullRequestPort(),
      gitOperations = git,
    )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "requires checkout of feature branch")
    assertEquals(0, git.stageAllCalls)
  }

  @Test
  fun `finalize commit-all blocks when the feature branch is protected`() {
    val repoRoot = Files.createTempDirectory("goal-protected-finalize")
    val git = CommitAllRecordingGitOperations(dirtyPorcelain = " M leftover.kt", currentBranch = "main")
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
        .copy(featureBranch = "main", executionModel = DecompositionExecutionModel.STACKED_BRANCHES),
    )
    val runner = GoalRunner(
      store,
      RecordingSubtaskLauncher { launchFacts() },
      RecordingOutcomeStore(),
      RecordingPullRequestPort(),
      gitOperations = git,
    )

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(linearRunRequest(repoRoot)))
    assertEquals(GoalRunnerStopReason.PULL_REQUEST_FAILED, stopped.stop.reason)
    assertContains(stopped.stop.blockedReason, "refuses protected branch")
    assertEquals(0, git.stageAllCalls)
  }

  private fun linearRunRequest(repoRoot: Path): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = repoRoot,
    invokedAgentId = "claude",
    dbPathOverride = null,
  )
}

class GoalRunnerReviewPolicyPersistenceTest {
  @Test
  fun `resume without add-ons carries the durable selection into child policy and continuation`() {
    val addOn = PersistedAgentAddonSelectionEntry(
      slug = "goal-context",
      sourceIdentity = "/tmp/skillbill-goal-runner/goal-context/agent-addon.yaml",
      contentSha256 = "a".repeat(64),
    )
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    store.persistReviewPolicy(
      parentWorkflowId = "wfl-parent",
      policy = GoalRunnerReviewPolicy(
        codeReviewMode = CodeReviewExecutionMode.DEFAULT,
        agentAddonSelection = AgentAddonSelection(listOf(addOn)),
      ),
    )
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      store.mutate { current -> current.withWorkflowId(1, "wfl-1") }
      outcomes["wfl-1"] = completeOutcome(1)
      launchFacts()
    }

    GoalRunner(store, launcher, outcomes, RecordingPullRequestPort()).run(runRequest())

    assertEquals(
      AgentAddonSelection(listOf(addOn)),
      store.newChildWorkflowSetups.single().reviewPolicy.agentAddonSelection,
    )
    assertEquals(
      AgentAddonSelection(listOf(addOn)),
      launcher.requests.single().skillRunRequest.goalContinuation?.agentAddonSelection,
    )
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

class GoalRunnerPauseLaunchBoundaryTest {
  @Test
  fun `targeted completion records the pause boundary before a dependent launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest().copy(stopAfterSubtaskId = 1))

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.PAUSED, stopped.stop.reason)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertEquals(1, store.boundaryTransitionCount)
    assertTrue(store.controlState.paused)
    assertTrue(store.controlState.stopAfterConsumed)
    assertEquals("complete", store.manifest.subtasks.single { it.id == 1 }.status)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `a completed target is reconciled to pause before selecting its dependent`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 2)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner = GoalRunner(store, launcher, RecordingOutcomeStore(), RecordingPullRequestPort())

    val report = runner.run(runRequest().copy(stopAfterSubtaskId = 1))

    assertEquals(GoalRunnerStopReason.PAUSED, assertIs<GoalRunnerRunReport.Stopped>(report).stop.reason)
    assertTrue(launcher.requests.isEmpty())
    assertTrue(store.controlState.paused)
    assertTrue(store.controlState.stopAfterConsumed)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `an operator pause racing child completion is consumed once before the next launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.requestPauseForTest()
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertEquals(GoalRunnerStopReason.PAUSED, assertIs<GoalRunnerRunReport.Stopped>(report).stop.reason)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertTrue(store.controlState.paused)
    assertTrue(store.controlState.pauseConsumed)
    assertEquals("operator_request", store.controlState.pauseReason)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `pause committed at launch authorization denies the dependent child launch`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    store.beforeLaunchAuthorization = { subtaskId ->
      if (subtaskId == 2) store.requestPauseForTest()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertEquals(GoalRunnerStopReason.PAUSED, assertIs<GoalRunnerRunReport.Stopped>(report).stop.reason)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertTrue(store.controlState.paused)
    assertTrue(store.controlState.pauseConsumed)
    assertEquals("operator_request", store.controlState.pauseReason)
    assertEquals("pending", store.manifest.subtasks.single { it.id == 2 }.status)
  }

  @Test
  fun `an explicit launch clears a pause request that never reached a boundary`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    store.requestPauseForTest()
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertFalse(store.controlState.pauseRequested)
    assertFalse(store.controlState.paused)
    assertEquals(null, store.controlState.pauseReason)
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

class GoalRunnerHandoffTest {
  @Test
  fun `completed subtask does not dirty projection before next review baseline`() {
    var projectionDirty = false
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 2),
      projectionSaved = { projectionDirty = true },
    )
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      projectionDirty = false
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val reviewOperations = object : GoalSubtaskReviewGitOperations {
      override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult =
        if (projectionDirty) {
          GoalSubtaskReviewBaselineResult(status = "error", error = "unstaged tracked changes are present")
        } else {
          GoalSubtaskReviewBaselineResult(
            status = "ok",
            baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
          )
        }

      override fun buildInput(
        repoRoot: Path,
        baseline: GoalSubtaskReviewBaseline,
        expectedBranch: String,
      ): GoalSubtaskReviewInputResult = error("Review input is not used by this test.")

      override fun recoverBaseline(
        repoRoot: Path,
        request: skillbill.ports.workflow.model.GoalSubtaskReviewBaselineRecoveryRequest,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult = error("Review baseline recovery is not used by this test.")
    }
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = launcher,
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
      gitOperations = object :
        WorkflowGitOperations by RecordingGitOperations(
          currentBranch = "feat/SKILL-56-goal",
        ),
        GoalSubtaskReviewGitOperationsProvider {
        override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations = reviewOperations
      },
    )

    val report = runner.run(
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
        invokedAgentId = "claude",
        dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
      ),
    )

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1, 2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(2, store.runtimeStateSaveCount)
  }
}

class GoalRunnerRepositoryPathTest {
  @Test
  fun `absolute spec path through a repository alias stays inside the canonical repository`() {
    val canonicalRepository = Files.createTempDirectory("skillbill-goal-canonical")
    val repositoryAlias = Files.createTempDirectory("skillbill-goal-alias-parent").resolve("repository")
    Files.createSymbolicLink(repositoryAlias, canonicalRepository)
    val aliasedSpec = repositoryAlias.resolve(".feature-specs/SKILL-56-goal/spec_subtask_1.md")
    Files.createDirectories(aliasedSpec.parent)
    Files.writeString(aliasedSpec, "# Subtask 1")
    val manifest = manifest(subtaskCount = 1)
    val store = InMemoryGoalManifestStore(
      manifest = manifest.copy(
        subtasks = listOf(manifest.subtasks.single().copy(specPath = aliasedSpec.toString())),
      ),
    )
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher {
      store.mutate { current -> current.withWorkflowId(1, "wfl-1") }
      outcomes["wfl-1"] = completeOutcome(1)
      launchFacts()
    }

    val report = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort()).run(
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = repositoryAlias,
        invokedAgentId = "claude",
      ),
    )

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(
      ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
      store.newChildWorkflowSetups.single().governedSpecPath,
    )
  }
}

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
    assertContains(stopped.stop.blockedReason, "exited with status 1")
    assertContains(stopped.stop.blockedReason, "Child stderr (head+tail):")
    assertContains(stopped.stop.blockedReason, "usage limit reached before persisting terminal outcome")
    assertEquals(1, launcher.requests.size)
  }

  @Test
  fun `oversized child stderr keeps exception head and recent tail`() {
    val head = "java.lang.IllegalStateException: SQLITE_BUSY: database is locked"
    val tail = "at skillbill.cli.core.MainKt.main(Main.kt:12)"
    val stderr = head + "X".repeat(GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS * 2) + tail
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      AgentRunLaunchFacts(
        agent = InstallAgent.CLAUDE,
        exitStatus = 1,
        stdout = "diagnostic only",
        stderr = stderr,
        timedOut = false,
        interrupted = false,
        spawnFailed = false,
      )
    }
    val runner = GoalRunner(store, launcher, RecordingOutcomeStore(), RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertContains(stopped.stop.blockedReason, head)
    assertContains(stopped.stop.blockedReason, tail)
    assertContains(stopped.stop.blockedReason, "chars omitted")
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

// Starts dirty, then clears after createCommit so post-sweep cleanliness verification can pass.
private class CommitAllRecordingGitOperations(
  private val dirtyPorcelain: String,
  private val currentBranch: String,
  private val unpushedCommits: Boolean = false,
  private val pushError: String? = null,
  private val commitError: String? = null,
) : WorkflowGitOperations, GoalSubtaskReviewGitOperationsProvider, ScopedStagingGitOperationsProvider {
  var stageAllCalls: Int = 0
  val stagePathsCalls: MutableList<List<String>> = mutableListOf()
  val commitMessages: MutableList<String> = mutableListOf()
  val pushedBranches: MutableList<String> = mutableListOf()
  private var porcelain: String = dirtyPorcelain

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = branch)

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "true")

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = currentBranch)

  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult {
    stageAllCalls += 1
    return WorkflowGitOperationResult(status = "ok", value = "")
  }

  override val scopedStagingOperations: ScopedStagingGitOperations = object : ScopedStagingGitOperations {
    override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult {
      stagePathsCalls += paths
      return WorkflowGitOperationResult(status = "ok", value = "")
    }

    override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = "")

    override fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = "")

    override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = "")

    override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
      WorkflowGitOperationResult(status = "ok", value = "")
  }

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    commitMessages += message
    porcelain = porcelain.lineSequence()
      .filter { line ->
        line.length >= 4 &&
          line.substring(3).substringAfterLast(" -> ").trim().trimEnd('/').let { path ->
            path == ".feature-specs" || path.startsWith(".feature-specs/")
          }
      }
      .joinToString("\n")
    return commitError?.let { WorkflowGitOperationResult(status = "error", error = it) }
      ?: WorkflowGitOperationResult(status = "ok", value = "sha-finalize")
  }

  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    pushedBranches += branch
    return pushError?.let { WorkflowGitOperationResult(status = "error", error = it) }
      ?: WorkflowGitOperationResult(status = "ok", value = branch)
  }

  override fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = if (unpushedCommits) "true" else "false")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "sha-finalize")

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = porcelain)

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    WorkflowWorktreeActivityResult(status = "ok")

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(status = "ok")

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations = readyGoalReviewOperations()
}

class GoalRunnerStatusProjectionTest {
  @Test
  fun `execution liveness is live only while a runtime worker lease is strictly unexpired`() {
    val harness = GoalStatusPhaseLedgerHarness()
    harness.openRuntimeWorkflow("wfl-live")
    harness.seedOwnership("wfl-live", expiresAt = "2026-07-27T12:00:01Z")
    val service = statusServiceForLiveness(harness, "wfl-live")

    val live = requireNotNull(service.status(goalStatusRequest()))
    assertEquals(ExecutionLiveness.LIVE, live.executionLiveness)

    harness.seedOwnership("wfl-live", expiresAt = "2026-07-27T12:00:00Z")
    val boundary = requireNotNull(service.status(goalStatusRequest()))
    assertEquals(ExecutionLiveness.IDLE, boundary.executionLiveness)
  }

  @Test
  fun `execution liveness is idle when a runtime workflow has no ownership row`() {
    val harness = GoalStatusPhaseLedgerHarness()
    harness.openRuntimeWorkflow("wfl-idle")

    val status = requireNotNull(statusServiceForLiveness(harness, "wfl-idle").status(goalStatusRequest()))

    assertEquals(ExecutionLiveness.IDLE, status.executionLiveness)
    assertEquals(0, harness.ownershipWriteCount)
  }

  @Test
  fun `execution liveness is idle when lease or identity is missing and unknown on lease read failure`() {
    // No parent lease after clean exit is idle — not unknown — so watch/replan can proceed at boundaries.
    val missingLeaseStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1))
    assertEquals(
      ExecutionLiveness.IDLE,
      requireNotNull(
        GoalRunnerStatusService(missingLeaseStore, RecordingOutcomeStore(), goalTestPhaseRecorder())
          .status(goalStatusRequest()),
      ).executionLiveness,
    )

    // Intent naming a subtask that is not on the manifest still falls through to the parent lease path.
    val missingCurrentSubtaskStore = InMemoryGoalManifestStore(
      manifest(subtaskCount = 1)
        .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "resume")),
    )
    assertEquals(
      ExecutionLiveness.IDLE,
      requireNotNull(
        GoalRunnerStatusService(missingCurrentSubtaskStore, RecordingOutcomeStore(), goalTestPhaseRecorder())
          .status(goalStatusRequest()),
      ).executionLiveness,
    )

    val failingHarness = GoalStatusPhaseLedgerHarness()
    failingHarness.openRuntimeWorkflow("wfl-failing")
    failingHarness.failOwnershipReads = true
    assertEquals(
      ExecutionLiveness.UNKNOWN,
      requireNotNull(
        statusServiceForLiveness(failingHarness, "wfl-failing").status(goalStatusRequest()),
      ).executionLiveness,
    )
    assertEquals(0, failingHarness.ownershipWriteCount)
  }

  @Test
  fun `execution liveness uses the parent lease before a child workflow exists`() {
    val store = InMemoryGoalManifestStore(manifest(subtaskCount = 1)).apply {
      executionLeaseForTest = GoalRunnerExecutionLease(
        generation = 1,
        ownerToken = "parent-owner",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 42,
        processBirthToken = "birth-42",
        heartbeatAt = "2026-07-27T11:59:50Z",
        expiresAt = "2026-07-27T12:00:01Z",
      )
    }
    val service = GoalRunnerStatusService(
      manifestStore = store,
      outcomeStore = RecordingOutcomeStore(),
      phaseRecorder = goalTestPhaseRecorder(),
      clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
    )

    assertEquals(ExecutionLiveness.LIVE, requireNotNull(service.status(goalStatusRequest())).executionLiveness)

    store.executionLeaseForTest = store.executionLeaseForTest!!.copy(expiresAt = "2026-07-27T11:59:59Z")
    assertEquals(ExecutionLiveness.IDLE, requireNotNull(service.status(goalStatusRequest())).executionLiveness)
  }

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
      phaseRecorder = goalTestPhaseRecorder(),
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
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

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
    assertEquals("in_progress", store.manifest.status)
    assertEquals("resume", store.manifest.currentSubtaskIntent.action)
    assertEquals("pending", store.manifest.subtasks.single().status)
    assertEquals(0, store.saveCount)
    assertEquals("SKILL-56" to null, outcomes.lastAuthoritativeOutcomeRequest)
    assertEquals(null, outcomes.lastReconcileRequest)
  }

  @Test
  fun `status projection does not persist terminal child reconciliation`() {
    val stored = manifest(subtaskCount = 2)
      .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
      .withWorkflowId(1, "wfl-1")
    val store = InMemoryGoalManifestStore(stored)
    val outcomes = RecordingOutcomeStore().apply {
      authoritativeOutcomesBySubtask[1] = completeOutcome(1).copy(workflowId = "wfl-1")
    }
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(1, status.pendingCount)
    assertEquals(stored, store.manifest)
    assertEquals(0, store.saveCount)
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
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

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
    assertEquals("blocked", store.manifest.status)
    assertEquals("blocked", store.manifest.currentSubtaskIntent.action)
    val subtask = store.manifest.subtasks.single()
    assertEquals("blocked", subtask.status)
    assertEquals("wfl-stale", subtask.workflowId)
    assertEquals(0, store.saveCount)
  }

  @Test
  fun `status reconciliation preserves completed manifest subtask when child workflow has stale blocked outcome`() {
    val completedManifest = manifest(subtaskCount = 1)
      .copy(
        status = "complete",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
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
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

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
    assertEquals("complete", store.manifest.currentSubtaskIntent.action)
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
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

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
  fun `status projection ignores retained blocked outcome when the same child is running after retry`() {
    val staleManifest = manifest(subtaskCount = 1)
      .withBlockedSubtask(1, workflowId = "wfl-active", reason = "pre-retry block")
    val outcomes = RecordingOutcomeStore().apply {
      this["wfl-active"] = GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.BLOCKED,
        workflowId = "wfl-active",
        blockedReason = "pre-retry block",
        lastResumableStep = "implement",
        suppressPr = true,
      )
      progresses["wfl-active"] = GoalRunnerWorkflowProgress(
        workflowId = "wfl-active",
        workflowStatus = "running",
        currentStepId = "implement",
        progressToken = "retry-token",
        latestDurableProgressEvent = null,
        latestLivenessSignal = "running",
        lastSnapshotUpdatedAt = "2026-07-21 18:02:05",
      )
    }
    val store = InMemoryGoalManifestStore(staleManifest)
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

    val status = service.status(GoalRunnerStatusRequest(issueKey = "SKILL-56", invokedAgentId = "codex"))

    requireNotNull(status)
    assertEquals(0, status.blockedCount)
    assertEquals(1, status.currentSubtaskId)
    assertEquals("implement", status.currentStep)
    assertEquals(staleManifest, store.manifest)
    assertEquals(0, store.saveCount)
  }

  @Test
  fun `status reconciliation preserves reset pending subtask when blocked sibling outcome exists`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore().apply {
      authoritativeOutcomesBySubtask[1] = GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.BLOCKED,
        workflowId = "wfl-blocked-before-reset",
        blockedReason = "old sibling blocked",
        lastResumableStep = "review",
        suppressPr = true,
      )
    }
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(1, status.pendingCount)
    assertEquals(0, status.blockedCount)
    val subtask = store.manifest.subtasks.single()
    assertEquals("pending", subtask.status)
    assertEquals(null, subtask.workflowId)
  }

  @Test
  fun `status projects blocked terminal outcome without persisting it`() {
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
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

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
    assertEquals("in_progress", store.manifest.status)
    assertEquals("resume", store.manifest.currentSubtaskIntent.action)
    val subtask = store.manifest.subtasks.single()
    assertEquals("pending", subtask.status)
    assertEquals(null, subtask.blockedReason)
    assertEquals(null, subtask.lastResumableStep)
    assertEquals(0, store.saveCount)
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
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

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

  @Test
  fun `status projection shows pending_launch when current subtask is selected but not yet launched`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 2)
        .copy(
          status = "in_progress",
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "start"),
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
            DecompositionSubtask(
              id = 2,
              name = "Subtask 2",
              specPath = ".feature-specs/SKILL-56-goal/spec_subtask_2.md",
              status = "pending",
              workflowId = null,
              lastResumableStep = null,
            ),
          ),
        ),
    )
    val outcomes = RecordingOutcomeStore()
    outcomes["wfl-1"] = completeOutcome(1)
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    assertEquals(2, status.currentSubtaskId)
    assertEquals("pending_launch", status.currentStep)
  }
}

class GoalRunnerPauseStatusTest {
  @Test
  fun `pause is consumed when the goal is stranded before launching a subtask`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder())

    val result = service.pause(
      issueKey = "SKILL-56",
      dbPathOverride = null,
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    )

    assertEquals("paused", result.status)
    assertTrue(result.paused)
    assertTrue(result.pauseRequested)
    assertTrue(store.controlState.paused)
    assertEquals(1, store.boundaryTransitionCount)
  }

  @Test
  fun `resume clears a pause request that never reached a boundary`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    store.requestPauseForTest()
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder())

    val result = service.resume(
      issueKey = "SKILL-56",
      dbPathOverride = null,
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    )

    assertEquals("resumed", result.status)
    assertEquals("operator_request", result.clearedPauseReason)
    assertFalse(store.controlState.pauseRequested)
    assertFalse(store.controlState.paused)
  }

  @Test
  fun `resume reports not_paused when no pause boundary is durable`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder())

    val result = service.resume(
      issueKey = "SKILL-56",
      dbPathOverride = null,
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    )

    assertEquals("not_paused", result.status)
  }
}

private fun statusServiceForLiveness(
  harness: GoalStatusPhaseLedgerHarness,
  workflowId: String,
): GoalRunnerStatusService = GoalRunnerStatusService(
  manifestStore = InMemoryGoalManifestStore(
    manifest(subtaskCount = 1)
      .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"))
      .withWorkflowId(1, workflowId),
  ),
  outcomeStore = RecordingOutcomeStore(),
  phaseRecorder = harness.recorder,
  clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
)

private fun goalStatusRequest() = GoalRunnerStatusRequest(issueKey = "SKILL-56", invokedAgentId = "codex")

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
  fun `accept records a blocked subtask that landed out of band and advances the goal`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 2).copy(
        status = "blocked",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"),
        subtasks = manifest(subtaskCount = 2).subtasks.map { subtask ->
          if (subtask.id == 1) {
            subtask.copy(status = "blocked", blockedReason = "session limit", lastResumableStep = "implement")
          } else {
            subtask
          }
        },
      ),
    )
    val service = GoalRunnerStatusService(
      manifestStore = store,
      outcomeStore = RecordingOutcomeStore(),
      phaseRecorder = goalTestPhaseRecorder(),
      gitOperations = AcceptGitOperations(),
    )

    val result = service.accept(
      GoalRunnerAcceptRequest(
        issueKey = "SKILL-56",
        subtaskId = 1,
        commitSha = "abc1234",
        reason = "finished by hand after the runtime blocked",
        repoRoot = Path.of("."),
      ),
    )

    val accepted = assertIs<GoalRunnerAcceptResult.Accepted>(result)
    assertEquals("abc1234abc1234abc1234abc1234abc1234abcd", accepted.commitSha)
    assertEquals(listOf("wfl-parent"), store.acceptedParentWorkflowIds)
    val subtaskOne = store.manifest.subtasks.first { it.id == 1 }
    assertEquals("complete", subtaskOne.status)
    assertEquals("abc1234abc1234abc1234abc1234abc1234abcd", subtaskOne.commitSha)
    assertNull(subtaskOne.blockedReason)
    assertEquals(CurrentSubtaskIntent(subtaskId = 2, action = "start"), store.manifest.currentSubtaskIntent)
  }

  @Test
  fun `accept rejects a commit that does not resolve in the repository`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val service = GoalRunnerStatusService(
      manifestStore = store,
      outcomeStore = RecordingOutcomeStore(),
      phaseRecorder = goalTestPhaseRecorder(),
      gitOperations = AcceptGitOperations(),
    )

    val result = service.accept(
      GoalRunnerAcceptRequest(
        issueKey = "SKILL-56",
        subtaskId = 1,
        commitSha = "deadbee",
        reason = "landed by hand",
        repoRoot = Path.of("."),
      ),
    )

    assertIs<GoalRunnerAcceptResult.Rejected>(result)
    assertEquals(emptyList(), store.acceptedParentWorkflowIds)
    assertEquals("pending", store.manifest.subtasks.single().status)
  }

  @Test
  fun `accept rejects a subtask whose dependency is not satisfied`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val service = GoalRunnerStatusService(
      manifestStore = store,
      outcomeStore = RecordingOutcomeStore(),
      phaseRecorder = goalTestPhaseRecorder(),
      gitOperations = AcceptGitOperations(),
    )

    val result = service.accept(
      GoalRunnerAcceptRequest(
        issueKey = "SKILL-56",
        subtaskId = 2,
        commitSha = "abc1234",
        reason = "landed by hand",
        repoRoot = Path.of("."),
      ),
    )

    assertIs<GoalRunnerAcceptResult.Rejected>(result)
    assertEquals(emptyList(), store.acceptedParentWorkflowIds)
  }

  @Test
  fun `status reconciliation keeps an accepted subtask complete across later reads`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 2).copy(
        subtasks = manifest(subtaskCount = 2).subtasks.map { subtask ->
          if (subtask.id == 1) subtask.copy(status = "blocked", blockedReason = "session limit") else subtask
        },
      ),
    )
    val service = GoalRunnerStatusService(
      manifestStore = store,
      outcomeStore = RecordingOutcomeStore(),
      phaseRecorder = goalTestPhaseRecorder(),
      gitOperations = AcceptGitOperations(),
    )
    service.accept(
      GoalRunnerAcceptRequest(
        issueKey = "SKILL-56",
        subtaskId = 1,
        commitSha = "abc1234",
        reason = "landed by hand",
        repoRoot = Path.of("."),
      ),
    )

    val projection = service.status(
      GoalRunnerStatusRequest(issueKey = "SKILL-56", invokedAgentId = "claude", repoRoot = Path.of(".")),
    )

    requireNotNull(projection)
    assertEquals(1, projection.completeCount)
    assertEquals(2, projection.currentSubtaskId)
    // The git-tracked manifest deliberately omits commit SHAs, so status is the only place a human
    // can see which commit an accepted subtask points at.
    val accepted = projection.outOfBandAcceptances.single()
    assertEquals(1, accepted.subtaskId)
    assertEquals("abc1234abc1234abc1234abc1234abc1234abcd", accepted.commitSha)
    assertEquals("landed by hand", accepted.reason)
  }

  @Test
  fun `soft reset preserves child identity and resumable step`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .copy(status = "in_progress", currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"))
        .withWorkflowId(1, "wfl-active")
        .copy(
          subtasks = listOf(
            manifest(subtaskCount = 1).subtasks.single().copy(
              status = "blocked",
              workflowId = "wfl-active",
              blockedReason = "repair required",
              lastResumableStep = "implement",
            ),
          ),
        ),
    )
    val outcomes = RecordingOutcomeStore()
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

    val reset = service.reset(
      GoalRunnerResetRequest(
        issueKey = "SKILL-56",
        hard = false,
      ),
    )

    requireNotNull(reset)
    // AC6: reset keeps the aggressive shape — allowInactiveReconciliation=true and NO staleness gate.
    assertEquals(
      ReconcileRequest(
        "SKILL-56",
        emptySet(),
        GoalRunnerReconcileGate(allowInactiveReconciliation = true, requireStalenessEvidence = false),
        null,
        null,
      ),
      outcomes.lastReconcileRequest,
    )
    assertEquals("in_progress", store.manifest.subtasks.single().status)
    assertEquals("wfl-active", store.manifest.subtasks.single().workflowId)
    assertEquals("implement", store.manifest.subtasks.single().lastResumableStep)
    assertNull(store.manifest.subtasks.single().blockedReason)
    assertEquals(CurrentSubtaskIntent(subtaskId = 1, action = "resume"), store.manifest.currentSubtaskIntent)
  }

  @Test
  fun `soft reset restarts blocked subtask without child identity`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1).copy(
        status = "blocked",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"),
        subtasks = listOf(
          manifest(subtaskCount = 1).subtasks.single().copy(
            status = "blocked",
            blockedReason = "branch setup failed",
            lastResumableStep = "create_branch",
          ),
        ),
      ),
    )
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder())

    service.reset(GoalRunnerResetRequest(issueKey = "SKILL-56", hard = false))

    assertEquals("pending", store.manifest.subtasks.single().status)
    assertNull(store.manifest.subtasks.single().workflowId)
    assertNull(store.manifest.subtasks.single().lastResumableStep)
    assertEquals(CurrentSubtaskIntent(subtaskId = 1, action = "start"), store.manifest.currentSubtaskIntent)
  }

  @Test
  fun `scoped recovery resets only selected terminal child`() {
    val original = manifest(subtaskCount = 2).copy(
      status = "blocked",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "blocked"),
      subtasks = manifest(subtaskCount = 2).subtasks.map { subtask ->
        when (subtask.id) {
          1 -> subtask.copy(status = "complete", commitSha = "full-commit-1")
          else -> subtask.copy(
            status = "blocked",
            workflowId = "wfl-stale",
            blockedReason = "terminal child",
            lastResumableStep = "implement",
          )
        }
      },
    )
    val store = InMemoryGoalManifestStore(original)
    val outcomes = RecordingOutcomeStore().apply {
      progresses["wfl-stale"] = GoalRunnerWorkflowProgress(
        workflowId = "wfl-stale",
        workflowStatus = "failed",
        currentStepId = "implement",
        progressToken = "terminal",
      )
    }

    val reset = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder()).reset(
      GoalRunnerResetRequest(
        issueKey = "SKILL-56",
        hard = false,
        subtaskId = 2,
        deleteChildWorkflow = true,
      ),
    )

    requireNotNull(reset)
    assertEquals("scoped_child_recovery", reset.mode)
    assertEquals(original.subtasks.first(), store.manifest.subtasks.first())
    assertEquals("pending", store.manifest.subtasks.last().status)
    assertNull(store.manifest.subtasks.last().workflowId)
    assertEquals(null, outcomes.lastReconcileRequest, "selector validation must not mutate through reconciliation")
  }

  @Test
  fun `scoped recovery rejects resumable child without mutation`() {
    val original = manifest(subtaskCount = 1)
      .copy(status = "blocked", currentSubtaskIntent = CurrentSubtaskIntent(1, "blocked"))
      .withBlockedSubtask(1, workflowId = "wfl-resumable", reason = "paused")
    val store = InMemoryGoalManifestStore(original)
    val outcomes = RecordingOutcomeStore().apply {
      progresses["wfl-resumable"] = GoalRunnerWorkflowProgress(
        workflowId = "wfl-resumable",
        workflowStatus = "paused",
        currentStepId = "implement",
        progressToken = "resumable",
      )
    }

    assertFailsWith<IllegalArgumentException> {
      GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder()).reset(
        GoalRunnerResetRequest(
          issueKey = "SKILL-56",
          hard = false,
          subtaskId = 1,
          deleteChildWorkflow = true,
        ),
      )
    }

    assertEquals(original, store.manifest)
    assertEquals(0, store.saveCount)
    assertEquals(null, outcomes.lastReconcileRequest)
  }

  @Test
  fun `hard reset deletes goal planning preparation before saving pending projection`() {
    val database = GoalTestPlanningDatabase()
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 2),
      hardReset = { state, dbPathOverride ->
        database.transaction(dbPathOverride) { unitOfWork ->
          unitOfWork.goalPlanningPreparations.deleteByGoal(state.parentWorkflowId)
          unitOfWork.workflowStates.deleteGoalChildWorkflowsByParent(state.parentWorkflowId)
        }
      },
    )
    val service = GoalRunnerStatusService(
      store,
      RecordingOutcomeStore(),
      goalTestPhaseRecorder(),
    )

    val reset = service.reset(
      GoalRunnerResetRequest(
        issueKey = "SKILL-56",
        hard = true,
        dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      ),
    )

    requireNotNull(reset)
    assertEquals(listOf("wfl-parent"), database.deletedParentGoalIds)
    assertEquals(listOf("wfl-parent"), database.deletedChildWorkflowParentIds)
    assertEquals(listOf<String?>("/tmp/skillbill-goal-runner/metrics.db"), database.transactionDbOverrides)
    assertEquals(listOf("pending", "pending"), store.manifest.subtasks.map(DecompositionSubtask::status))
  }

  @Test
  fun `hard reset lists discarded acceptance and only explicit restoration recreates it`() {
    val original = manifest(subtaskCount = 2).withBlockedSubtask(
      subtaskId = 1,
      workflowId = "wfl-manual",
      reason = "finished outside runtime",
    )
    val store = InMemoryGoalManifestStore(original)
    val service = GoalRunnerStatusService(
      store,
      RecordingOutcomeStore(),
      goalTestPhaseRecorder(),
      AcceptGitOperations(),
    )
    val accepted = service.accept(
      GoalRunnerAcceptRequest(
        issueKey = "SKILL-56",
        subtaskId = 1,
        commitSha = "abc1234",
        reason = "reviewed; ship it",
        repoRoot = Path.of("."),
      ),
    )
    val acceptedSha = assertIs<GoalRunnerAcceptResult.Accepted>(accepted).commitSha

    assertEquals(
      listOf(GoalRunnerAcceptedSubtask(1, acceptedSha, "reviewed; ship it", accepted.acceptedAt)),
      service.hardResetPreflight("SKILL-56", null),
    )
    service.reset(GoalRunnerResetRequest(issueKey = "SKILL-56", hard = true, repoRoot = Path.of(".")))
    assertEquals(emptyList(), service.hardResetPreflight("SKILL-56", null))
    assertIs<GoalRunnerAcceptResult.Rejected>(
      service.accept(
        GoalRunnerAcceptRequest(
          issueKey = "SKILL-56",
          subtaskId = 1,
          commitSha = acceptedSha,
          reason = "reviewed; ship it",
          repoRoot = Path.of("."),
        ),
      ),
    )

    val restored = service.accept(
      GoalRunnerAcceptRequest(
        issueKey = "SKILL-56",
        subtaskId = 1,
        commitSha = acceptedSha,
        reason = "reviewed; ship it",
        repoRoot = Path.of("."),
        restoreAfterHardReset = true,
      ),
    )

    assertIs<GoalRunnerAcceptResult.Accepted>(restored)
    assertEquals(
      listOf(GoalRunnerAcceptedSubtask(1, acceptedSha, "reviewed; ship it", restored.acceptedAt)),
      service.hardResetPreflight("SKILL-56", null),
    )
  }

  @Test
  fun `hard reset requires repository root for checkpoint ref cleanup`() {
    val service = GoalRunnerStatusService(
      InMemoryGoalManifestStore(manifest(subtaskCount = 1)),
      RecordingOutcomeStore(),
      goalTestPhaseRecorder(),
    )

    assertFailsWith<IllegalArgumentException> {
      service.reset(GoalRunnerResetRequest(issueKey = "SKILL-56", hard = true))
    }
  }

  @Test
  fun `hard reset rejects repository root that does not match the goal identity`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1),
      initialControlState = GoalRunnerControlState(
        repositoryIdentity = goalRepositoryIdentity(Path.of("/tmp/bound-repo")),
      ),
    )
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), goalTestPhaseRecorder())

    assertFailsWith<IllegalArgumentException> {
      service.reset(
        GoalRunnerResetRequest(
          issueKey = "SKILL-56",
          hard = true,
          repoRoot = Path.of("/tmp/other-repo"),
        ),
      )
    }
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}

class GoalRunnerManifestReconciliationTest {
  @Test
  fun `goal run reconciles terminal child before selecting the next subtask`() {
    val staleManifest = manifest(subtaskCount = 2).copy(
      status = "in_progress",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
      subtasks = manifest(subtaskCount = 2).subtasks.map { subtask ->
        if (subtask.id == 1) subtask.copy(status = "pending", workflowId = "wfl-1") else subtask
      },
    )
    val store = InMemoryGoalManifestStore(staleManifest)
    val outcomes = RecordingOutcomeStore().apply {
      authoritativeOutcomesBySubtask[1] = completeOutcome(1)
    }
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      assertEquals(2, subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-2") }
      outcomes["wfl-2"] = completeOutcome(2)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
        invokedAgentId = "claude",
        dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
      ),
    )

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(2), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(listOf(2), store.newChildWorkflowSetups.map { it.subtaskId })
    assertEquals("complete", store.manifest.subtasks.first { it.id == 1 }.status)
    assertEquals("sha-1", store.manifest.subtasks.first { it.id == 1 }.commitSha)
  }
}

internal class InMemoryGoalManifestStore(
  manifest: DecompositionManifest,
  private val hardReset: ((GoalRunnerManifestState, String?) -> Unit)? = null,
  private val projectionSaved: (() -> Unit)? = null,
  initialControlState: GoalRunnerControlState = GoalRunnerControlState(),
) : GoalRunnerManifestStore {
  var manifest: DecompositionManifest = manifest
    private set
  var saveCount: Int = 0
    private set
  var runtimeStateSaveCount: Int = 0
    private set
  var controlState: GoalRunnerControlState = initialControlState
    private set
  var executionLeaseForTest: GoalRunnerExecutionLease? = null
  var boundaryTransitionCount: Int = 0
    private set
  var beforeLaunchAuthorization: ((Int) -> Unit)? = null
  private var persistedReviewPolicy: GoalRunnerReviewPolicy? = null
  val newChildWorkflowSetups: MutableList<GoalRunnerChildWorkflowSetup> = mutableListOf()
  val acceptedParentWorkflowIds: MutableList<String> = mutableListOf()
  var acceptances: Map<Int, GoalRunnerOutOfBandAcceptance> = emptyMap()
    private set

  override fun loadByIssueKey(issueKey: String, dbPathOverride: String?, repoRoot: Path?): GoalRunnerManifestState? =
    GoalRunnerManifestState(
      parentWorkflowId = "wfl-parent",
      dbPath = dbPathOverride.orEmpty().ifBlank { "/tmp/skillbill-goal-runner/metrics.db" },
      manifest = manifest,
    ).takeIf { manifest.issueKey == issueKey }

  override fun save(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    saveCount += 1
    projectionSaved?.invoke()
    manifest = state.manifest
    return state.copy(dbPath = dbPathOverride ?: state.dbPath, manifest = manifest)
  }

  override fun saveRuntimeState(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    runtimeStateSaveCount += 1
    manifest = state.manifest
    return state.copy(dbPath = dbPathOverride ?: state.dbPath, manifest = manifest)
  }

  override fun controlState(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState = controlState

  override fun bindRepositoryIdentity(
    parentWorkflowId: String,
    repositoryIdentity: String,
    dbPathOverride: String?,
  ): GoalRunnerControlState {
    require(controlState.repositoryIdentity == null || controlState.repositoryIdentity == repositoryIdentity) {
      "Goal parent '$parentWorkflowId' belongs to another repository."
    }
    if (controlState.repositoryIdentity == null) {
      controlState = controlState.copy(repositoryIdentity = repositoryIdentity)
    }
    return controlState
  }

  override fun executionLease(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerExecutionLease? =
    executionLeaseForTest

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
    dbPathOverride: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    dbPathOverride: String?,
  ): Boolean = true

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
    dbPathOverride: String?,
  ): Boolean = true

  override fun requestPause(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerControlState {
    controlState = controlState.copy(
      pauseRequested = true,
      pauseReason = controlState.pauseReason ?: "operator_request",
    )
    return controlState
  }

  override fun authorizeSubtaskLaunch(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerLaunchAuthorization {
    val spawnAuthorization = object : AgentRunSpawnAuthorization {
      override fun <T> withAuthorization(spawn: () -> T): T {
        beforeLaunchAuthorization?.invoke(subtaskId)
        if (controlState.requiresPauseBoundary(state.manifest)) {
          throw GoalRunnerLaunchAuthorizationDeniedException(controlState)
        }
        return spawn()
      }
    }
    return GoalRunnerLaunchAuthorization(
      authorized = !controlState.requiresPauseBoundary(state.manifest),
      controlState = controlState,
      spawnAuthorization = spawnAuthorization,
    )
  }

  override fun reviewPolicy(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerReviewPolicy? =
    persistedReviewPolicy

  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
    dbPathOverride: String?,
  ): GoalRunnerReviewPolicy {
    persistedReviewPolicy = policy
    return policy
  }

  override fun persistStopAfterSubtask(
    parentWorkflowId: String,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerControlState {
    controlState = controlState.copy(stopAfterSubtaskId = controlState.stopAfterSubtaskId ?: subtaskId)
    return controlState
  }

  fun requestPauseForTest() {
    controlState = controlState.copy(
      pauseRequested = true,
      pauseReason = controlState.pauseReason ?: "operator_request",
    )
  }

  override fun pauseAtBoundary(state: GoalRunnerManifestState, dbPathOverride: String?): GoalRunnerManifestState {
    val targetReached = controlState.stopAfterSubtaskId?.let { targetId ->
      state.manifest.subtasks.any { it.id == targetId && it.status == "complete" }
    } == true && !controlState.stopAfterConsumed
    if (!controlState.requiresPauseBoundary(state.manifest)) return state
    controlState = when {
      controlState.paused -> controlState.copy(stopAfterConsumed = controlState.stopAfterConsumed || targetReached)
      controlState.pauseRequested -> controlState.copy(
        pauseConsumed = true,
        paused = true,
        pauseReason = controlState.pauseReason ?: "operator_request",
        pausedAt = FAKE_PAUSED_AT,
        stopAfterConsumed = controlState.stopAfterConsumed || targetReached,
      )
      else -> controlState.copy(
        paused = true,
        pauseReason = "stop_after_subtask",
        pausedAt = FAKE_PAUSED_AT,
        stopAfterConsumed = true,
      )
    }
    boundaryTransitionCount += 1
    return state.copy(controlState = controlState)
  }

  override fun resume(parentWorkflowId: String, dbPathOverride: String?): GoalRunnerManifestState {
    controlState = controlState.copy(
      pauseRequested = false,
      pauseConsumed = false,
      paused = false,
      pauseReason = null,
      pausedAt = null,
    )
    return loadByIssueKey(manifest.issueKey, dbPathOverride, null)!!.copy(controlState = controlState)
  }

  override fun saveCompletedSubtaskAtBoundary(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
  ): GoalRunnerCompletionPersistenceResult {
    runtimeStateSaveCount += 1
    manifest = state.manifest
    val targetReached = controlState.stopAfterSubtaskId == subtaskId && !controlState.stopAfterConsumed
    val operatorRequested = controlState.pauseRequested && !controlState.pauseConsumed
    val shouldPause = controlState.paused || targetReached || operatorRequested
    if (shouldPause) {
      controlState = controlState.copy(
        pauseConsumed = controlState.pauseConsumed || operatorRequested,
        paused = true,
        pauseReason = when {
          controlState.paused -> controlState.pauseReason
          operatorRequested -> "operator_request"
          else -> "stop_after_subtask"
        },
        pausedAt = controlState.pausedAt ?: FAKE_PAUSED_AT,
        stopAfterConsumed = controlState.stopAfterConsumed || targetReached,
      )
      boundaryTransitionCount += 1
    }
    return GoalRunnerCompletionPersistenceResult(
      state = state.copy(manifest = manifest, controlState = controlState),
      paused = shouldPause,
    )
  }

  override fun saveHardReset(
    state: GoalRunnerManifestState,
    dbPathOverride: String?,
    preservePlanning: Boolean,
  ): GoalRunnerManifestState {
    hardReset?.invoke(state, dbPathOverride)
    acceptances = emptyMap()
    controlState = GoalRunnerControlState()
    return save(state, dbPathOverride)
  }

  override fun deleteIncompatibleChildWorkflow(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    workflowId: String,
    dbPathOverride: String?,
  ): GoalRunnerManifestState {
    val recovered = state.copy(
      manifest = state.manifest.copy(
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId, "start"),
        subtasks = state.manifest.subtasks.map { subtask ->
          if (subtask.id == subtaskId && subtask.workflowId == workflowId) {
            subtask.copy(
              status = "pending",
              branch = null,
              commitSha = null,
              workflowId = null,
              blockedReason = null,
              lastResumableStep = null,
            )
          } else {
            subtask
          }
        },
      ),
    )
    return save(recovered, dbPathOverride)
  }

  var plannedSubtaskIds: MutableSet<Int> = mutableSetOf()
  var sharedPreplanPrepared: Boolean = true
  var sharedPreplanPayloadSha256ForTest: String? = "c".repeat(64)
  var scopedReplanCount: Int = 0
    private set
  var lastIncludeSharedPreplan: Boolean? = null
    private set
  var forceSharedDigestMismatchOnReplan: Boolean = false

  override fun planningStatus(
    parentWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int?,
    blockedReason: String?,
    dbPathOverride: String?,
  ): skillbill.goalrunner.model.GoalPlanningStatusSnapshot {
    val plannedIds = plannedSubtaskIds.sorted()
    val firstMissing = orderedSubtaskIds.firstOrNull { it !in plannedIds }
    val state = when {
      blockedReason != null -> skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED
      !sharedPreplanPrepared -> skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
      firstMissing == null -> skillbill.goalrunner.model.GoalPlanningStatusState.PREPARED
      plannedIds.isEmpty() -> skillbill.goalrunner.model.GoalPlanningStatusState.PREPLANNED
      else -> skillbill.goalrunner.model.GoalPlanningStatusState.PARTIALLY_PLANNED
    }
    val reason = when (state) {
      skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED ->
        skillbill.goalrunner.model.GoalPlanningStatusReasons.NOT_STARTED
      skillbill.goalrunner.model.GoalPlanningStatusState.PREPLANNED ->
        skillbill.goalrunner.model.GoalPlanningStatusReasons.preplannedResume(requireNotNull(firstMissing))
      skillbill.goalrunner.model.GoalPlanningStatusState.PARTIALLY_PLANNED ->
        skillbill.goalrunner.model.GoalPlanningStatusReasons.partiallyPlannedResume(requireNotNull(firstMissing))
      skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED -> blockedReason
      skillbill.goalrunner.model.GoalPlanningStatusState.PREPARED -> null
    }
    return skillbill.goalrunner.model.GoalPlanningStatusSnapshot(
      state,
      sharedPreplanPrepared,
      plannedIds.size,
      orderedSubtaskIds.size,
      blockedSubtaskId ?: firstMissing,
      reason,
    )
  }

  override fun sharedPreplanPayloadSha256(parentWorkflowId: String, dbPathOverride: String?): String? =
    sharedPreplanPayloadSha256ForTest.takeIf { sharedPreplanPrepared }

  override fun saveScopedReplan(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    dbPathOverride: String?,
    options: skillbill.ports.goalrunner.model.GoalRunnerScopedReplanOptions,
  ): skillbill.ports.goalrunner.model.GoalRunnerScopedReplanWriteResult {
    scopedReplanCount += 1
    lastIncludeSharedPreplan = options.includeSharedPreplan
    val before = plannedSubtaskIds.sorted()
    val sharedBefore = sharedPreplanPrepared
    val cascadedIds: List<Int>
    val deleted: Int
    if (options.includeSharedPreplan) {
      cascadedIds = cascadeEligiblePlanSubtaskIds(
        plannedIds = before.filter { it != subtaskId },
        subtasks = state.manifest.subtasks,
      )
      val retained = before.filter { it != subtaskId && it !in cascadedIds }
      if (options.expectedSharedPayloadSha256 != null) {
        if (forceSharedDigestMismatchOnReplan ||
          options.expectedSharedPayloadSha256 != sharedPreplanPayloadSha256ForTest
        ) {
          throw skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError(
            state.parentWorkflowId,
            0,
            "shared preplan changed after it was observed for discard",
          )
        }
        sharedPreplanPrepared = false
        if (retained.isEmpty()) {
          plannedSubtaskIds.clear()
          sharedPreplanPayloadSha256ForTest = null
          deleted = if (subtaskId in before) 1 else 0
        } else {
          cascadedIds.forEach { plannedSubtaskIds.remove(it) }
          deleted = if (plannedSubtaskIds.remove(subtaskId)) 1 else 0
        }
      } else {
        cascadedIds.forEach { plannedSubtaskIds.remove(it) }
        deleted = if (plannedSubtaskIds.remove(subtaskId)) 1 else 0
      }
    } else {
      cascadedIds = emptyList()
      deleted = if (plannedSubtaskIds.remove(subtaskId)) 1 else 0
    }
    val saved = save(state, dbPathOverride)
    return skillbill.ports.goalrunner.model.GoalRunnerScopedReplanWriteResult(
      state = saved,
      deletedPlanCount = deleted,
      plannedSubtaskIdsBefore = before,
      plannedSubtaskIdsAfter = plannedSubtaskIds.sorted(),
      sharedPreplanPrepared = sharedPreplanPrepared,
      sharedPreplanPreparedBefore = sharedBefore,
      discardedSharedPreplan = sharedBefore && !sharedPreplanPrepared,
      cascadedPlanSubtaskIds = cascadedIds,
    )
  }

  override fun saveNewChildWorkflow(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    dbPathOverride: String?,
  ): GoalRunnerManifestState {
    newChildWorkflowSetups += setup
    return save(state, dbPathOverride)
  }

  override fun outOfBandAcceptances(
    parentWorkflowId: String,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerOutOfBandAcceptance> = acceptances

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
    dbPathOverride: String?,
  ): GoalRunnerOutOfBandAcceptance {
    acceptedParentWorkflowIds += parentWorkflowId
    acceptances = acceptances + (acceptance.subtaskId to acceptance)
    return acceptance
  }

  fun mutate(block: (DecompositionManifest) -> DecompositionManifest) {
    manifest = block(manifest)
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
  fun `first-run subtask with pre-assigned id records started and heartbeat through a long quiet phase`() {
    // SKILL-87 (AC3/AC5): the goal driver pre-assigns and persists the runtime workflow id BEFORE
    // launch, so resolveWorkflowId is non-blank from the first tick. A long, quiet first phase (no
    // terminal event yet) must still durably record operation_started AND operation_heartbeat.
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1).withWorkflowId(subtaskId = 1, workflowId = "wftr-pre-assigned"),
    )
    val outcomes = RecordingOutcomeStore()
    val reconciler = GoalRunnerLaunchReconciler(
      manifestStore = store,
      subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
      outcomeStore = outcomes,
    )

    val launchRequest = reconciler.subtaskLaunchRequest("SKILL-56", subtaskId = 1, request = wiringRunRequest())
    val emitter = launchRequest.skillRunRequest.progressEmitter
    emitter.emit(supervisorEmission(GoalProgressEventKind.OPERATION_STARTED, processAlive = true))
    emitter.emit(supervisorEmission(GoalProgressEventKind.OPERATION_HEARTBEAT, processAlive = true))

    val recorded = outcomes.progressEventRecords
    assertEquals(2, recorded.size, "pre-assigned id must let the quiet first phase record liveness")
    assertTrue(recorded.all { it.event.workflowId == "wftr-pre-assigned" })
    assertEquals(
      listOf(GoalProgressEventKind.OPERATION_STARTED, GoalProgressEventKind.OPERATION_HEARTBEAT),
      recorded.map { it.event.eventKind },
    )
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
  private val reviewStates: MutableMap<String, GoalSubtaskReviewState> = mutableMapOf()
  val unemittedReviewPasses: MutableMap<String, List<GoalSubtaskReviewPassResult>> = mutableMapOf()
  val acknowledgedReviewPasses: MutableList<Pair<String, Int>> = mutableListOf()
  val progresses: MutableMap<String, GoalRunnerWorkflowProgress> = mutableMapOf()
  val blockedWorkflows: MutableList<BlockedWorkflow> = mutableListOf()
  val reopenBlockedPhaseCalls: MutableList<ReopenBlockedPhaseCall> = mutableListOf()
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
  var lastAuthoritativeOutcomeRequest: Pair<String, String?>? = null

  operator fun set(workflowId: String, outcome: GoalRunnerStoredOutcome) {
    outcomes[workflowId] = outcome
    reviewStates.putIfAbsent(
      workflowId,
      GoalSubtaskReviewState.initial("0".repeat(40), emptyList(), CodeReviewExecutionMode.AUTO),
    )
  }

  fun seedReviewState(workflowId: String) {
    reviewStates[workflowId] = GoalSubtaskReviewState.initial(
      reviewBaseSha = "0".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.AUTO,
    )
  }

  override fun goalSubtaskReviewState(workflowId: String, dbPathOverride: String?): GoalSubtaskReviewState? =
    reviewStates[workflowId]

  override fun unemittedGoalReviewPasses(
    workflowId: String,
    dbPathOverride: String?,
  ): List<GoalSubtaskReviewPassResult> = unemittedReviewPasses[workflowId].orEmpty()

  override fun acknowledgeGoalReviewPass(workflowId: String, passNumber: Int, dbPathOverride: String?): Boolean {
    val remaining = unemittedReviewPasses[workflowId].orEmpty()
    if (remaining.firstOrNull()?.passNumber != passNumber) return false
    acknowledgedReviewPasses += workflowId to passNumber
    unemittedReviewPasses[workflowId] = remaining.drop(1)
    return true
  }

  override fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String>,
    gate: GoalRunnerReconcileGate,
    repoRoot: Path?,
    dbPathOverride: String?,
  ): Map<Int, GoalRunnerStoredOutcome> {
    lastReconcileRequest = ReconcileRequest(issueKey, activeWorkflowIds, gate, repoRoot, dbPathOverride)
    return authoritativeOutcomesBySubtask.toMap()
  }

  override fun authoritativeOutcomes(issueKey: String, dbPathOverride: String?): Map<Int, GoalRunnerStoredOutcome> {
    lastAuthoritativeOutcomeRequest = issueKey to dbPathOverride
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

  override fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    dbPathOverride: String?,
  ): Boolean {
    reopenBlockedPhaseCalls += ReopenBlockedPhaseCall(workflowId, preferredPhaseId, reason)
    return true
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

internal data class ReopenBlockedPhaseCall(
  val workflowId: String,
  val preferredPhaseId: String,
  val reason: String,
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
  val gate: GoalRunnerReconcileGate,
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
    val launch = {
      requests += request
      result(request)
    }
    return request.skillRunRequest.spawnAuthorization?.withAuthorization(launch) ?: launch()
  }
}

internal class RecordingPullRequestPort : GoalPullRequestPort {
  val requests: MutableList<GoalPullRequestRequest> = mutableListOf()
  val openCount: Int get() = requests.size

  override fun open(request: GoalPullRequestRequest): GoalPullRequestResult {
    requests += request
    return GoalPullRequestResult.Opened("https://github.com/canonical/skill-bill/pull/56")
  }
}

private class FixedBranchGitOperations(
  private val branch: String,
) : WorkflowGitOperations, GoalSubtaskReviewGitOperationsProvider {
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

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations = readyGoalReviewOperations()
}

internal class AcceptGitOperations(
  private val resolvable: Map<String, String> = mapOf(
    "abc1234" to "abc1234abc1234abc1234abc1234abc1234abcd",
    "abc1234abc1234abc1234abc1234abc1234abcd" to "abc1234abc1234abc1234abc1234abc1234abcd",
  ),
) : WorkflowGitOperations by StatusDiffGitOperations {
  override fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult =
    resolvable[revision.trim()]?.let { WorkflowGitOperationResult(status = "ok", value = it) }
      ?: WorkflowGitOperationResult(
        status = "error",
        error = "Revision '$revision' does not name a commit in this repository.",
      )
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
  private val baselineError: String? = null,
) : WorkflowGitOperations, GoalSubtaskReviewGitOperationsProvider {
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

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations =
    readyGoalReviewOperations(baselineError)
}

private fun readyGoalReviewOperations(baselineError: String? = null): GoalSubtaskReviewGitOperations =
  object : GoalSubtaskReviewGitOperations {
    override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult =
      baselineError?.let { GoalSubtaskReviewBaselineResult(status = "error", error = it) }
        ?: GoalSubtaskReviewBaselineResult(
          status = "ok",
          baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        )

    override fun buildInput(
      repoRoot: Path,
      baseline: GoalSubtaskReviewBaseline,
      expectedBranch: String,
    ): GoalSubtaskReviewInputResult = GoalSubtaskReviewInputResult(
      status = "ok",
      input = GoalSubtaskReviewInput(
        reviewBaseSha = baseline.reviewBaseSha,
        currentHeadSha = "0".repeat(40),
        trackedDelta = "",
        ownedUntrackedPatches = "",
      ),
    )

    override fun recoverBaseline(
      repoRoot: Path,
      request: skillbill.ports.workflow.model.GoalSubtaskReviewBaselineRecoveryRequest,
      expectedBranch: String,
    ): GoalSubtaskReviewBaselineResult = GoalSubtaskReviewBaselineResult(
      status = "error",
      error = "Goal review baseline recovery is not used by this goal runner fixture.",
    )
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

internal fun DecompositionManifest.withSubtaskAgent(
  subtaskId: Int,
  finalizingAgentId: String,
  participatingAgentIds: List<String> = listOf(finalizingAgentId),
): DecompositionManifest = copy(
  subtasks = subtasks.map { subtask ->
    if (subtask.id == subtaskId) {
      subtask.copy(finalizingAgentId = finalizingAgentId, participatingAgentIds = participatingAgentIds)
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
  currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
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

// SKILL-103 (AC1, AC2): goal status attribution — active_agent sourced from persisted run state,
// never from the status caller's resolution chain. Kept in its own class so the broad
// [GoalRunnerTest] stays under the detekt LargeClass threshold.
class GoalRunnerStatusAttributionTest {
  @Test
  fun `status projection reports counts current step and active agent sourced from persisted run state`() {
    // The caller passes invokedAgentId=claude and configuredAgentOverrideId=codex, but the current
    // subtask's recorded finalizing agent is cursor — status must report cursor and ignore both.
    val blockedWithAgent = manifest(subtaskCount = 3)
      .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1")
      .withBlockedSubtask(2, workflowId = "wfl-2", reason = "needs review")
      .withSubtaskAgent(2, finalizingAgentId = "cursor")
    val store = InMemoryGoalManifestStore(manifest = blockedWithAgent)
    val outcomes = RecordingOutcomeStore()
    outcomes.progresses["wfl-2"] = GoalRunnerWorkflowProgress(
      workflowId = "wfl-2",
      workflowStatus = "running",
      currentStepId = "implement",
      progressToken = "child-progress-token",
      latestLivenessSignal = "durable_progress step=implement attempt=1",
    )
    val service = GoalRunnerStatusService(store, outcomes, goalTestPhaseRecorder())

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "claude",
        configuredAgentOverrideId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals(1, status.completeCount)
    // Subtask 2 is durably blocked in the manifest but its child workflow is running, so it counts as
    // in-progress: the manifest projection is only rewritten at reconciliation points and would otherwise
    // report a relaunched subtask as blocked for the whole run.
    assertEquals(2, status.pendingCount)
    assertEquals(0, status.blockedCount)
    assertEquals(2, status.currentSubtaskId)
    assertEquals("implement", status.currentStep)
    assertEquals("cursor", status.activeAgent)
    assertEquals("durable_progress step=implement attempt=1", status.latestLivenessSignal)
  }

  @Test
  fun `status projection omits active agent when no agent is persisted for the current subtask`() {
    // When neither the phase ledger nor the subtask outcome carries an agent, the field is omitted
    // (null) rather than invented from the caller's resolution chain.
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1).withBlockedSubtask(1, workflowId = "wfl-1", reason = "needs review"),
    )
    val service = GoalRunnerStatusService(
      store,
      RecordingOutcomeStore(),
      goalTestPhaseRecorder(),
    )

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
        configuredAgentOverrideId = "claude",
      ),
    )

    requireNotNull(status)
    assertEquals(null, status.activeAgent)
  }

  @Test
  fun `status projection reports the persisted phase-ledger agent for a runtime child regardless of caller`() {
    // AC2 regression: a goal run persisted with cursor phase records, queried by a status call whose
    // own resolution chain would yield codex, reports active_agent: cursor. Source 1 is the current
    // subtask's active workflow agent from the persisted phase ledger.
    val harness = GoalStatusPhaseLedgerHarness()
    val workflowId = "wfl-cursor-child"
    harness.openRuntimeWorkflow(workflowId)
    harness.recordCompletedPhase(workflowId, phaseId = "implement", resolvedAgentId = "cursor")
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1).withBlockedSubtask(1, workflowId = workflowId, reason = "needs review"),
    )
    val service = GoalRunnerStatusService(store, RecordingOutcomeStore(), harness.recorder)

    val status = service.status(
      GoalRunnerStatusRequest(
        issueKey = "SKILL-56",
        invokedAgentId = "codex",
        configuredAgentOverrideId = "codex",
      ),
    )

    requireNotNull(status)
    assertEquals("cursor", status.activeAgent)
  }
}

// SKILL-103: GoalRunnerStatusService now resolves the active agent from persisted phase state via
// FeatureTaskRuntimePhaseRecorder. Goal-runner unit tests don't seed child phase records, so this
// recorder runs over an empty repository (every read returns null) and attribution falls through to
// the subtask's recorded finalizing/participating agent — letting status attribution tests assert
// source 2 without a database.
internal const val FAKE_PAUSED_AT = "2026-08-02T10:00:00Z"

internal fun goalTestPhaseRecorder(): FeatureTaskRuntimePhaseRecorder = FeatureTaskRuntimePhaseRecorder(
  GoalTestEmptyDatabase,
  GoalTestNoopSnapshotValidator,
  AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
  AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
)

// Seedable in-memory harness for the AC2 phase-ledger regression: opens a real runtime-mode workflow
// row and records a finalized phase so GoalRunnerStatusService.resolveActiveAgent reads the agent
// from the durable phase ledger (source 1) rather than the subtask outcome.
private class GoalStatusPhaseLedgerHarness {
  private val repository = GoalStatusSeedableWorkflowStateRepository()
  private val database = GoalStatusSeedableDatabase(repository)
  val recorder: FeatureTaskRuntimePhaseRecorder =
    FeatureTaskRuntimePhaseRecorder(
      database,
      GoalTestNoopSnapshotValidator,
      AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
      AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
    )
  var failOwnershipReads: Boolean
    get() = repository.failOwnershipReads
    set(value) {
      repository.failOwnershipReads = value
    }
  val ownershipWriteCount: Int get() = repository.ownershipWriteCount

  fun openRuntimeWorkflow(workflowId: String) {
    recorder.ensureWorkflowOpen(workflowId, sessionId = "goal-status-test")
  }

  fun seedOwnership(workflowId: String, expiresAt: String) {
    repository.seedOwnership(workflowId, expiresAt)
  }

  fun recordCompletedPhase(workflowId: String, phaseId: String, resolvedAgentId: String) {
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = phaseId,
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = resolvedAgentId,
        finished = true,
        outputArtifact = """{"contract_version":"0.1"}""",
      ),
    )
  }
}

private class GoalStatusSeedableDatabase(
  private val repository: GoalStatusSeedableWorkflowStateRepository,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-status-phase-ledger.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@GoalStatusSeedableDatabase.dbPath
    override val reviews: ReviewRepository get() = error("unused by goal status tests")
    override val learnings: LearningRepository get() = error("unused by goal status tests")
    override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by goal status tests")
    override val telemetryReconciliation: TelemetryReconciliationRepository get() = error("unused by goal status tests")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by goal status tests")
    override val workflowStates: WorkflowStateRepository = repository
    override val workList = skillbill.ports.persistence.EmptyWorkListRepository
    override val goalPlanningPreparations = skillbill.ports.persistence.EmptyGoalPlanningPreparationRepository
  }
}

private class GoalStatusSeedableWorkflowStateRepository : WorkflowStateRepository {
  override fun saveFeatureTaskExecutionIdentity(
    identity: skillbill.ports.persistence.model.FeatureTaskExecutionIdentity,
  ) = Unit
  override fun findStandaloneFeatureTaskCandidates(normalizedIssueKey: String, repositoryIdentity: String) =
    emptyList<skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate>()

  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()
  private val ownershipRows =
    linkedMapOf<String, skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership>()
  var failOwnershipReads = false
  var ownershipWriteCount = 0

  fun seedOwnership(workflowId: String, expiresAt: String) {
    ownershipRows[workflowId] = skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId,
      ownerToken = "owner-token-123456",
      generation = 1,
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 1234,
      processBirthToken = "birth-1234",
      leaseState = skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      phaseId = "implement",
      phaseAttempt = 1,
      heartbeatAt = "2026-07-27T11:59:30Z",
      expiresAt = expiresAt,
    )
  }

  override fun getFeatureTaskRuntimeWorkerOwnership(
    workflowId: String,
  ): skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership? {
    if (failOwnershipReads) error("lease read failed")
    return ownershipRows[workflowId]
  }

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) = Unit
  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit
  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = null
  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null
  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()
  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()
  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = null
  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = null
  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null
  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null
}

private object GoalTestNoopSnapshotValidator : skillbill.workflow.WorkflowSnapshotValidator {
  override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
}

private object GoalTestEmptyDatabase : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-test-metrics.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@GoalTestEmptyDatabase.dbPath
    override val reviews: ReviewRepository get() = error("unused by goal status tests")
    override val learnings: LearningRepository get() = error("unused by goal status tests")
    override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by goal status tests")
    override val telemetryReconciliation: TelemetryReconciliationRepository get() = error("unused by goal status tests")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by goal status tests")
    override val workflowStates: WorkflowStateRepository = GoalTestEmptyWorkflowStateRepository
    override val workList = skillbill.ports.persistence.EmptyWorkListRepository
    override val goalPlanningPreparations = skillbill.ports.persistence.EmptyGoalPlanningPreparationRepository
  }
}

private class GoalTestPlanningDatabase : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/goal-test-planning.db")
  val deletedParentGoalIds = mutableListOf<String>()
  val deletedChildWorkflowParentIds = mutableListOf<String>()
  val transactionDbOverrides = mutableListOf<String?>()
  private val planningRepository = object : skillbill.ports.persistence.GoalPlanningPreparationRepository by
  skillbill.ports.persistence.EmptyGoalPlanningPreparationRepository {
    override fun deleteByGoal(parentGoalWorkflowId: String): Int {
      deletedParentGoalIds += parentGoalWorkflowId
      return 1
    }
  }

  override fun resolveDbPath(dbOverride: String?): Path = dbPath
  override fun databaseExists(dbOverride: String?): Boolean = true
  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())
  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = transaction(dbOverride, block)

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T {
    transactionDbOverrides += dbOverride
    return block(unitOfWork())
  }

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@GoalTestPlanningDatabase.dbPath
    override val reviews: ReviewRepository get() = error("unused by hard reset test")
    override val learnings: LearningRepository get() = error("unused by hard reset test")
    override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused by hard reset test")
    override val telemetryReconciliation: TelemetryReconciliationRepository get() = error("unused by hard reset test")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused by hard reset test")
    override val workflowStates: WorkflowStateRepository = object : WorkflowStateRepository by
    GoalTestEmptyWorkflowStateRepository {
      override fun deleteGoalChildWorkflowsByParent(parentWorkflowId: String): Int {
        deletedChildWorkflowParentIds += parentWorkflowId
        return 1
      }
    }
    override val workList = skillbill.ports.persistence.EmptyWorkListRepository
    override val goalPlanningPreparations = planningRepository
  }
}

private object GoalTestEmptyWorkflowStateRepository : WorkflowStateRepository {
  override fun saveFeatureTaskExecutionIdentity(
    identity: skillbill.ports.persistence.model.FeatureTaskExecutionIdentity,
  ) = Unit
  override fun findStandaloneFeatureTaskCandidates(normalizedIssueKey: String, repositoryIdentity: String) =
    emptyList<skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate>()
  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) = Unit
  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = null
  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()
  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = null
  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null
  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit
  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null
  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()
  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = null
  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null
  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) = Unit
  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = null
  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()
  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? = null
}

// Regression for the validate crashloop: a persistently-failing validate phase must stop after a bounded
// number of goal-level retries instead of looping forever. Kept in its own class so the broad
// [GoalRunnerTest] stays under the detekt LargeClass threshold.
class GoalRunnerValidationQualityRetryTest {
  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )

  @Test
  fun `validation quality gate stops after bounded retries instead of looping forever`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.BLOCKED,
        workflowId = "wfl-$subtaskId",
        blockedReason = "./gradlew check keeps failing during validate.",
        lastResumableStep = "validate",
        suppressPr = true,
      )
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(report)
    assertEquals(GoalRunnerStopReason.BLOCKED, stopped.stop.reason)
    assertEquals("validate", stopped.stop.lastResumableStep)
    assertEquals("blocked", store.manifest.subtasks.single().status)
    val validateResumes = launcher.requests.count {
      it.skillRunRequest.goalContinuation?.lastResumableStep == "validate"
    }
    assertEquals(4, launcher.requests.size, "validate must bound to 1 initial launch + 3 retries, not loop forever")
    assertEquals(3, validateResumes, "only the bounded retry budget may re-resume at validate")
  }
}

class GoalRunnerUnaddressedFindingsSummaryTest {
  @Test
  fun `a goal whose ledger reports absent still completes with the compact severity breakdown`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
      outcomeStore = RecordingOutcomeStore(),
      pullRequestPort = RecordingPullRequestPort(),
      unaddressedFindingsLedgerService = UnaddressedFindingsLedgerService(
        RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), knownIssue = false),
      ),
    )

    val request = GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
      dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
    )
    val completed = assertIs<GoalRunnerRunReport.Completed>(runner.run(request))

    assertEquals("opened", completed.pullRequestStatus)
    assertEquals(0, completed.unaddressedFindingCount)
    assertEquals(
      mapOf("blocker" to 0, "major" to 0, "minor" to 0, "nit" to 0),
      completed.unaddressedSeverityBreakdown,
    )
  }

  @Test
  fun `a malformed ledger row does not abort finalization before the pull request is opened`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withCompletedSubtask(1, workflowId = "wfl-1", commitSha = "sha-1"),
    )
    val sessionFactory = RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository())
    sessionFactory.ledgerRows += UnaddressedFinding(
      issueKey = "SKILL-56",
      subtaskId = 1,
      workflowId = "wfl-1",
      reviewPassNumber = 1,
      findingOrdinal = 1,
      severity = "major",
      issueCategory = "not_a_governed_category",
      location = "src/Feature.kt:42",
      summary = "Poison row persisted by an older writer",
    )
    val pullRequestPort = RecordingPullRequestPort()
    val runner = GoalRunner(
      manifestStore = store,
      subtaskLauncher = RecordingSubtaskLauncher { launchFacts() },
      outcomeStore = RecordingOutcomeStore(),
      pullRequestPort = pullRequestPort,
      unaddressedFindingsLedgerService = UnaddressedFindingsLedgerService(sessionFactory),
    )

    val request = GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = Path.of("/tmp/skillbill-goal-runner"),
      invokedAgentId = "claude",
      dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
    )
    val completed = assertIs<GoalRunnerRunReport.Completed>(runner.run(request))

    assertEquals("opened", completed.pullRequestStatus)
    assertEquals(
      null,
      completed.unaddressedFindingCount,
      "an unreadable ledger must not report an affirmative zero",
    )
    assertEquals(emptyMap(), completed.unaddressedSeverityBreakdown)
  }
}

// Operator blocked-subtask resume: kept outside [GoalRunnerTest] so that suite stays under detekt LargeClass.
class GoalRunnerOperatorBlockedResumeTest {
  @Test
  fun `operator resume of a blocked subtask reopens the child phase before launch`() {
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1)
        .withBlockedSubtask(1, workflowId = "wfl-1", reason = "implement needs user action"),
    )
    val outcomes = RecordingOutcomeStore()
    outcomes.seedReviewState("wfl-1")
    val launcher = RecordingSubtaskLauncher {
      outcomes["wfl-1"] = completeOutcome(1)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val report = runner.run(runRequest())

    assertIs<GoalRunnerRunReport.Completed>(report)
    assertEquals(listOf(1), launcher.requests.map { it.skillRunRequest.subtaskId })
    assertEquals(listOf("wfl-1"), outcomes.reopenBlockedPhaseCalls.map { it.workflowId })
    assertEquals(listOf("validate"), outcomes.reopenBlockedPhaseCalls.map { it.preferredPhaseId })
    assertTrue(
      outcomes.reopenBlockedPhaseCalls.single().reason.contains("Operator resumed the goal"),
      outcomes.reopenBlockedPhaseCalls.single().reason,
    )
  }

  private fun runRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}
