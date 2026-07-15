package skillbill.application

import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.featuretask.FeatureSpecPreparationRuntime
import skillbill.application.featuretask.FeatureSpecPreparationWriter
import skillbill.application.featuretask.FeatureTaskRuntimeAgentResolver
import skillbill.application.featuretask.FeatureTaskRuntimeBranchSetupRunner
import skillbill.application.featuretask.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeDecompositionPlanner
import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.featuretask.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhaseGates
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimePlanningStopper
import skillbill.application.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeSpecGate
import skillbill.application.featuretask.FeatureTaskRuntimeSpecStatusProjector
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.SpecSourceResolver
import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.workflow.repoRoot
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.workflow.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.RuntimePhaseFileManifestGitOperationsProvider
import skillbill.ports.workflow.SpecScratchStore
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class FeatureTaskRuntimeRunnerTest {
  @Test
  fun `runs phases deterministically through terminal pr phase order`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(ALL_PHASES, completed.completedPhaseIds)
    assertEquals(
      ALL_PHASES,
      harness.launchedPhaseOrder(),
    )
    assertEquals(
      ALL_PHASES,
      harness.launchOrder(),
    )
  }

  @Test
  fun `single-spec completion reconciles the spec Agent line with the ledger-derived finalizing agent`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val write = harness.specStatusWriter.writes.single()
    assertEquals(SPEC_REFERENCE, write.first.toString())
    assertEquals(
      phaseAgent("pr"),
      write.second,
      "the Agent line must carry the ledger-derived finalizing agent (the agent that ran the terminal pr phase)",
    )
  }

  @Test
  fun `goal-continuation completion does not reconcile a single-spec Agent line`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-no-spec-line")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = "measured-head-sha" }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))

    harness.runner.run(harness.request())

    assertTrue(
      harness.specStatusWriter.writes.isEmpty(),
      "a goal-continuation subtask run never stamps the single-spec Agent line",
    )
  }

  @Test
  fun `runtime run against a prose-mode workflow blocks with an actionable reason and launches nothing`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedProseModeWorkflow()

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "was created in 'prose' mode")
    assertContains(blocked.blockedReason, "reset the subtask")
    assertTrue(harness.launcher.requests.isEmpty())
  }

  @Test
  fun `runtime issue-key reopen conflict fails before run events or agents start`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID, issueKey = ISSUE_KEY)

    assertFailsWith<WorkflowIssueKeyConflictError> {
      harness.runner.run(harness.request().copy(issueKey = "SKILL-118"))
    }

    assertTrue(harness.events.isEmpty())
    assertTrue(harness.launcher.requests.isEmpty())
  }

  @Test
  fun `blocked phase output stops the run and does not advance to pr`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "commit_push") COMMIT_PUSH_BLOCKED_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("commit_push", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "Validation failed before commit.")
    assertTrue(harness.launchedPhaseOrder().none { it == "pr" })
    assertTrue(
      harness.events.any { event ->
        event is FeatureTaskRuntimeRunEvent.PhaseBlocked && event.phaseId == "commit_push"
      },
    )
  }

  @Test
  fun `non-retryable review policy conflict re-blocks on resume without relaunch`() {
    val reviewBlocked = """
      {
        "contract_version":"0.1",
        "phase_id":"review",
        "status":"blocked",
        "failure_disposition":"non_retryable_policy_conflict",
        "summary":"Review policy cannot run for this scope.",
        "produced_outputs":{"findings":[],"blocking_reasons":["Inline policy conflict."]}
      }
    """.trimIndent()
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "review") reviewBlocked else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    val launchCount = harness.launcher.requests.size
    val resumed = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("review", resumed.lastIncompletePhase)
    assertEquals(launchCount, harness.launcher.requests.size)
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("non_retryable_policy_conflict", record.failureDisposition?.wireValue)
  }

  @Test
  fun `retryable review failure uses the bounded in-phase retry`() {
    var reviewLaunches = 0
    val retryableFailure = """
      {
        "contract_version":"0.1",
        "phase_id":"review",
        "status":"failed",
        "failure_disposition":"retryable",
        "summary":"Transient review preparation failed.",
        "produced_outputs":{"blocking_reasons":["Temporary input unavailable."]}
      }
    """.trimIndent()
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "review") reviewLaunches += 1
        facts(if (phaseId == "review" && reviewLaunches == 1) retryableFailure else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(2, reviewLaunches)
  }

  @Test
  fun `phase blocks and records manifests when it introduces another issue spec`() {
    val git = RecordingWorkflowGitOperations()
    git.worktreeStatusSequence.addAll(
      listOf("", "?? .feature-specs/SKILL-124-sqldelight-runtime-persistence/spec.md"),
    )
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("preplan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "SKILL-124")
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals(
      listOf(".feature-specs/SKILL-124-sqldelight-runtime-persistence/spec.md"),
      record.fileManifestIntroduced,
    )
    assertEquals("non_retryable_policy_conflict", record.failureDisposition?.wireValue)
  }

  @Test
  fun `phase blocks when it commits another issue spec`() {
    val git = RecordingWorkflowGitOperations()
    git.runtimePhaseHeadCommitSequence.addAll(listOf("before", "after"))
    git.changedPathsBetweenCommitsValue =
      ".feature-specs/SKILL-124-sqldelight-runtime-persistence/spec.md"
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("preplan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "SKILL-124")
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals(
      listOf(".feature-specs/SKILL-124-sqldelight-runtime-persistence/spec.md"),
      record.fileManifestIntroduced,
    )
  }

  @Test
  fun `validation phase output block keeps repairing instead of stopping task`() {
    var validateLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "validate") {
          validateLaunches += 1
        }
        facts(if (phaseId == "validate" && validateLaunches < 3) VALIDATE_BLOCKED_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(ALL_PHASES, completed.completedPhaseIds)
    assertEquals(3, harness.launchedPhaseOrder().count { it == "validate" })
    assertTrue(
      harness.events.none { event -> event is FeatureTaskRuntimeRunEvent.PhaseBlocked && event.phaseId == "validate" },
    )
    val validateRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"])
    assertEquals("completed", validateRecord.status)
    assertEquals(3, validateRecord.attemptCount)
  }

  @Test
  fun `each phase briefing includes unconditional run-invariants latest upstream and derived diff for review`() {
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = SPEC_REFERENCE,
      featureSize = FeatureTaskRuntimeFeatureSize.SMALL,
      acceptanceCriteria = listOf("AC-1", "AC-2"),
      mandatesAndOverrides = listOf("mandate-X"),
    )
    val recorded = listOf(
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("preplan", 1, PREPLAN_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("plan", 1, PLAN_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("implement", 1, IMPLEMENT_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("review", 1, VALID_OUTPUT),
    )

    val briefings = ALL_PHASES.associateWith { phaseId ->
      val declaration =
        skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(
          phaseId,
          invariants.featureSize,
        )
      val handoff = skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract.assembleHandoff(
        declaration = declaration,
        runInvariants = invariants,
        recordedOutputs = recorded,
      )
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    }

    briefings.forEach { (phaseId, briefing) ->
      assertEquals(SPEC_REFERENCE, briefing.specReference, "spec reference for $phaseId")
      assertEquals("SMALL", briefing.featureSize, "feature size for $phaseId")
      assertEquals(listOf("AC-1", "AC-2"), briefing.acceptanceCriteria, "criteria for $phaseId")
      assertContains(briefing.briefingText, "feature_size: SMALL")
      assertContains(briefing.briefingText, SPEC_REFERENCE)
      assertContains(briefing.briefingText, "mandate-X")
    }
    assertTrue(briefings.getValue("plan").upstreamOutputsByPhaseId.containsKey("preplan"))
    assertEquals(PREPLAN_OUTPUT, briefings.getValue("plan").upstreamOutputsByPhaseId.getValue("preplan"))
    assertTrue(briefings.getValue("implement").upstreamOutputsByPhaseId.containsKey("plan"))
    assertEquals(PLAN_OUTPUT, briefings.getValue("implement").upstreamOutputsByPhaseId.getValue("plan"))
    assertEquals(listOf("current_unit_of_work"), briefings.getValue("review").derivedContextKeys)
    assertContains(briefings.getValue("review").briefingText, "current_unit_of_work")
    assertEquals(listOf("diff"), briefings.getValue("pr").derivedContextKeys)
    assertContains(briefings.getValue("pr").briefingText, "diff")
  }

  @Test
  fun `schema gate rejection on a non-fix-loop phase blocks without advancing`() {
    // write_history is non-fix-loop (and post-implement); a schema-invalid output blocks immediately.
    // (implement is no longer a fence: it is now a bounded fix loop under the idempotency contract.)
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("write_history")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("write_history", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "does not participate in a fix loop")
    assertEquals(
      listOf("preplan", "plan", "implement", "review", "audit", "validate"),
      blocked.completedPhaseIds,
    )
    assertEquals(
      listOf("preplan", "plan", "implement", "review", "audit", "validate", "write_history"),
      harness.launchedPhaseOrder(),
    )
  }

  @Test
  fun `review fix loop re-runs up to the cap then blocks loudly`() {
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("review")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "exhausted the bounded fix loop")
    val launchedPhases = harness.launchedPhaseOrder()
    assertEquals(1, launchedPhases.count { it == "plan" })
    assertEquals(1, launchedPhases.count { it == "implement" })
    assertEquals(
      FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      launchedPhases.count { it == "review" },
    )
    assertEquals(
      FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      harness.launchOrder().count { it == "review" },
    )
  }

  @Test
  fun `review fix loop recovers on a later iteration and advances`() {
    var reviewAttempts = 0
    val harness = runnerHarness(
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel == "review") {
            reviewAttempts += 1
            if (reviewAttempts < 2) {
              throw InvalidFeatureTaskRuntimePhaseOutputSchemaError("review", "still failing")
            }
          }
        }
      },
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(2, reviewAttempts)
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals(2, reviewRecord.attemptCount)
    assertEquals("completed", reviewRecord.status)
  }

  @Test
  fun `a schema-gate rejection threads the validator reason into the next attempt's prompt`() {
    // F-001: the behavioral change lives in the runner's fix loop, not the composer. A regression that
    // drops priorSchemaFailure (sets null on Retry, or never threads it through attemptOnce ->
    // launchAndCapture -> compose) leaves every retry a blind re-roll yet keeps the composer-isolated
    // tests green. Assert the SECOND review launch prompt carries the rejection directive and the prior
    // reason verbatim, and the FIRST attempt's prompt carries neither (forward launch unchanged).
    val reason = "Review gate: emit a findings array or a verdict, not prose"
    var reviewAttempts = 0
    val harness = runnerHarness(
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel == "review") {
            reviewAttempts += 1
            if (reviewAttempts < 2) {
              throw InvalidFeatureTaskRuntimePhaseOutputSchemaError("review", reason)
            }
          }
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val reviewPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "review" }
    assertEquals(2, reviewPrompts.size, "review launches once per attempt")
    assertTrue(
      !reviewPrompts[0].contains("REJECTED by the schema gate"),
      "the first attempt's prompt carries no correction directive",
    )
    assertContains(reviewPrompts[1], "Previous attempt was REJECTED by the schema gate")
    assertContains(reviewPrompts[1], reason)
  }

  @Test
  fun `per-phase agent resolution honors override then per-phase then invoked default`() {
    val harness = runnerHarness(
      agentAssignment = FeatureTaskRuntimeAgentAssignment(
        perPhaseAgentIds = mapOf("review" to "claude"),
      ),
    )

    harness.runner.run(harness.request())

    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertEquals(INVOKED_AGENT, records.getValue("plan").resolvedAgentId)
    assertEquals("claude", records.getValue("review").resolvedAgentId)
  }

  @Test
  fun `run-wide override wins over per-phase and invoked for every phase`() {
    val harness = runnerHarness(
      agentAssignment = FeatureTaskRuntimeAgentAssignment(
        perPhaseAgentIds = mapOf("review" to "claude"),
        override = "opencode",
      ),
    )

    harness.runner.run(harness.request())

    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    ALL_PHASES.forEach { phaseId ->
      assertEquals("opencode", records.getValue(phaseId).resolvedAgentId, "override must win for $phaseId")
    }
  }

  @Test
  fun `invoked agent is the always-present default and there is no hardcoded codex default`() {
    // The resolver order is per-phase entry -> invoking agent id; env is applied upstream at the
    // CLI boundary, not here, so an absent per-phase entry falls back to the invoked agent only.
    val resolved = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = "plan",
      assignment = FeatureTaskRuntimeAgentAssignment(),
      invokedAgentId = INVOKED_AGENT,
    )
    assertEquals(INVOKED_AGENT, resolved.invokedAgentId)
    assertEquals(INVOKED_AGENT, resolved.resolvedAgentId)

    val perPhase = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = "review",
      assignment = FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = mapOf("review" to "claude")),
      invokedAgentId = INVOKED_AGENT,
    )
    assertEquals("claude", perPhase.resolvedAgentId)
  }

  @Test
  fun `resume restarts from last incomplete phase and restores upstream outputs`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      listOf("review", "audit", "validate", "write_history", "commit_push", "pr"),
      harness.launchedPhaseOrder(),
    )
    assertEquals(listOf("review", "audit", "validate", "write_history", "commit_push", "pr"), harness.launchOrder())

    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    val reviewBriefing = requireNotNull(briefings["review"]) { "review briefing must be persisted" }
    assertEquals(IMPLEMENT_OUTPUT, reviewBriefing.upstreamOutputsByPhaseId["implement"])
    val auditBriefing = requireNotNull(briefings["audit"]) { "audit briefing must be persisted" }
    assertEquals(PLAN_OUTPUT, auditBriefing.upstreamOutputsByPhaseId["plan"])
    assertEquals(IMPLEMENT_OUTPUT, auditBriefing.upstreamOutputsByPhaseId["implement"])
    assertEquals(VALID_REVIEW_OUTPUT, auditBriefing.upstreamOutputsByPhaseId["review"])
    val historyBriefing = requireNotNull(briefings["write_history"]) { "history briefing must be persisted" }
    assertEquals(IMPLEMENT_OUTPUT, historyBriefing.upstreamOutputsByPhaseId["implement"])
    val commitBriefing = requireNotNull(briefings["commit_push"]) { "commit briefing must be persisted" }
    assertTrue(commitBriefing.upstreamOutputsByPhaseId.containsKey("write_history"))
    val prBriefing = requireNotNull(briefings["pr"]) { "pr briefing must be persisted" }
    assertTrue(prBriefing.upstreamOutputsByPhaseId.containsKey("commit_push"))
  }

  @Test
  fun `resume skips completed preplan and restores its digest into plan briefing`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      listOf("plan", "implement", "review", "audit", "validate", "write_history", "commit_push", "pr"),
      harness.launchedPhaseOrder(),
    )
    assertTrue(harness.launchedPhaseOrder().none { it == "preplan" })
    val planBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals(PREPLAN_OUTPUT, planBriefing.upstreamOutputsByPhaseId["preplan"])
    assertContains(planBriefing.briefingText, "### from: preplan")
    assertContains(planBriefing.briefingText, PREPLAN_OUTPUT)
  }

  @Test
  fun `resume re-runs legacy completed plan when preplan output is absent`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(ALL_PHASES, harness.launchedPhaseOrder())
    val planBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals(VALID_OUTPUT, planBriefing.upstreamOutputsByPhaseId["preplan"])
    assertContains(planBriefing.briefingText, "### from: preplan")
  }

  @Test
  fun `resume of a fix-loop phase that already burned the budget blocks without relaunching`() {
    // F-001: review persisted as running at attemptCount=3 (the cap) with no valid artifact must
    // re-block immediately on resume; the bounded budget is not reset by resume/crash.
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("review", "running", 3, phaseAgent("review"), outputArtifact = null)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "exhausted the bounded fix loop")
    // The agent must never be relaunched for the already-exhausted review phase.
    assertTrue(harness.launchedPhaseOrder().none { it == "review" })
    // A durable terminal blocked record is persisted (survives ledger pruning).
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("blocked", reviewRecord.status)
    assertTrue(requireNotNull(reviewRecord.blockedReason).isNotBlank())
  }

  @Test
  fun `resume of a fix-loop phase at attempt one resumes at iteration two`() {
    // F-001: review persisted as running at attemptCount=1 (no valid artifact) resumes at the
    // next attempt (iteration 2) rather than resetting to iteration 1.
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("review", "running", 1, phaseAgent("review"), outputArtifact = null)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    // Completed on the resumed attempt; attempt count is 2 (resumed from durable attempt 1).
    assertEquals(2, reviewRecord.attemptCount)
    assertEquals("completed", reviewRecord.status)
  }

  @Test
  fun `resume of a non-fix-loop phase with a durable blocked record re-blocks without relaunching`() {
    // F-002: a non-fix-loop phase persisted with a terminal blocked record (the durable marker
    // that survives ledger pruning) re-blocks on resume without launching the agent again.
    // write_history is non-fix-loop (implement is now a bounded fix-loop phase).
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), VALID_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_OUTPUT)
    harness.seedPhase("validate", "completed", 1, phaseAgent("validate"), VALID_OUTPUT)
    harness.seedBlockedPhase("write_history", attemptCount = 1, phaseAgent("write_history"), "history gate failed")

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("write_history", blocked.lastIncompletePhase)
    assertTrue(harness.launchedPhaseOrder().none { it == "write_history" })
  }

  @Test
  fun `resume of a fix-loop phase with a durable blocked record relaunches until the attempt cap`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), VALID_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_OUTPUT)
    harness.seedBlockedPhase("validate", attemptCount = 1, phaseAgent("validate"), "validation gate failed")

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(harness.launchedPhaseOrder().contains("validate"))
    val validateRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"])
    assertEquals("completed", validateRecord.status)
    assertEquals(2, validateRecord.attemptCount)
  }

  // --- Subtask 2: bounded cyclic phase executor (AC10) ---

  @Test
  fun `non-mutating declared cycle iterates to convergence on a satisfying verdict`() {
    // plan re-enters preplan once via the backward edge (verdict needs_fix), then converges
    // (verdict advance) and the run completes; the agent never bypasses the cap.
    var planLaunches = 0
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "plan") {
          planLaunches += 1
          facts(verdictPlanOutput(if (planLaunches == 1) "needs_fix" else "advance"))
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    // preplan launched twice (initial + one re-entry); plan launched twice.
    assertEquals(2, harness.launchedPhaseOrder().count { it == "preplan" })
    assertEquals(2, planLaunches)
  }

  @Test
  fun `cap exhaustion blocks loudly with loop id iteration count and unresolved verdict`() {
    // plan always reports needs_fix, so the backward edge fires up to its cap and then blocks.
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") verdictPlanOutput("needs_fix") else validJsonOutput(phaseId))
      },
    )

    val report = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "plan-fix")
    assertContains(blocked.blockedReason, "needs_fix")
    assertContains(blocked.blockedReason, PLAN_FIX_CAP.toString())
    // A durable terminal blocked record carries the loop context.
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertEquals("plan-fix", planRecord.loopId)
    assertEquals(PLAN_FIX_CAP, planRecord.edgeIteration)
  }

  @Test
  fun `per-edge counters increment and persist distinct from attempt count`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") verdictPlanOutput("needs_fix") else validJsonOutput(phaseId))
      },
    )

    harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    // The per-edge LOOP_EDGE ledger entries carry the runtime-minted edge iterations 1..cap, which
    // are distinct from the re-entered phase's attempt_count.
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    assertEquals((1..PLAN_FIX_CAP).toList(), loopEdges.mapNotNull { it.edgeIteration })
    assertTrue(loopEdges.all { it.loopId == "plan-fix" })
    // The re-entered preplan record persists the latest edge context, distinct from attempt_count.
    val preplanRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals("plan-fix", preplanRecord.loopId)
    assertEquals(PLAN_FIX_CAP, preplanRecord.edgeIteration)
    assertNotEquals(
      preplanRecord.attemptCount,
      preplanRecord.edgeIteration,
      "the per-edge iteration must be tracked distinctly from attempt_count",
    )
  }

  @Test
  fun `resume mid-cycle lands on the correct phase and edge iteration`() {
    // A prior run fired the edge to (cap - 1) and crashed with preplan re-entered. On resume the
    // edge fires ONCE more, reaching the cap, then blocks loudly. The seeded edge iteration is
    // load-bearing: had resume reset the watermark to 0, the edge would fire `cap` more times before
    // blocking instead of exactly one, so this proves resume continued from the durable iteration.
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") verdictPlanOutput("needs_fix") else validJsonOutput(phaseId))
      },
    )
    harness.seedReentryPhase("preplan", "running", 1, phaseAgent("preplan"), null, "plan-fix", PLAN_FIX_CAP - 1)

    val report = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "plan-fix")
    assertContains(blocked.blockedReason, PLAN_FIX_CAP.toString())
    assertContains(blocked.blockedReason, "needs_fix")
    // The watermark continued from the durable (cap - 1): the resume minted exactly ONE new
    // backward-edge iteration (the cap), then blocked. Had it reset to 0, the resume would mint
    // iterations 1..cap (two more fires) before blocking, so this pins the resume to the right
    // edge iteration, not just the right phase.
    val resumeEdgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals(listOf(PLAN_FIX_CAP), resumeEdgeIterations)
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertEquals(PLAN_FIX_CAP, planRecord.edgeIteration)
  }

  @Test
  fun `cap-exhausted edge re-blocks on resume without relaunching`() {
    // The re-entered preplan already burned the edge cap on a prior run; on resume the runtime
    // re-blocks before relaunching, mirroring the same-phase cap-burned-resume guard.
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(validJsonOutput(phaseId))
      },
    )
    harness.seedReentryPhase("preplan", "running", 2, phaseAgent("preplan"), null, "plan-fix", PLAN_FIX_CAP)

    val report = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("preplan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "plan-fix")
    assertContains(blocked.blockedReason, PLAN_FIX_CAP.toString())
    assertContains(blocked.blockedReason, "needs_fix")
    // The cap-exhausted re-entered phase must not relaunch its agent.
    assertTrue(harness.launchedPhaseOrder().none { it == "preplan" })
  }

  @Test
  fun `edge-free declaration behaves exactly as the current forward pipeline`() {
    // An override carrying only the production forward pipeline (no backward edges) drives the same
    // phase order as the default run.
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    val forwardOnly = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = ALL_PHASES,
    )

    val report = harness.runner.run(harness.request(forwardOnly))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(ALL_PHASES, harness.launchOrder())
  }

  @Test
  fun `blocked run persists a durable terminal blocked record alongside the ledger entry`() {
    // F-002: blocking persists a terminal blocked per-phase record so blocked-ness survives even
    // if the append-only ledger BLOCKED entry is later pruned by the retention cap.
    val harness = runnerHarness(validator = ThrowingValidator(failPhases = setOf("implement")))

    harness.runner.run(harness.request())

    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("blocked", implementRecord.status)
    assertTrue(requireNotNull(implementRecord.blockedReason).isNotBlank())
    assertNull(implementRecord.finishedAt)
  }

  @Test
  fun `a resumed running attempt re-mints started_at so duration measures only the current run`() {
    // F-007: on resume the running transition mints a fresh started_at (and keeps first_started_at)
    // so duration_millis times only the current run, not the resume gap.
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "running", 1, phaseAgent("plan"), outputArtifact = null)
    val seeded = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    val originalStartedAt = seeded.startedAt

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    // started_at re-minted on the resumed running attempt; first_started_at preserves the original.
    assertTrue(planRecord.startedAt >= originalStartedAt)
    assertEquals(originalStartedAt, planRecord.firstStartedAt)
  }

  @Test
  fun `run advances the coarse workflow row to the active phase and completes it on the final phase`() {
    // F-008: the coarse workflow row tracks the run instead of pinning at the initial step, so the
    // generic workflow get/list/latest agrees with FeatureTaskRuntimeStatusService.
    val harness = runnerHarness()

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val row = requireNotNull(harness.repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID))
    assertEquals("completed", row.workflowStatus)
    assertEquals("pr", row.currentStepId)
  }

  @Test
  fun `a blocked run advances the coarse workflow row to blocked at the blocked phase`() {
    // F-008: a blocked run marks the row blocked at the blocked phase.
    val harness = runnerHarness(validator = ThrowingValidator(failPhases = setOf("implement")))

    harness.runner.run(harness.request())

    val row = requireNotNull(harness.repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID))
    assertEquals("blocked", row.workflowStatus)
    assertEquals("implement", row.currentStepId)
  }

  @Test
  fun `missing required upstream output blocks loudly without launching the phase`() {
    val harness = runnerHarness()
    // implement is recorded complete but its output artifact is absent (corrupt
    // durable state), so review must loud-fail rather than launch on a missing upstream.
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, outputArtifact = null)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "implement")
    assertContains(blocked.blockedReason, "blind")
    assertTrue(harness.launchOrder().none { it == "review" })
  }

  @Test
  fun `all upstreams satisfied produces no spurious missing-upstream block`() {
    val harness = runnerHarness()
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
  }

  @Test
  fun `emits observability events and appends durable ledger read back through the store`() {
    val harness = runnerHarness()

    harness.runner.run(harness.request())

    val started = harness.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseStarted>().map { it.phaseId }
    val done = harness.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseCompleted>().map { it.phaseId }
    assertEquals(ALL_PHASES, started)
    assertEquals(ALL_PHASES, done)

    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)

    @Suppress("UNCHECKED_CAST")
    val ledger = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as List<Map<String, Any?>>
    val actions = ledger.map { it["action"] as String }
    assertContains(actions, "start")
    assertContains(actions, "complete")
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(sequences.sorted(), sequences)

    @Suppress("UNCHECKED_CAST")
    val records = artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val planRecord = records["plan"] as Map<String, Any?>
    assertEquals("completed", planRecord["status"])
    assertTrue((planRecord["started_at"] as String).isNotBlank())
    assertTrue((planRecord["finished_at"] as String).isNotBlank())
  }

  @Test
  fun `blocked run appends a blocked ledger entry`() {
    val harness = runnerHarness(validator = ThrowingValidator(failPhases = setOf("implement")))

    harness.runner.run(harness.request())

    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)

    @Suppress("UNCHECKED_CAST")
    val ledger = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as List<Map<String, Any?>>
    val blockedEntry = ledger.single { it["action"] == "blocked" }
    assertEquals("implement", blockedEntry["phase_id"])
    assertTrue((blockedEntry["blocked_reason"] as String).isNotBlank())
  }
}

// Runtime-owned lifecycle telemetry (started/finished/error) of the runner, split from
// FeatureTaskRuntimeRunnerTest so each class stays within its size budget while sharing the same
// file-private run harness.
class FeatureTaskRuntimeLifecycleTelemetryRunnerTest {
  @Test
  fun `runtime emits started on open and finished completed from its own per-phase records`() {
    val harness = telemetryRunnerHarness()

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.lifecycle.startedRecords.size)
    val started = harness.lifecycle.startedRecords.single()
    assertEquals("MEDIUM", started.featureSize)
    assertEquals(ISSUE_KEY, started.issueKey)
    assertEquals(1, harness.lifecycle.finishedRecords.size)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals(started.sessionId, finished.sessionId)
    assertEquals("completed", finished.completionStatus)
    assertEquals(ALL_PHASES, finished.completedPhaseIds)
    assertEquals(ALL_PHASES.associateWith { "completed" }, finished.phaseOutcomes)
    assertEquals("completed", finished.lastIncompletePhase)
    assertEquals("", finished.blockedReason)
  }

  @Test
  fun `runtime lifecycle telemetry honors run db override`() {
    val dbOverride = "/tmp/skillbill-runtime-override.db"
    val harness = telemetryRunnerHarness(runtimeConfig = RuntimeHarnessConfig(dbPathOverride = dbOverride))

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(harness.database.transactionDbOverrides.isNotEmpty())
    assertTrue(harness.database.transactionDbOverrides.all { it == dbOverride })
    assertEquals(SESSION_ID, harness.lifecycle.startedRecords.single().sessionId)
    assertEquals(SESSION_ID, harness.lifecycle.finishedRecords.single().sessionId)
  }

  @Test
  fun `runtime emits finished blocked with last incomplete phase from its own per-phase records`() {
    val harness = telemetryRunnerHarness(launcher = RuntimeRecordingLauncher { spawnFailedFacts() })

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals("blocked", finished.completionStatus)
    assertEquals("preplan", finished.lastIncompletePhase)
    assertTrue(finished.blockedReason.startsWith("runtime:"))
  }

  @Test
  fun `runtime emits finished decomposed at planning from its own per-phase records`() {
    // F-004: the only end-to-end coverage of completionStatusOf(Decomposed) -> decomposed_at_planning.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-telemetry-decompose")
    val harness = telemetryRunnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Decomposed>(report)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals("decomposed_at_planning", finished.completionStatus)
    assertEquals(listOf("preplan", "plan"), finished.completedPhaseIds)
    assertEquals("decomposed_at_planning", finished.lastIncompletePhase)
    assertEquals("", finished.blockedReason)
  }

  @Test
  fun `runtime emits finished error and rethrows when an exception escapes the run loop`() {
    // F-002/F-004: an exception escaping the loop must close the started session with the error
    // completion bucket (failure-isolated) while the original exception still propagates.
    val boom = RuntimeException("launcher exploded")
    val harness = telemetryRunnerHarness(launcher = RuntimeRecordingLauncher { throw boom })

    val thrown = assertFailsWith<RuntimeException> { harness.runner.run(harness.request) }

    assertEquals(boom, thrown)
    assertEquals(1, harness.lifecycle.startedRecords.size)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals("error", finished.completionStatus)
    assertEquals(harness.lifecycle.startedRecords.single().sessionId, finished.sessionId)
    assertEquals("preplan", finished.lastIncompletePhase)
    assertTrue(finished.blockedReason.startsWith("runtime:"))
  }

  @Test
  fun `runtime finished error emits even when phase outcome loading fails`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), lifecycle)
    val telemetry = FeatureTaskRuntimeLifecycleTelemetry(
      LifecycleTelemetryService(database, EnabledRuntimeTelemetrySettingsProvider),
    )

    telemetry.finishedError(
      SESSION_ID,
      phaseOutcomes = { error("phase load failed") },
      reviewFixIterationCount = { 0 },
      auditGapIterationCount = { 0 },
      dbOverride = null,
    )

    val finished = lifecycle.finishedRecords.single()
    assertEquals(SESSION_ID, finished.sessionId)
    assertEquals("error", finished.completionStatus)
    assertEquals(emptyMap(), finished.phaseOutcomes)
    assertEquals(emptyList(), finished.completedPhaseIds)
    assertEquals("unknown", finished.lastIncompletePhase)
    assertTrue(finished.blockedReason.startsWith("runtime:"))
  }
}

// Persistence, resume, decompose, and observability behaviour of the runner, split from
// FeatureTaskRuntimeRunnerTest so each class stays within its size budget while sharing the same
// file-private run harness.
class FeatureTaskRuntimeRunnerPersistenceTest {
  @Test
  fun `persists per-phase briefing durably with run-invariants upstream and review diff`() {
    val harness = runnerHarness()

    harness.runner.run(harness.request())

    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    assertEquals(ALL_PHASES.toSet(), briefings.keys)

    briefings.forEach { (phaseId, briefing) ->
      assertEquals(SPEC_REFERENCE, briefing.specReference, "spec reference for $phaseId")
      assertEquals("MEDIUM", briefing.featureSize, "feature size for $phaseId")
      assertEquals(listOf("AC-1", "AC-2"), briefing.acceptanceCriteria, "criteria for $phaseId")
      assertEquals(listOf("mandate-X"), briefing.mandatesAndOverrides, "mandates for $phaseId")
      assertContains(briefing.briefingText, "feature_size: MEDIUM")
      assertContains(briefing.briefingText, SPEC_REFERENCE)
      assertContains(briefing.briefingText, "mandate-X")
    }
    assertEquals(VALID_OUTPUT, briefings.getValue("plan").upstreamOutputsByPhaseId["preplan"])
    assertEquals(VALID_OUTPUT, briefings.getValue("implement").upstreamOutputsByPhaseId["plan"])
    // implement carries its reconciliation report (mutating-phase gate), so review's implement
    // upstream is the full reconciliation output rather than the minimal VALID_OUTPUT.
    assertEquals(validJsonOutput("implement"), briefings.getValue("review").upstreamOutputsByPhaseId["implement"])
    assertEquals(listOf("diff"), briefings.getValue("review").derivedContextKeys)
    assertContains(briefings.getValue("review").briefingText, "diff")
  }

  @Test
  fun `launch spawn failure blocks distinctly without schema gate or fix loop retries`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { spawnFailedFacts() },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("preplan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "failed to launch")
    assertTrue(!blocked.blockedReason.contains("schema"))
    assertTrue(!blocked.blockedReason.contains("exhausted the bounded fix loop"))
    assertEquals(listOf("preplan"), harness.launchedPhaseOrder())
  }

  @Test
  fun `launch timeout on a fix-loop phase blocks distinctly without burning the budget`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        if (request.invokedAgentId == phaseAgent("review")) timedOutFacts() else facts(defaultPhaseOutput(request))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "timed out")
    assertTrue(!blocked.blockedReason.contains("exhausted the bounded fix loop"))
    assertEquals(1, harness.launchedPhaseOrder().count { it == "review" })
  }

  @Test
  fun `malformed per-phase records artifact loud-fails on resume`() {
    val harness = runnerHarness()
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.repository.corruptRecordsArtifact(WORKFLOW_ID, "not-a-map")

    val failure = assertFailsWith<InvalidWorkflowStateSchemaError> {
      harness.runner.run(harness.request())
    }
    assertContains(failure.message.orEmpty(), FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY)
  }

  @Test
  fun `each phase launch delivers the briefing and output contract as the prompt override`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    harness.runner.run(harness.request())

    assertEquals(ALL_PHASES.size, harness.launcher.requests.size)
    harness.launcher.requests.zip(ALL_PHASES).forEach { (request, phaseId) ->
      val prompt = requireNotNull(request.skillRunRequest.promptOverride) {
        "phase '$phaseId' must launch with a prompt override, not the goal-continuation default"
      }
      assertContains(prompt, "# Feature-task-runtime phase briefing")
      assertContains(prompt, "phase: $phaseId")
      assertContains(prompt, "feature_size: MEDIUM")
      assertContains(prompt, "Scaling changes scope and verbosity only")
      assertContains(prompt, SPEC_REFERENCE)
      assertContains(prompt, "mandate-X")
      assertContains(prompt, "Required final output")
      assertContains(prompt, "\"phase_id\": must be \"$phaseId\"")
      assertContains(prompt, "\"contract_version\": must be exactly \"0.1\"")
      assertTrue(
        !prompt.contains("goal-continuation mode") && !prompt.contains("First execute this exact command"),
        "phase prompt for '$phaseId' must not instruct the goal-continuation flow",
      )
    }
  }

  @Test
  fun `resume preserves durable feature size instead of re-resolving from changed request inputs`() {
    val harness = runnerHarness(
      runtimeConfig = smallRuntimeConfig(),
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val changedRequest = harness.request().copy(
      runInvariants = harness.request().runInvariants.copy(featureSize = FeatureTaskRuntimeFeatureSize.LARGE),
    )
    val resumed = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(changedRequest))

    assertEquals("SMALL", resumed.featureSize)
    val projection = requireNotNull(
      FeatureTaskRuntimeStatusService(
        harness.recorder,
        harness.runInvariantsStore,
        harness.decomposeTerminalRecorder,
      )
        .status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID)),
    )
    assertEquals("SMALL", projection.featureSize)
  }

  @Test
  fun `partial resume launches review with durable small size and current unit scope`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = smallRuntimeConfig(),
    )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.runInvariantsStore.resolve(WORKFLOW_ID, proposed = harness.request().runInvariants)

    val changedRequest = harness.request().copy(
      runInvariants = harness.request().runInvariants.copy(featureSize = FeatureTaskRuntimeFeatureSize.LARGE),
    )
    val resumed = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(changedRequest))

    assertEquals("SMALL", resumed.featureSize)
    assertEquals(
      listOf("review", "audit", "validate", "write_history", "commit_push", "pr"),
      harness.launchedPhaseOrder(),
    )
    val reviewPrompt = requireNotNull(harness.launcher.requests.first().skillRunRequest.promptOverride)
    assertContains(reviewPrompt, "feature_size: SMALL")
    assertContains(reviewPrompt, "review_scope: current_unit_of_work")
    assertContains(reviewPrompt, "current-unit-of-work review scope")
    val reviewBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("SMALL", reviewBriefing.featureSize)
    assertEquals(listOf("current_unit_of_work"), reviewBriefing.derivedContextKeys)
  }

  @Test
  fun `a terminal schema-gate block persists the validator's reason in the blocked reason`() {
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("implement")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "Last schema-gate failure: rejected by fake validator")
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertContains(requireNotNull(implementRecord.blockedReason), "rejected by fake validator")
  }

  @Test
  fun `an exhausted fix loop persists the last validator reason in the blocked reason`() {
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("review")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "exhausted the bounded fix loop")
    assertContains(blocked.blockedReason, "Last schema-gate failure: rejected by fake validator")
  }

  @Test
  fun `per-phase records carry runtime-owned timestamps agent id and status`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertEquals(ALL_PHASES.toSet(), records.keys)
    ALL_PHASES.forEach { phaseId ->
      val record = records.getValue(phaseId)
      assertEquals("completed", record.status, "status for $phaseId")
      assertTrue(record.attemptCount >= 1, "attempt count for $phaseId")
      assertTrue(record.startedAt.isNotBlank(), "startedAt for $phaseId")
      assertTrue(requireNotNull(record.finishedAt).isNotBlank(), "finishedAt for $phaseId")
      assertTrue(requireNotNull(record.durationMillis) >= 0, "durationMillis for $phaseId")
      assertEquals(phaseAgent(phaseId), record.resolvedAgentId, "resolved agent id for $phaseId")
    }
  }

  @Test
  fun `decompose plan writes shared feature specs records terminal completed status and skips implement`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request())

    val decomposed = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(report)
    assertEquals(listOf("preplan", "plan"), decomposed.completedPhaseIds)
    assertEquals(2, decomposed.subtaskSpecPaths.size)
    assertEquals(listOf("preplan", "plan"), harness.launchedPhaseOrder())
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    // AC3: a spec.md parent and ordered spec_subtask_1*.md then spec_subtask_2*.md.
    assertTrue(
      decomposed.parentSpecPath.endsWith("spec.md"),
      "parent spec path must end with spec.md: ${decomposed.parentSpecPath}",
    )
    val firstSubtaskName = Path.of(decomposed.subtaskSpecPaths[0]).fileName.toString()
    val secondSubtaskName = Path.of(decomposed.subtaskSpecPaths[1]).fileName.toString()
    assertTrue(
      firstSubtaskName.startsWith("spec_subtask_1") && firstSubtaskName.endsWith(".md"),
      "first subtask spec must be spec_subtask_1*.md: $firstSubtaskName",
    )
    assertTrue(
      secondSubtaskName.startsWith("spec_subtask_2") && secondSubtaskName.endsWith(".md"),
      "second subtask spec must be spec_subtask_2*.md: $secondSubtaskName",
    )
    assertTrue(Files.isRegularFile(repoRoot.resolve(decomposed.parentSpecPath)))
    assertTrue(Files.isRegularFile(repoRoot.resolve(decomposed.decompositionManifestPath)))
    decomposed.subtaskSpecPaths.forEach { path -> assertTrue(Files.isRegularFile(repoRoot.resolve(path))) }

    val row = requireNotNull(harness.repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID))
    assertEquals("completed", row.workflowStatus)
    assertEquals("plan", row.currentStepId)
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)
    assertTrue(artifacts.containsKey(FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY))
    val terminal = requireNotNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
    assertEquals(decomposed.decompositionManifestPath, terminal.decompositionManifestPath)
    assertContains(terminal.reason, "needs ordered subtasks")
  }

  @Test
  fun `goal-continuation run suppresses decompose and pr then completes at commit push`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-subtask")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        val phaseId = phaseIdFromPrompt(prompt)
        facts(if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
        useRealDecompositionPlanner = true,
      ),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(ALL_PHASES.filterNot { it == "pr" }, harness.launchedPhaseOrder())
    assertEquals("commit_push", completed.subtaskOutcome?.lastResumableStep)
    assertNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)
    assertEquals(
      mapOf(
        "issue_key" to ISSUE_KEY,
        "subtask_id" to 5,
        "suppress_pr" to true,
        "goal_branch" to "feat/existing-runtime-branch",
        "code_review_mode" to "auto",
        "parent_workflow_id" to "wfl-parent",
      ),
      artifacts["goal_continuation"],
    )
    val outcome = artifacts["goal_continuation_outcome"] as Map<*, *>
    assertEquals("complete", outcome["status"])
    assertEquals("commit-runtime-1", outcome["commit_sha"])
    assertEquals("commit_push", outcome["last_resumable_step"])
    assertEquals(phaseAgent("commit_push"), outcome["finalizing_agent_id"])
    @Suppress("UNCHECKED_CAST")
    val participants = outcome["participating_agent_ids"] as List<String>
    assertTrue(participants.isNotEmpty(), "goal-continuation outcome must carry a non-empty participating_agent_ids")
    assertEquals("skipped", (artifacts["install_sync_result"] as Map<*, *>)["status"])
  }

  @Test
  fun `goal-continuation with payload commit sha completes without measuring git head`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-payload-sha")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = "measured-head-should-not-be-used" }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals("complete", completed.subtaskOutcome?.status)
    assertEquals("commit-runtime-1", completed.subtaskOutcome?.commitSha)
    assertEquals(0, git.headCommitShaCalls, "payload SHA present must not trigger a git HEAD measurement")
    val outcome = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_continuation_outcome"] as Map<*, *>
    assertEquals("complete", outcome["status"])
    assertEquals("commit-runtime-1", outcome["commit_sha"])
  }

  @Test
  fun `goal review preserves a top-level changes requested verdict without findings`() {
    var reviewLaunches = 0
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        val output = if (phaseId == "review" && reviewLaunches++ == 0) {
          """
          {
            "contract_version": "0.1",
            "phase_id": "review",
            "status": "completed",
            "summary": "Delegated review requested changes.",
            "verdict": "changes_requested",
            "produced_outputs": {"summary": "findings were retained in the full artifact"}
          }
          """.trimIndent()
        } else {
          validJsonOutput(phaseId)
        }
        facts(output)
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    @Suppress("UNCHECKED_CAST")
    val state = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_subtask_review_state"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val firstPass = (state["pass_results"] as List<Map<String, Any?>>).first()
    assertEquals("changes_requested", firstPass["verdict"])
    assertEquals(1, firstPass["unresolved_finding_count"])
  }

  @Test
  fun `orphaned goal review state blocks before a runtime child can be treated as standalone`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    val preplanOnly = harness.request().copy(
      transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
        forwardPhaseIds = listOf("preplan"),
        backwardEdges = emptyList(),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(preplanOnly))
    val orphanedArtifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap().apply {
      remove("goal_continuation")
    }
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, orphanedArtifacts)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(preplanOnly))
    assertContains(blocked.blockedReason, "Goal-continuation review persistence is malformed")
    assertEquals(1, harness.launcher.requests.size)
  }

  @Test
  fun `goal review resumes a reserved pass after a crash without consuming another pass`() {
    var crashReview = true
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "review" && crashReview) spawnFailedFacts() else facts(validJsonOutput(phaseId))
      },
    )

    val first = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals("review", first.lastIncompletePhase)
    val reserved = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, reserved.reservedPassNumber)
    assertEquals(0, reserved.completedPassCount)

    crashReview = false
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    val resumed = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, resumed.completedPassCount)
    assertEquals(null, resumed.reservedPassNumber)
  }

  @Test
  fun `goal review recovers an incompatible baseline before review evidence exists`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-review-recover")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val recoveredBaseline = GoalSubtaskReviewBaseline("1".repeat(40), listOf("preexisting.tmp"))
    git.goalReviewBuildResults += GoalSubtaskReviewInputResult(
      status = "error",
      error = "Persisted review base '${"0".repeat(40)}' is not an ancestor of current HEAD.",
      failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
    )
    git.goalReviewRecoveredBaseline = recoveredBaseline
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val state = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(recoveredBaseline.reviewBaseSha, state.reviewBaseSha)
    assertEquals(recoveredBaseline.baselineUntrackedPaths, state.baselineUntrackedPaths)
    assertEquals(1, git.goalReviewRecoverCalls)
    assertEquals(listOf("0".repeat(40), recoveredBaseline.reviewBaseSha), git.goalReviewBuildInputs.map { it.reviewBaseSha })
  }

  @Test
  fun `crash reconciliation preserves a changes requested disposition without structured findings`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    harness.runner.run(
      harness.request().copy(
        transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = listOf("preplan"),
          backwardEdges = emptyList(),
        ),
      ),
    )
    harness.goalContinuationRecorder.reserveGoalReviewPass(WORKFLOW_ID)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase(
      "review",
      "completed",
      1,
      phaseAgent("review"),
      """
        {"contract_version":"0.1","phase_id":"review","status":"completed","summary":"Review requests changes.","verdict":"changes_requested","produced_outputs":{"summary":"full evidence remains durable"}}
      """.trimIndent(),
    )

    harness.runner.run(
      harness.request().copy(
        transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = listOf("preplan", "plan", "implement", "review"),
          backwardEdges = emptyList(),
        ),
      ),
    )

    val state = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, state.completedPassCount)
    val passResult = state.passResults.single()
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, passResult.verdict)
    assertEquals(1, passResult.unresolvedFindingCount)
  }

  @Test
  fun `goal-continuation outcome struct carries finalizingAgentId and participatingAgentIds`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-attribution-struct")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val subtaskOutcome =
      requireNotNull(completed.subtaskOutcome) { "subtaskOutcome must be present for a goal-continuation run" }
    assertEquals(
      phaseAgent("commit_push"),
      subtaskOutcome.finalizingAgentId,
      "finalizingAgentId on the outcome struct must be the commit_push phase agent",
    )
    assertTrue(
      subtaskOutcome.participatingAgentIds.isNotEmpty(),
      "participatingAgentIds on the outcome struct must be non-empty for a completed goal-continuation run",
    )
  }

  @Test
  fun `goal-continuation without payload sha completes with measured git head`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-measured-sha")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = "measured-head-sha" }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(COMMIT_PUSH_NO_SHA_OUTPUT))

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals("complete", completed.subtaskOutcome?.status)
    assertEquals("measured-head-sha", completed.subtaskOutcome?.commitSha)
    assertTrue(git.headCommitShaCalls >= 1, "a missing payload SHA must trigger a git HEAD measurement")
    val outcome = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_continuation_outcome"] as Map<*, *>
    assertEquals("complete", outcome["status"])
    assertEquals("measured-head-sha", outcome["commit_sha"])
  }

  @Test
  fun `goal-continuation without payload sha and unmeasurable head blocks instead of completing`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-no-sha")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(COMMIT_PUSH_NO_SHA_OUTPUT))

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals("blocked", completed.subtaskOutcome?.status)
    assertNull(completed.subtaskOutcome?.commitSha)
    assertEquals("commit_push", completed.subtaskOutcome?.lastResumableStep)
    assertContains(requireNotNull(completed.subtaskOutcome?.blockedReason), "no commit SHA")
    val outcome = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_continuation_outcome"] as Map<*, *>
    assertEquals("blocked", outcome["status"])
    assertNull(outcome["commit_sha"], "a blocked SHA-less outcome must not record a commit_sha")
  }

  @Test
  fun `non-goal-continuation run never measures git head for the outcome`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
      .also { it.headCommitShaValue = "should-not-read" }
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    harness.runner.run(harness.request())

    assertEquals(0, git.headCommitShaCalls, "a non-goal-continuation run must not measure git HEAD for an outcome")
  }
}

class FeatureTaskRuntimeRunnerSpecLifecycleTest {
  @Test
  fun `standalone run flips its spec status to complete before commit_push so the commit includes it`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-spec-status")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\n---\n\n# Spec\n")
    var specAtCommitLaunch: String? = null
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "commit_push") {
          specAtCommitLaunch = Files.readString(specPath)
        }
        facts(defaultPhaseOutput(request))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertContains(requireNotNull(specAtCommitLaunch), "status: Complete")
    assertContains(Files.readString(specPath), "status: Complete")
  }

  @Test
  fun `goal-continuation run leaves its spec status for the goal runner manifest projection to own`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-spec-status-goal")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\n---\n\n# Spec\n")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertContains(Files.readString(specPath), "status: Pending")
  }

  @Test
  fun `single_spec linear run deletes the spec scratch dir on terminal success`() {
    // AC2: a single_spec linear-mode run deletes the local spec scratch only on terminal success.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-linear-delete")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(listOf(specPath.parent), harness.specScratchStore.deletedDirectories)
    assertFalse(Files.exists(specPath.parent), "linear single_spec scratch must be deleted on success")
  }

  @Test
  fun `single_spec linear run that blocks leaves the spec scratch intact`() {
    // AC3: an aborted/blocked linear run leaves the scratch intact and resumable.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-linear-block")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "commit_push") COMMIT_PUSH_BLOCKED_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertTrue(harness.specScratchStore.deletions.isEmpty(), "a blocked run must not delete the scratch")
    assertTrue(Files.exists(specPath), "blocked linear run leaves the spec scratch intact")
  }

  @Test
  fun `local-mode run never deletes the spec scratch`() {
    // AC6: local mode (default, no spec_source line) keeps the committed spec and deletes nothing.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-local-no-delete")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\n---\n\n# Spec\n")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertTrue(harness.specScratchStore.deletions.isEmpty(), "local mode must not delete the scratch")
    assertTrue(Files.exists(specPath), "local mode keeps the committed spec on disk")
  }

  @Test
  fun `goal-continuation linear subtask run leaves spec deletion to the goal runner`() {
    // AC2 ownership: the runner deletes only single_spec scratch; decomposed deletion is the goal
    // runner's responsibility, so a goal-continuation subtask run must not delete its spec.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-linear-goalcont")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertTrue(harness.specScratchStore.deletions.isEmpty(), "the goal runner owns goal-continuation deletion")
    assertTrue(Files.exists(specPath), "goal-continuation run must leave the subtask spec for the goal runner")
  }

  @Test
  fun `resume of a durably complete decompose plan reports decomposed without advancing to implement`() {
    // PC-F001 (resume fall-through): PLAN is durably completed as a non-goal-continuation decompose
    // outcome, but the process crashed before the decompose terminal was observed. A re-run must
    // re-derive the decompose stop and terminate at planning, never advancing to implement.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-resume")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), DECOMPOSE_PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val decomposed = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(report)
    assertEquals(2, decomposed.subtaskSpecPaths.size)
    // The implement agent must never launch: a decompose terminal must not advance the run.
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    assertTrue(harness.launchedPhaseOrder().isEmpty(), "no phase agent relaunches on a complete-plan resume")
    val terminal = requireNotNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
    assertContains(terminal.reason, "needs ordered subtasks")
  }

  @Test
  fun `resume reconstructs an already-recorded decompose terminal without rewriting specs`() {
    // PC-F001 (idempotent resume): when a decompose terminal is already durably recorded, the resume
    // reconstructs the Decomposed report from it rather than re-running the shared prep write path.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-idempotent")
    val firstHarness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )
    val first = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(firstHarness.runner.run(firstHarness.request()))
    // Only the write path (resolveFromPlanOutput) emits DecomposedAtPlanning; the loadDecomposeTerminal
    // early-return reconstruction path does not. A single emission across BOTH runs therefore proves
    // the resume bypassed the writer rather than re-running the shared prep write path.
    val emissionsAfterFirst = firstHarness.events.count { it is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning }
    assertEquals(1, emissionsAfterFirst, "the first run writes the decomposition and emits exactly once")

    val resumed = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(firstHarness.runner.run(firstHarness.request()))
    assertEquals(first.decompositionManifestPath, resumed.decompositionManifestPath)
    assertEquals(first.subtaskSpecPaths, resumed.subtaskSpecPaths)
    assertTrue(resumed.subtaskSpecPaths.isNotEmpty())
    val emissionsAfterResume = firstHarness.events.count { it is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning }
    assertEquals(
      1,
      emissionsAfterResume,
      "the resume reconstructs from the recorded terminal and must NOT re-emit (i.e. must not re-run the writer)",
    )
  }

  @Test
  fun `malformed decompose package blocks loudly instead of throwing or advancing`() {
    // PC-F001 (crash guard): a plan envelope declaring mode=decompose but with a malformed package
    // (a subtask missing required fields) must produce a diagnosable Blocked outcome, not crash.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-malformed")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") MALFORMED_DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "malformed decomposition package")
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    // The block is durable and visible to status: the plan phase carries a terminal blocked record.
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertTrue(requireNotNull(planRecord.blockedReason).isNotBlank())
    assertNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
  }

  @Test
  fun `decoder-valid but writer-invalid decompose package blocks loudly on a fresh run`() {
    // PC-F001 residual: a plan envelope declaring mode=decompose with a package the typed decoder
    // accepts (every required field present + correctly typed) but the writer rejects on a
    // business rule (subtask ids descend [2, 1]) throws InvalidFeatureSpecPreparationRequestError
    // from the write path. That exception extends SkillBillRuntimeException (NOT
    // IllegalArgumentException) and previously escaped resolve()'s catch and crashed run(). It must
    // now produce a diagnosable Blocked terminal, never an uncaught throw, and never launch implement.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-writer-invalid")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") WRITER_INVALID_DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "malformed decomposition package")
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertTrue(requireNotNull(planRecord.blockedReason).isNotBlank())
    assertNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
  }

  @Test
  fun `decoder-valid but writer-invalid decompose package blocks loudly on a plan-complete resume`() {
    // PC-F001 residual (resume): same writer-rejected package, but PLAN is already durably complete
    // (crash after PLAN before the terminal was observed). The resume re-derives the decompose stop
    // from the persisted output and must Block, never crash or advance to implement.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-writer-invalid-resume")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), WRITER_INVALID_DECOMPOSE_PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "malformed decomposition package")
    assertTrue(harness.launchedPhaseOrder().isEmpty(), "no phase agent relaunches on a complete-plan resume")
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertTrue(requireNotNull(planRecord.blockedReason).isNotBlank())
    assertNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
  }

  @Test
  fun `a block during a backward-edge re-entry persists the loop context and enforces the cap across the crash`() {
    // MAJOR 1 (AC4/AC8): a re-entered phase that fires its edge (edge iteration 1) then BLOCKS on an
    // infra failure must persist the runtime-minted loop context on its terminal blocked record. The
    // bug overwrote the running record's loop_id/edge_iteration with a context-less blocked record, so
    // on resume the per-edge watermark reset to 0 and the edge could fire perEdgeCap more times after
    // every crash-at-blocked-re-entry, bypassing the cap.
    var preplanLaunches = 0
    var crashOnReentry = true
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when {
          phaseId == "preplan" && ++preplanLaunches == 2 && crashOnReentry -> spawnFailedFacts()
          phaseId == "plan" -> facts(verdictPlanOutput("needs_fix"))
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: preplan -> plan needs_fix fires the edge (iteration 1) -> preplan re-enters and crashes.
    val firstReport = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("preplan", firstBlocked.lastIncompletePhase)
    // The terminal blocked record retained the loop context — the watermark the bug dropped.
    val blockedPreplan = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals("blocked", blockedPreplan.status)
    assertEquals("plan-fix", blockedPreplan.loopId)
    assertEquals(1, blockedPreplan.edgeIteration)

    // Run 2 (resume): the crash heals and the cycle continues. The surviving watermark means the
    // resume mints the NEXT edge iteration (the cap), never restarting at 1, so across both runs the
    // edge fires exactly 1..cap. Had the blocked record dropped the context, resume would re-mint
    // iteration 1 and the edge could fire perEdgeCap more times, bypassing the cap.
    crashOnReentry = false
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request(PLAN_FIX_CYCLE)))
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals((1..PLAN_FIX_CAP).toList(), edgeIterations, "the edge fired 1..cap across the crash, never restarting")
  }
}

// SKILL-85 Subtask 4: the M1 review-driven implement_fix loop matrix over the real phase
// topology, kept in a sibling class so the primary runner test class stays within its size
// budget while sharing the same file-private run harness.
class FeatureTaskRuntimeReviewFixLoopTest {
  // --- SKILL-85 Subtask 4: M1 review-driven implement_fix loop over the real phase topology ------

  @Test
  fun `m1 finished telemetry carries the review fix iteration count after a loop ran`() {
    val harness = telemetryRunnerHarness(launcher = reviewFixLauncher(convergeOnReview = 2))

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals(1, finished.reviewFixIterationCount, "the single review->fix iteration is reflected in telemetry")
  }

  @Test
  fun `m1 finished telemetry reports zero review fix iterations on a clean run`() {
    // The additive count is 0 when the review_fix loop never fired (a clean forward run).
    val harness = telemetryRunnerHarness(launcher = reviewFixLauncher(convergeOnReview = 1))

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(0, harness.lifecycle.finishedRecords.single().reviewFixIterationCount)
  }

  // (a) AC3/AC10: an approved review advances to audit and never launches the loop-only fix phase.
  @Test
  fun `m1 approved review advances to audit without launching implement_fix`() {
    val harness = runnerHarness(launcher = reviewFixLauncher(convergeOnReview = 1))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(ALL_PHASES, launched, "a clean run launches the forward pipeline, skipping implement_fix")
    assertTrue(launched.none { it == "implement_fix" })
  }

  // (b)+(e) AC2/AC6/AC10: changes_requested spawns implement_fix carrying the findings, then re-reviews.
  @Test
  fun `m1 changes_requested spawns implement_fix with the findings then re-reviews`() {
    val harness = runnerHarness(launcher = reviewFixLauncher(convergeOnReview = 2))

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "implement_fix" }, "one fix iteration before converging")
    assertEquals(2, launched.count { it == "review" }, "initial review + one re-review")
    // The fix phase launches after the first review and before the re-review (the reopened span).
    val firstReview = launched.indexOf("review")
    val fixIndex = launched.indexOf("implement_fix")
    assertTrue(firstReview < fixIndex, "implement_fix runs after the triggering review")
    assertTrue(fixIndex < launched.lastIndexOf("review"), "the re-review runs after implement_fix")
    // (e) the fix briefing carries the review findings handed off for remediation.
    val fixBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["implement_fix"])
    assertContains(fixBriefing.briefingText, REVIEW_BLOCKER_MESSAGE)
    // (AC6) each implement_fix launch + re-review carry the review_fix loop id + iteration in the ledger.
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
    assertTrue(loopEdges.all { it.loopId == "review_fix" })
    val reviewPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { it.contains("Phase: review") }
    assertEquals(2, reviewPrompts.size)
    assertContains(reviewPrompts[0], "bill-code-review mode:delegated")
    assertContains(reviewPrompts[1], "bill-code-review mode:inline")
    assertEquals(
      CodeReviewExecutionMode.DELEGATED,
      requireNotNull(harness.runInvariantsStore.resolve(WORKFLOW_ID)).codeReviewMode,
    )
  }

  @Test
  fun `resume without a review-mode request retains the durable mode for a re-review`() {
    var failFirstReview = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "review" && failFirstReview) {
          failFirstReview = false
          spawnFailedFacts()
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )

    val first = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(first)

    val resumed = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumed)
    val reviewPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { it.contains("Phase: review") }
    assertEquals(2, reviewPrompts.size)
    assertTrue(reviewPrompts.all { it.contains("bill-code-review mode:inline") })
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      requireNotNull(harness.runInvariantsStore.resolve(WORKFLOW_ID)).codeReviewMode,
    )
  }

  @Test
  fun `failed second review resumes the same durably reserved inline pass`() {
    var reviewLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewLaunches += 1
            when (reviewLaunches) {
              1 -> facts(reviewFindingsOutput(changesRequested = true))
              2 -> spawnFailedFacts()
              else -> facts(reviewFindingsOutput(changesRequested = false))
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val first = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(first)
    val blockedReview = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("blocked", blockedReview.status)
    assertEquals(2, blockedReview.reviewPassNumber)
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    val reviewPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { it.contains("Phase: review") }
    assertEquals(
      3,
      reviewPrompts.size,
      "the failed launch retries the same reserved pass rather than reserving pass three",
    )
    assertContains(reviewPrompts[0], "bill-code-review mode:delegated")
    reviewPrompts.drop(1).forEach { prompt -> assertContains(prompt, "bill-code-review mode:inline") }
    val completedReview = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", completedReview.status)
    assertEquals(2, completedReview.reviewPassNumber)
  }

  @Test
  fun `resume rejects a changed review mode before opening or launching`() {
    val harness = runnerHarness()
    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE)),
    )
    val launchCount = harness.launcher.requests.size

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "Cannot change code-review mode on resume")
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  // (c) AC3/AC10: convergence on the only allowed re-review still advances to audit.
  @Test
  fun `m1 converges on the last allowed iteration and advances`() {
    val harness = runnerHarness(launcher = reviewFixLauncher(convergeOnReview = 2))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "implement_fix" }, "one fix iteration before converging")
    assertEquals(2, launched.count { it == "review" })
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
  }

  @Test
  fun `m1 cap exhaustion advances to audit with review findings preserved`() {
    val harness = runnerHarness(launcher = reviewFixLauncher(convergeOnReview = 99))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertTrue(launched.any { it == "audit" })
    assertEquals(1, launched.count { it == "implement_fix" }, "the fix ran once")
    assertEquals(2, launched.count { it == "review" }, "the initial and inline review consumed the budget")
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", reviewRecord.status)
    assertEquals(2, reviewRecord.reviewPassNumber)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
  }

  // (f) AC5/AC10: an idempotent re-entry — implement_fix's reconciliation gate is enforced, so a fix
  // output that omits the reconciliation report blocks loudly rather than silently double-applying.
  @Test
  fun `m1 implement_fix without a reconciliation report blocks on the idempotency gate`() {
    var reviewLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewLaunches += 1
            facts(reviewFindingsOutput(changesRequested = true))
          }
          // A fix output WITHOUT reconciled_state: the mutating-phase gate must reject it.
          "implement_fix" -> facts(
            """{"contract_version":"0.1","phase_id":"implement_fix","status":"completed",""" +
              """"summary":"fix","produced_outputs":{"changed_files":["src/Foo.kt"]}}""",
          )
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement_fix", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "reconcil")
  }

  // (g) AC5/AC10: a crash mid-loop (implement_fix re-entered then spawn-fails) resumes at the correct
  // phase with the durable loop context preserved (the watermark is never reset), then continues the
  // loop to convergence. The edge counter advances monotonically across the crash, never restarting.
  @Test
  fun `m1 crash during implement_fix resumes with the loop context preserved and converges`() {
    var fixLaunches = 0
    var crashOnFix = true
    var reviewLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewLaunches += 1
            // Approve once the fix has actually run post-crash (the second fix launch), so the resumed
            // loop converges rather than looping to the cap.
            facts(reviewFindingsOutput(changesRequested = fixLaunches < 2))
          }
          "implement_fix" -> {
            fixLaunches += 1
            if (fixLaunches == 1 && crashOnFix) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: review changes_requested fires the edge (iteration 1) -> implement_fix re-enters and crashes.
    val firstReport = harness.runner.run(harness.request())
    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("implement_fix", firstBlocked.lastIncompletePhase)
    // AC5: the terminal blocked fix record retained the review_fix loop context (the watermark the
    // resume reconstruction relies on), not a context-less record that would reset the cap.
    val fixRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement_fix"])
    assertEquals("review_fix", fixRecord.loopId)
    assertEquals(1, fixRecord.edgeIteration)

    // Run 2 (resume): the crash heals; the loop re-enters the fix from the preserved watermark and the
    // re-review then approves, advancing the run to completion.
    crashOnFix = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    // The edge iterations are monotonic and bounded across the crash (never reset to a fresh 1).
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals(edgeIterations.sorted(), edgeIterations, "edge iterations advance monotonically across the crash")
    assertEquals(edgeIterations.toSet().size, edgeIterations.size, "no edge iteration repeats after the crash")
    assertTrue(edgeIterations.all { it <= 1 }, "the cap is never exceeded across the crash")
  }

  @Test
  fun `m1 cap-exhausted review_fix loop advances on resume without relaunching the fix`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "review") reviewFindingsOutput(changesRequested = true) else validJsonOutput(phaseId))
      },
    )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedReentryPhase("implement_fix", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT, "review_fix", 1)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertTrue(launched.any { it == "audit" })
    assertTrue(launched.none { it == "implement_fix" })
  }

  @Test
  fun `ledger-only review fix resumes at implement fix without consuming the edge`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "review") reviewFindingsOutput(changesRequested = false) else validJsonOutput(phaseId))
      },
    )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedReviewPhase("completed", 1, reviewFindingsOutput(changesRequested = true), 1)
    harness.seedLoopEdge("implement_fix", "review_fix", 1)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals("implement_fix", launched.first())
    assertTrue(launched.none { it == "preplan" || it == "plan" })
    assertEquals(
      listOf(1),
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "review_fix" }
        .mapNotNull { it.edgeIteration },
    )
  }

  // (i) AC5/SKILL-85-F-001: a crash during the reserved inline pass after its same-phase attempt count
  // accrued beyond the schema budget must resume that same pass rather than prematurely block.
  @Test
  fun `m1 crash with review attempt_count past the schema budget resumes without premature block`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        // The resumed review approves (empty findings), so a relaunch advances rather than re-looping.
        facts(if (phaseId == "review") reviewFindingsOutput(changesRequested = false) else validJsonOutput(phaseId))
      },
    )
    // Model the crash: the only fix ran, then review pass two crashed while running at attempt_count 3.
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedReentryPhase("implement_fix", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT, "review_fix", 1)
    harness.seedReviewPhase(
      "running",
      3,
      reviewFindingsOutput(changesRequested = true),
      2,
    )

    val report = harness.runner.run(harness.request())

    // The run did NOT prematurely block on the schema budget: it relaunched review and converged.
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertTrue(launched.contains("review"), "the resumed review relaunched rather than pre-blocking")
    assertTrue(launched.contains("audit"), "the approved review advanced past the loop to audit")
    // The review settled completed (not blocked) on resume.
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", reviewRecord.status)
  }

  // (j) AC1/SKILL-85-F-003: a review output carrying NEITHER a structured verdict NOR a findings array
  // is missing every verification signal; it must fail loudly through the schema gate rather than
  // silently advancing to audit (prose alone cannot advance past a possible Blocker/Major).
  @Test
  fun `m1 review with neither verdict nor findings blocks rather than silently advancing`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "review") REVIEW_NO_SIGNAL_OUTPUT else validJsonOutput(phaseId))
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "verification signal")
    // It never advanced to audit on the missing signal.
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "audit" })
  }
}

// Branch-setup establishment, resume re-attach, loud-fail blocks, durability/visibility, and
// resolved-branch idempotency through the runner, kept in a sibling class so the runner test class
// stays within its size budget while sharing the same file-private run harness. (Pure branch-setup
// decision logic lives in FeatureTaskRuntimeBranchSetupTest.)
class FeatureTaskRuntimeBranchSetupRunnerTest {
  @Test
  fun `starts on default branch creates and switches to the feature branch before implement`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(EXPECTED_FEATURE_BRANCH, completed.resolvedBranch)
    assertEquals(
      listOf(RecordingWorkflowGitOperations.CheckoutCall(EXPECTED_FEATURE_BRANCH, "main")),
      git.checkoutCalls,
    )
    val resolved = requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID))
    assertEquals(EXPECTED_FEATURE_BRANCH, resolved.branch)
    assertTrue(resolved.created)
    val branchEvent = assertIs<FeatureTaskRuntimeRunEvent.BranchResolved>(
      harness.events.first { it is FeatureTaskRuntimeRunEvent.BranchResolved },
    )
    assertTrue(branchEvent.created)
    // The preplan and plan phases are non-file-mutating; branch setup happens before implement.
    assertEquals(ALL_PHASES, harness.launchOrder())
  }

  @Test
  fun `starts on a non-default branch reuses it without checking out a new branch`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/pre-created")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals("feat/pre-created", completed.resolvedBranch)
    assertTrue(git.checkoutCalls.isEmpty(), "reuse must not check out a new branch")
    val resolved = requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID))
    assertEquals("feat/pre-created", resolved.branch)
    assertEquals(false, resolved.created)
  }

  @Test
  fun `cannot establish a feature branch blocks loudly and launches no file-mutating phase`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      checkoutResult = WorkflowGitOperationResult(status = "error", error = "checkout exploded"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, EXPECTED_FEATURE_BRANCH)
    assertContains(blocked.blockedReason, "checkout exploded")
    assertContains(blocked.blockedReason, "default branch")
    // Preplan and plan launched (non-mutating), but no file-mutating phase ever launched.
    assertEquals(NON_FILE_MUTATING_PHASES.toList(), harness.launchOrder())
    assertTrue(harness.launchOrder().none { it !in NON_FILE_MUTATING_PHASES })
  }

  @Test
  fun `unreadable current branch blocks loudly and launches no file-mutating phase`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchResult = WorkflowGitOperationResult(status = "error", error = "git HEAD unreadable"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "git HEAD unreadable")
    assertContains(blocked.blockedReason, "default branch")
    assertTrue(git.checkoutCalls.isEmpty(), "an unreadable current branch must never check out")
    assertEquals(NON_FILE_MUTATING_PHASES.toList(), harness.launchOrder())
    assertTrue(
      harness.launchOrder().none { it !in NON_FILE_MUTATING_PHASES },
      "no file-mutating phase may launch when the current branch is unreadable",
    )
  }

  @Test
  fun `resume on default with a persisted branch re-attaches via exactly one checkout to it`() {
    // HEAD on main alone would trigger create+checkout of the convention branch; the persisted
    // branch must drive the decision, producing exactly one checkout to it and no second/divergent
    // branch. A divergent value proves the persisted load (not a fresh resolution) decided.
    val persistedBranch = "feat/persisted-resume-branch"
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(persistedBranch, baseBranch = "main", created = true)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(persistedBranch, completed.resolvedBranch)
    assertEquals(
      listOf(RecordingWorkflowGitOperations.CheckoutCall(persistedBranch, null)),
      git.checkoutCalls,
      "re-attach on default must perform exactly one checkout, targeting the persisted branch",
    )
    assertTrue(
      git.checkoutCalls.none { it.branch == EXPECTED_FEATURE_BRANCH },
      "re-attach must not create the freshly-resolved convention branch",
    )
    val branchEvent = assertIs<FeatureTaskRuntimeRunEvent.BranchResolved>(
      harness.events.first { it is FeatureTaskRuntimeRunEvent.BranchResolved },
    )
    assertEquals(persistedBranch, branchEvent.branch)
    assertTrue(branchEvent.reused)
    assertEquals(false, branchEvent.created)
    assertEquals(persistedBranch, requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID)).branch)
  }

  @Test
  fun `resume already on the persisted branch re-attaches without any checkout`() {
    val persistedBranch = "feat/persisted-resume-branch"
    val git = RecordingWorkflowGitOperations(currentBranchValue = persistedBranch)
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(persistedBranch, baseBranch = "main", created = true)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(persistedBranch, completed.resolvedBranch)
    assertTrue(git.checkoutCalls.isEmpty(), "HEAD already on the persisted branch must not check out")
    val branchEvent = assertIs<FeatureTaskRuntimeRunEvent.BranchResolved>(
      harness.events.first { it is FeatureTaskRuntimeRunEvent.BranchResolved },
    )
    assertEquals(persistedBranch, branchEvent.branch)
    assertTrue(branchEvent.reused)
    assertEquals(false, branchEvent.created)
    assertEquals(persistedBranch, requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID)).branch)
  }

  @Test
  fun `resume whose persisted branch no longer exists blocks loudly and creates no branch`() {
    val persistedBranch = "feat/deleted-between-runs"
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      existingBranches = emptySet(),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(persistedBranch, baseBranch = "main", created = true)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, persistedBranch)
    assertContains(blocked.blockedReason, "no longer exists")
    assertEquals(listOf(persistedBranch), git.branchExistsCalls)
    assertTrue(
      git.checkoutCalls.isEmpty(),
      "a missing persisted branch must never check out (would create a divergent branch): ${git.checkoutCalls}",
    )
    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(launchedMutating.isEmpty(), "no file-mutating phase may launch when re-attach fails: $launchedMutating")
  }

  @Test
  fun `resume blocks loudly when persisted branch existence cannot be verified`() {
    val persistedBranch = "feat/existence-unreadable"
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      branchExistsResult = WorkflowGitOperationResult(status = "error", error = "rev-parse exploded"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(persistedBranch, baseBranch = "main", created = true)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, persistedBranch)
    assertContains(blocked.blockedReason, "rev-parse exploded")
    assertTrue(git.checkoutCalls.isEmpty(), "an unverifiable persisted branch must never check out")
    assertTrue(harness.launchOrder().none { it !in NON_FILE_MUTATING_PHASES })
  }

  @Test
  fun `checkout that lands on a different branch blocks loudly and launches no file-mutating phase`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      landedBranchAfterCheckout = "feat/wrong-landing",
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "feat/wrong-landing")
    assertContains(blocked.blockedReason, EXPECTED_FEATURE_BRANCH)
    assertTrue(blocked.blockedReason.contains("HEAD is on"))
    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(
      launchedMutating.isEmpty(),
      "no file-mutating phase may launch when HEAD lands on the wrong branch: $launchedMutating",
    )
  }

  @Test
  fun `checkout that lands on a protected branch blocks loudly and launches no file-mutating phase`() {
    // A corrupt persisted branch name that is itself protected: the checkout reports landing on it
    // (landed == target), so the post-checkout guard must reject it via the protected-branch arm
    // rather than the wrong-branch arm.
    val protectedPersisted = "main"
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/pre-created")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(protectedPersisted, baseBranch = "main", created = false)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "protected branch")
    assertContains(blocked.blockedReason, "main")
    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(
      launchedMutating.isEmpty(),
      "no file-mutating phase may launch when HEAD lands on a protected branch: $launchedMutating",
    )
  }

  @Test
  fun `resume already on a protected persisted branch blocks loudly and launches no file-mutating phase`() {
    // Same corrupt persisted branch as the checkout-protected test, but HEAD is already on the
    // protected branch. The no-op re-attach path must still reject it before launching implement.
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch("main", baseBranch = "main", created = false)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "protected branch")
    assertContains(blocked.blockedReason, "main")
    assertTrue(git.checkoutCalls.isEmpty(), "already-on-protected re-attach must not check out")
    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(
      launchedMutating.isEmpty(),
      "no file-mutating phase may launch when persisted branch is protected: $launchedMutating",
    )
  }

  @Test
  fun `no file-mutating phase launches while on the default branch`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      checkoutResult = WorkflowGitOperationResult(status = "error", error = "denied"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    harness.runner.run(harness.request())

    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(launchedMutating.isEmpty(), "no file-mutating phase may launch on the default branch: $launchedMutating")
  }

  @Test
  fun `branch-setup block is durably visible to status, observability, and the ledger`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      checkoutResult = WorkflowGitOperationResult(status = "error", error = "checkout exploded"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)

    // Durable status projection: the branch-setup block is no longer invisible (blockedCount=0,
    // implement merely running/pending); it surfaces as a first-class blocked phase with its reason.
    val status = requireNotNull(
      FeatureTaskRuntimeStatusService(
        harness.recorder,
        harness.runInvariantsStore,
        harness.decomposeTerminalRecorder,
      )
        .status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID)),
    )
    assertEquals(1, status.blockedCount)
    val implementStatus = status.phases.single { it.phaseId == "implement" }
    assertEquals("blocked", implementStatus.status)

    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("blocked", implementRecord.status)
    assertContains(requireNotNull(implementRecord.blockedReason), "checkout exploded")

    // Typed observability event mirrors the per-phase block path.
    val event = assertIs<FeatureTaskRuntimeRunEvent.BranchSetupBlocked>(
      harness.events.single { it is FeatureTaskRuntimeRunEvent.BranchSetupBlocked },
    )
    assertEquals("implement", event.phaseId)
    assertContains(event.blockedReason, "checkout exploded")

    // Append-only ledger carries the blocked entry for the audit trail.
    val ledgerEntry = requireNotNull(harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty())
      .single { it.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED }
    assertEquals("implement", ledgerEntry.phaseId)
    assertContains(requireNotNull(ledgerEntry.blockedReason), "checkout exploded")
  }

  @Test
  fun `resume after a recoverable branch-setup block re-attempts setup and launches the implement phase`() {
    // F-002: a prior run blocked at branch setup (transient git error) and persisted a blocked
    // record under "implement" keyed to the branch-setup sentinel agent. The operator fixes the git
    // condition and resumes; branch setup now succeeds, so the stale block must be superseded and the
    // implement phase must actually launch rather than re-block forever.
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    lateinit var harness: RunnerHarness
    var observedPreLaunchRecord = false
    harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(git, CONVENTION_SPEC_REFERENCE),
        eventSink = FeatureTaskRuntimeRunEventSink { event ->
          if (event is FeatureTaskRuntimeRunEvent.PhaseStarted && event.phaseId == "implement") {
            val preLaunchRecord =
              requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
            assertEquals("blocked", preLaunchRecord.status)
            assertEquals(BRANCH_SETUP_AGENT_ID, preLaunchRecord.resolvedAgentId)
            observedPreLaunchRecord = true
          }
        },
      ),
    )
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedBranchSetupBlockedPhase("implement", "checkout exploded on the prior run")

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(EXPECTED_FEATURE_BRANCH, completed.resolvedBranch)
    // The implement phase (and every later file-mutating phase) launched: the poison is cleared.
    assertEquals(ALL_PHASES.filterNot(NON_FILE_MUTATING_PHASES::contains), harness.launchedPhaseOrder())
    // The durable record is superseded back to a completed implement-agent record, not left blocked.
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("completed", implementRecord.status)
    assertEquals(phaseAgent("implement"), implementRecord.resolvedAgentId)
    assertEquals(1, implementRecord.attemptCount)
    assertTrue(observedPreLaunchRecord, "the stale branch-setup block must remain durable until real phase launch")
    // No phase is reported blocked once setup recovers.
    val status = requireNotNull(
      FeatureTaskRuntimeStatusService(
        harness.recorder,
        harness.runInvariantsStore,
        harness.decomposeTerminalRecorder,
      )
        .status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID)),
    )
    assertEquals(0, status.blockedCount)
  }

  @Test
  fun `later file-mutating phases reattach when a prior phase leaves HEAD on the default branch`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    val launcher = RuntimeRecordingLauncher { request ->
      if (request.invokedAgentId == phaseAgent("implement")) {
        git.currentBranchValue = "main"
      }
      facts(defaultPhaseOutput(request))
    }
    val harness = runnerHarness(
      launcher = launcher,
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(EXPECTED_FEATURE_BRANCH, completed.resolvedBranch)
    assertEquals(ALL_PHASES, harness.launchOrder())
    assertEquals(
      listOf(
        RecordingWorkflowGitOperations.CheckoutCall(EXPECTED_FEATURE_BRANCH, "main"),
        RecordingWorkflowGitOperations.CheckoutCall(EXPECTED_FEATURE_BRANCH, null),
      ),
      git.checkoutCalls,
      "review must reattach to the persisted branch after implement leaves HEAD on main",
    )
  }

  @Test
  fun `recordResolvedBranch is a non-overwriting no-op once a branch is persisted`() {
    val harness = runnerHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    val first = FeatureTaskRuntimeResolvedBranch(branch = "feat/first-branch", baseBranch = "main", created = true)
    val divergent =
      FeatureTaskRuntimeResolvedBranch(branch = "feat/divergent-branch", baseBranch = "develop", created = false)

    assertTrue(harness.recorder.recordResolvedBranch(WORKFLOW_ID, first))
    // A second record with divergent values must be a no-op that never overwrites the first, so a
    // resume/re-run can never force a second or divergent branch for the same run.
    assertTrue(harness.recorder.recordResolvedBranch(WORKFLOW_ID, divergent))

    val persisted = requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID))
    assertEquals("feat/first-branch", persisted.branch)
    assertEquals("main", persisted.baseBranch)
    assertEquals(true, persisted.created)
  }
}

// SKILL-85 Subtask 3 (AC2/3/4/5/6/8): reconcile-on-resume idempotency for the now-fix-loop mutating
// implement phase, exercised through a synthetic backward edge review --needs_fix--> implement.
class FeatureTaskRuntimeReconcileOnResumeTest {
  // (a) A mutating-phase re-run is a no-op when the tree matches target: the re-entered implement
  // returns the reconciliation report and the reconciliation gate passes, the clean worktree produces
  // no checkpoint commit, and the run converges without a duplicated mutation.
  @Test
  fun `mutating-phase re-run is a no-op when the tree matches target`() {
    var reviewLaunches = 0
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.worktreeStatusValue = "" // clean tree => no checkpoint commit
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewLaunches += 1
            facts(verdictReviewOutput(if (reviewLaunches == 1) "needs_fix" else "advance"))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    // implement launched twice (initial + one reconcile re-entry); each reconciled output passed.
    assertEquals(2, harness.launchedPhaseOrder().count { it == "implement" })
    // Clean tree => no checkpoint commit on the boundary.
    assertTrue(git.createCommitMessages.isEmpty(), "a clean tree must not produce a checkpoint commit")
  }

  // (c) The checkpoint boundary is established at the right point — after a verifier-passing iteration
  // and before the backward edge re-enters the mutating phase — and respects suppress_pr: a dirty tree
  // yields exactly one checkpoint commit on the resolved feature branch (no push), a clean tree yields
  // none.
  @Test
  fun `dirty tree checkpoints once before mutating re-entry on the resolved feature branch`() {
    var reviewLaunches = 0
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.worktreeStatusValue = " M src/Foo.kt" // dirty tree => one checkpoint commit on the boundary
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewLaunches += 1
            facts(verdictReviewOutput(if (reviewLaunches == 1) "needs_fix" else "advance"))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    // Exactly one checkpoint commit: fired on the single backward edge into implement.
    assertEquals(1, git.createCommitMessages.size, "a dirty tree must checkpoint exactly once before re-entry")
    assertContains(git.createCommitMessages.single(), "feat/existing-runtime-branch")
    assertContains(git.createCommitMessages.single(), "remediation checkpoint")
    // The checkpoint stages the full tree before committing: agents never `git add`, so without a
    // stage-all the bare commit would run against an empty index and fail (F-001).
    assertEquals(1, git.stageAllCalls, "the checkpoint must stage the full tree once before committing")
  }

  // F-001: a staging failure must block loudly rather than proceeding to a doomed empty-index commit.
  @Test
  fun `dirty tree checkpoint that fails to stage blocks loudly and never commits`() {
    var reviewLaunches = 0
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.worktreeStatusValue = " M src/Foo.kt"
    git.stageAllResult = WorkflowGitOperationResult(status = "error", error = "stage failed")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewLaunches += 1
            facts(verdictReviewOutput(if (reviewLaunches == 1) "needs_fix" else "advance"))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "stage failed")
    assertTrue(git.createCommitMessages.isEmpty(), "a failed staging must never proceed to a commit")
  }

  // F-002: the runner's protected-branch checkpoint guard is belt-and-suspenders — branch setup is the
  // upstream gatekeeper that blocks a protected resolved branch before any mutating phase launches, so
  // a dirty tree with a protected resolved branch produces no checkpoint commit (the boundary is never
  // reached). Seeding a protected persisted branch with HEAD already on it drives that state.
  @Test
  fun `dirty tree with a protected resolved branch never checkpoints`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    git.worktreeStatusValue = " M src/Foo.kt"
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch("main", baseBranch = "main", created = false)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertTrue(
      git.createCommitMessages.isEmpty(),
      "a protected resolved branch must never produce a checkpoint commit even on a dirty tree",
    )
  }

  // F-004: a suppress_pr goal-continuation run driven through the checkpoint boundary (dirty tree,
  // mutating re-entry) commits exactly one checkpoint and performs no push. The no-push property holds
  // by construction: WorkflowGitOperations exposes no push and the checkpoint path only stages +
  // commits, so honoring suppress_pr reduces to "the checkpoint is the single added durable boundary".
  @Test
  fun `suppress_pr goal-continuation checkpoints once and never pushes`() {
    var reviewLaunches = 0
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goalcont-checkpoint")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.worktreeStatusValue = " M src/Foo.kt"
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewLaunches += 1
            facts(verdictReviewOutput(if (reviewLaunches == 1) "needs_fix" else "advance"))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE)))
    assertEquals(
      1,
      git.createCommitMessages.size,
      "a suppress_pr goal-continuation must checkpoint exactly once before re-entry",
    )
  }

  // (c continued) A checkpoint is never created on the default branch: a non-mutating cycle (no
  // mutating phase re-entered) never reaches the checkpoint boundary even on a dirty tree.
  @Test
  fun `non-mutating cycle never checkpoints even on a dirty tree`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.worktreeStatusValue = " M src/Foo.kt"
    var planLaunches = 0
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "plan") {
          planLaunches += 1
          facts(verdictPlanOutput(if (planLaunches == 1) "needs_fix" else "advance"))
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request(PLAN_FIX_CYCLE)))
    assertTrue(git.createCommitMessages.isEmpty(), "a non-mutating cycle must never reach the checkpoint boundary")
  }

  // (d) The reconciliation gate rejects an implement output that did not report reconciliation: the
  // silent skip is routed through the loud schema-gate failure path, so the bounded implement fix loop
  // retries to its cap and then blocks. A reconciled output advances (proved in test (a)).
  @Test
  fun `reconciliation gate rejects an implement output without a reconciliation report`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement") facts(IMPLEMENT_NO_RECONCILE_OUTPUT) else facts(validJsonOutput(phaseId))
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "exhausted the bounded fix loop")
    assertContains(blocked.blockedReason, "reconciliation report")
    // The bounded fix loop retried to the cap before blocking (verified, not assumed).
    assertEquals(
      FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      harness.launchedPhaseOrder().count { it == "implement" },
    )
  }

  // (b) A simulated mid-implement crash then a clean resume reconciles to target without double-apply,
  // and WITHOUT resetting the per-edge cap counter: the surviving edge watermark means the resumed
  // re-entry mints the NEXT edge iteration, so across both runs the edge fires exactly 1..cap.
  @Test
  fun `crash mid-implement resume reconciles without resetting the per-edge cap`() {
    var implementLaunches = 0
    var crashOnReentry = true
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when {
          // The crash hits the FIRST re-entry (second implement launch), mid mutating phase.
          phaseId == "implement" && ++implementLaunches == 2 && crashOnReentry -> spawnFailedFacts()
          phaseId == "review" -> facts(verdictReviewOutput("needs_fix"))
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: implement -> review needs_fix fires the edge (iteration 1) -> implement re-enters & crashes.
    val firstReport = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))
    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("implement", firstBlocked.lastIncompletePhase)
    // The terminal blocked record retained the loop context — the watermark a reset would drop.
    val blockedImplement = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("blocked", blockedImplement.status)
    assertEquals("implement-fix", blockedImplement.loopId)
    assertEquals(1, blockedImplement.edgeIteration)

    // Run 2 (resume): the crash heals. The surviving watermark means the resume mints the NEXT edge
    // iteration (the cap), never restarting at 1, so across both runs the edge fires exactly 1..cap.
    crashOnReentry = false
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE)))
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals(
      (1..IMPLEMENT_FIX_CAP).toList(),
      edgeIterations,
      "the edge fired 1..cap across the crash, never restarting the per-edge counter",
    )
  }

  // (e) Regression guard: removing the implement exclusion does NOT regress same-phase schema-retry
  // bounds for NON-mutating phases. A non-fix-loop phase still blocks immediately on a schema-invalid
  // output without retrying.
  @Test
  fun `non-mutating non-fix-loop phase still blocks immediately on schema-invalid output`() {
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("write_history")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("write_history", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "does not participate in a fix loop")
    assertEquals(1, harness.launchedPhaseOrder().count { it == "write_history" })
  }
}

internal const val WORKFLOW_ID = "wftr-20260602-test-0001"
internal const val SESSION_ID = "ftr-test-001"
private const val ISSUE_KEY = "SKILL-65"
private const val SPEC_REFERENCE = ".feature-specs/SKILL-65/spec.md"

// A spec whose parent directory follows the `{ISSUE_KEY}-{feature-name}` convention so the
// derived feature branch is `feat/{ISSUE_KEY}-{feature-name}` (GoalRunnerTest-style assertion).
private const val CONVENTION_SPEC_REFERENCE =
  ".feature-specs/SKILL-65-runtime-feature-task-parity/spec_subtask_1.md"
private const val EXPECTED_FEATURE_BRANCH = "feat/SKILL-65-runtime-feature-task-parity"
internal const val INVOKED_AGENT = "claude-code"
internal const val VALID_OUTPUT = """{"contract_version":"0.1"}"""

// A clean review output carrying an empty findings array (the affirmative "no blocking findings"
// signal the review gate requires, SKILL-85 Subtask 4 F-003); used by the default phase-aware launcher.
private const val VALID_REVIEW_OUTPUT = """{"contract_version":"0.1","produced_outputs":{"findings":[]}}"""

// A clean audit output carrying an empty unmet_criteria array (the affirmative "every acceptance
// criterion is met" signal the audit gate requires, SKILL-85 Subtask 5 AC1); used by the default launcher.
private const val VALID_AUDIT_OUTPUT =
  """{"contract_version":"0.1","produced_outputs":{"unmet_criteria":[]}}"""
private const val PREPLAN_OUTPUT = """{"preplan_digest":"scope-boundaries-risks-rollout"}"""
private const val PLAN_OUTPUT = """{"plan":"do-the-thing"}"""
internal const val IMPLEMENT_OUTPUT = """{"implement":"done"}"""

internal val ALL_PHASES =
  listOf("preplan", "plan", "implement", "review", "audit", "validate", "write_history", "commit_push", "pr")
private val NON_FILE_MUTATING_PHASES = setOf("preplan", "plan")

// A distinct invoking agent per phase so a captured launch request is
// phase-attributable from its invokedAgentId.
internal fun phaseAgent(phaseId: String): String = "agent-$phaseId"

internal fun phasePerAgentAssignment(): FeatureTaskRuntimeAgentAssignment =
  FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = ALL_PHASES.associateWith(::phaseAgent))

// Bundles the persistence + git collaborators a harness exposes so the harness constructor stays
// within the parameter budget.
internal class RunnerHarnessIo(
  val workflow: RunnerHarnessWorkflow,
  val repository: InMemoryRuntimeWorkflowRepository,
  val gitOperations: RecordingWorkflowGitOperations,
  val specStatusWriter: RecordingSpecStatusWriter,
)

internal class RunnerHarnessWorkflow(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
)

internal class RunnerHarness(
  val launcher: RuntimeRecordingLauncher,
  val io: RunnerHarnessIo,
  val runner: FeatureTaskRuntimeRunner,
  val events: MutableList<FeatureTaskRuntimeRunEvent>,
  private val runRequest: FeatureTaskRuntimeRunRequest,
  val specScratchStore: RecordingSpecScratchStore,
) {
  val specStatusWriter: RecordingSpecStatusWriter get() = io.specStatusWriter
  val recorder: FeatureTaskRuntimePhaseRecorder get() = io.workflow.recorder
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder
    get() = io.workflow.goalContinuationRecorder
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder
    get() = io.workflow.decomposeTerminalRecorder
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore get() = io.workflow.runInvariantsStore
  val repository: InMemoryRuntimeWorkflowRepository get() = io.repository
  val gitOperations: RecordingWorkflowGitOperations get() = io.gitOperations

  // Launch order recovered from the event stream: each launch is preceded by a
  // PhaseStarted or a PhaseFixLoopIteration carrying the phase id.
  fun launchOrder(): List<String> = events.mapNotNull { event ->
    when (event) {
      is FeatureTaskRuntimeRunEvent.PhaseStarted -> event.phaseId
      is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration -> event.phaseId
      else -> null
    }
  }

  // Launch order derived from the launcher's captured requests; requires
  // phasePerAgentAssignment so each request's invokedAgentId maps back to its phase.
  fun launchedPhaseOrder(): List<String> = launcher.requests.map { request ->
    ALL_PHASES.firstOrNull { phaseId -> phaseAgent(phaseId) == request.invokedAgentId }
      ?: error("Launch request agent '${request.invokedAgentId}' is not phase-attributable.")
  }

  // Launch order parsed from each captured request's prompt header, so it covers phases outside
  // ALL_PHASES (e.g. the loop-only implement_fix) that launchedPhaseOrder cannot attribute by agent.
  fun launchedPromptPhaseOrder(): List<String> = launcher.requests.map { request ->
    phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
  }

  // Ensures the runtime workflow row exists first: the recorder write seam is a
  // no-op against a missing row.
  fun seedPhase(phaseId: String, status: String, attemptCount: Int, agentId: String, outputArtifact: String?) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseStateForTest(phaseId, status, attemptCount, agentId, outputArtifact)
  }

  fun seedReviewPhase(status: String, attemptCount: Int, outputArtifact: String?, reviewPassNumber: Int) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        status = status,
        attemptCount = attemptCount,
        resolvedAgentId = phaseAgent("review"),
        finished = status == "completed",
        outputArtifact = outputArtifact,
        reviewPassNumber = reviewPassNumber,
      ),
    )
  }

  // Seeds a foreign-mode (prose) row at WORKFLOW_ID for the runtime mode-collision path.
  fun seedProseModeWorkflow() {
    repository.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = WORKFLOW_ID,
        sessionId = SESSION_ID,
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = "running",
        currentStepId = "implement",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = skillbill.ports.persistence.model.FeatureTaskWorkflowMode.PROSE,
      ),
      skillbill.ports.persistence.model.FeatureTaskWorkflowMode.PROSE,
    )
  }

  // Seeds the durable run-scoped resolved branch, simulating a prior run that already established it.
  fun seedResolvedBranch(branch: String, baseBranch: String?, created: Boolean) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordResolvedBranch(
      WORKFLOW_ID,
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch(
        branch = branch,
        baseBranch = baseBranch,
        created = created,
      ),
    )
  }

  // Seeds a durable terminal blocked per-phase record (the marker that survives ledger pruning).
  fun seedBlockedPhase(phaseId: String, attemptCount: Int, agentId: String, blockedReason: String) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "blocked",
        attemptCount = attemptCount,
        resolvedAgentId = agentId,
        finished = false,
        outputArtifact = null,
        blockedReason = blockedReason,
      ),
    )
  }

  // Seeds a durable per-phase record carrying backward-edge loop context (loop id + per-edge
  // iteration), modelling a prior run that re-entered this phase through a backward edge.
  @Suppress("LongParameterList") // mirrors the full seeded per-phase record surface
  fun seedReentryPhase(
    phaseId: String,
    status: String,
    attemptCount: Int,
    agentId: String,
    outputArtifact: String?,
    loopId: String,
    edgeIteration: Int,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = status,
        attemptCount = attemptCount,
        resolvedAgentId = agentId,
        finished = status == "completed",
        outputArtifact = outputArtifact,
        loopId = loopId,
        edgeIteration = edgeIteration,
      ),
    )
  }

  fun seedLoopEdge(phaseId: String, loopId: String, edgeIteration: Int) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.appendLedgerEntry(
      skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = WORKFLOW_ID,
        action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
        phaseId = phaseId,
        attemptCount = edgeIteration,
        resolvedAgentId = INVOKED_AGENT,
        loopId = loopId,
        edgeIteration = edgeIteration,
      ),
    )
  }

  // Seeds a durable branch-setup-origin blocked record (keyed to the branch-setup sentinel agent),
  // modelling a prior run that blocked while establishing the feature branch for the phase.
  fun seedBranchSetupBlockedPhase(phaseId: String, blockedReason: String) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = BRANCH_SETUP_AGENT_ID,
        finished = false,
        outputArtifact = null,
        blockedReason = blockedReason,
      ),
    )
  }

  fun request(): FeatureTaskRuntimeRunRequest = runRequest

  // Drives the run with a synthetic cyclic transition declaration (the test-only inert seam).
  fun request(
    transitionsOverride: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration,
  ): FeatureTaskRuntimeRunRequest = runRequest.copy(transitionsOverride = transitionsOverride)
}

// Mirrors the runner's branch-setup sentinel agent id so tests can seed a branch-setup-origin block.
private const val BRANCH_SETUP_AGENT_ID = "branch-setup"

// Bundles the branch-setup-relevant test inputs so runnerHarness stays within the parameter budget.
internal data class BranchSetupTestConfig(
  val gitOperations: RecordingWorkflowGitOperations = RecordingWorkflowGitOperations(),
  val specReference: String = SPEC_REFERENCE,
  val featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
)

internal data class RuntimeHarnessConfig(
  val branchSetup: BranchSetupTestConfig = BranchSetupTestConfig(),
  val repoRoot: Path = Path.of("/tmp/repo"),
  val environment: Map<String, String> = emptyMap(),
  val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  val useRealDecompositionPlanner: Boolean = false,
  val eventSink: FeatureTaskRuntimeRunEventSink? = null,
  val dbPathOverride: String? = null,
)

private fun runtimeSpecSourceResolver(): SpecSourceResolver =
  SpecSourceResolver(TestDecompositionManifestFileStore, testDecompositionManifestValidator)

private fun runtimePhaseGates(
  branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  planningStopper: FeatureTaskRuntimePlanningStopper,
  lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  specGate: FeatureTaskRuntimeSpecGate = testSpecGate(),
): FeatureTaskRuntimePhaseGates = FeatureTaskRuntimePhaseGates(
  branchSetupRunner,
  planningStopper,
  lifecycleTelemetry,
  gitOperations,
  FeatureTaskRuntimeSpecStatusProjector(TestDecompositionManifestFileStore),
  specGate,
)

private fun testSpecGate(
  specScratchStore: SpecScratchStore = RecordingSpecScratchStore(),
  specStatusWriter: FeatureTaskRuntimeSpecStatusWriter = RecordingSpecStatusWriter(),
): FeatureTaskRuntimeSpecGate =
  FeatureTaskRuntimeSpecGate(runtimeSpecSourceResolver(), specScratchStore, specStatusWriter)

private fun disabledRuntimeLifecycleTelemetry(database: DatabaseSessionFactory): FeatureTaskRuntimeLifecycleTelemetry =
  FeatureTaskRuntimeLifecycleTelemetry(
    LifecycleTelemetryService(database, DisabledRuntimeTelemetrySettingsProvider),
  )

private object DisabledRuntimeTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = TelemetrySettings(
    configPath = Path.of("/fake/config.json"),
    level = "off",
    enabled = false,
    installId = "",
    proxyUrl = "",
    customProxyUrl = null,
    batchSize = 50,
  )
}

private fun smallRuntimeConfig(): RuntimeHarnessConfig = RuntimeHarnessConfig(
  branchSetup = BranchSetupTestConfig(featureSize = FeatureTaskRuntimeFeatureSize.SMALL),
)

private fun conventionRuntimeConfig(git: RecordingWorkflowGitOperations): RuntimeHarnessConfig =
  RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(git, CONVENTION_SPEC_REFERENCE))

private fun runnerHarnessRequest(
  runtimeConfig: RuntimeHarnessConfig,
  agentAssignment: FeatureTaskRuntimeAgentAssignment,
  sink: FeatureTaskRuntimeRunEventSink,
): FeatureTaskRuntimeRunRequest = FeatureTaskRuntimeRunRequest(
  issueKey = ISSUE_KEY,
  workflowId = WORKFLOW_ID,
  sessionId = SESSION_ID,
  runInvariants = FeatureTaskRuntimeRunInvariants(
    specReference = runtimeConfig.branchSetup.specReference,
    featureSize = runtimeConfig.branchSetup.featureSize,
    acceptanceCriteria = listOf("AC-1", "AC-2"),
    mandatesAndOverrides = listOf("mandate-X"),
  ),
  invokedAgentId = INVOKED_AGENT,
  agentAssignment = agentAssignment,
  environment = runtimeConfig.environment,
  dbPathOverride = null,
  repoRoot = runtimeConfig.repoRoot,
  goalContinuation = runtimeConfig.goalContinuation,
  eventSink = sink,
)

internal fun runnerHarness(
  launcher: RuntimeRecordingLauncher = defaultPhaseAwareLauncher(),
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
  specScratchStore: RecordingSpecScratchStore = RecordingSpecScratchStore(),
): RunnerHarness {
  val repository = InMemoryRuntimeWorkflowRepository()
  val database = RuntimeFakeDatabaseSessionFactory(repository)
  val recorder = FeatureTaskRuntimePhaseRecorder(database, NoopWorkflowSnapshotValidator)
  val goalContinuationRecorder = FeatureTaskRuntimeGoalContinuationRecorder(database, NoopWorkflowSnapshotValidator)
  val decomposeTerminalRecorder =
    FeatureTaskRuntimeDecomposeTerminalRecorder(database, NoopWorkflowSnapshotValidator)
  val runInvariantsStore = FeatureTaskRuntimeRunInvariantsStore(database, NoopWorkflowSnapshotValidator)
  val branchSetupRunner = FeatureTaskRuntimeBranchSetupRunner(recorder, runtimeConfig.branchSetup.gitOperations)
  val decompositionPlanner =
    if (runtimeConfig.useRealDecompositionPlanner) testDecompositionPlanner() else noOpDecompositionPlanner()
  val planningStopper = FeatureTaskRuntimePlanningStopper(validator, decompositionPlanner, decomposeTerminalRecorder)
  val specStatusWriter = RecordingSpecStatusWriter()
  val runner = FeatureTaskRuntimeRunner(
    launcher,
    recorder,
    goalContinuationRecorder,
    runInvariantsStore,
    validator,
    runtimePhaseGates(
      branchSetupRunner,
      planningStopper,
      disabledRuntimeLifecycleTelemetry(database),
      runtimeConfig.branchSetup.gitOperations,
      testSpecGate(specScratchStore, specStatusWriter),
    ),
  )
  // Always capture events; a caller-supplied sink is chained after the capture.
  val captured = mutableListOf<FeatureTaskRuntimeRunEvent>()
  val sink = FeatureTaskRuntimeRunEventSink { event ->
    captured += event
    runtimeConfig.eventSink?.emit(event)
  }
  val runRequest = runnerHarnessRequest(runtimeConfig, agentAssignment, sink)
  val io = RunnerHarnessIo(
    workflow = RunnerHarnessWorkflow(
      recorder,
      goalContinuationRecorder,
      decomposeTerminalRecorder,
      runInvariantsStore,
    ),
    repository = repository,
    gitOperations = runtimeConfig.branchSetup.gitOperations,
    specStatusWriter = specStatusWriter,
  )
  return RunnerHarness(launcher, io, runner, captured, runRequest, specScratchStore)
}

internal class TelemetryRunnerHarness(
  val runner: FeatureTaskRuntimeRunner,
  val lifecycle: RecordingLifecycleTelemetryRepository,
  val request: FeatureTaskRuntimeRunRequest,
  val database: RuntimeFakeDatabaseSessionFactory,
)

internal fun telemetryRunnerHarness(
  launcher: RuntimeRecordingLauncher = RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
): TelemetryRunnerHarness {
  val repository = InMemoryRuntimeWorkflowRepository()
  val lifecycle = RecordingLifecycleTelemetryRepository()
  val database = RuntimeFakeDatabaseSessionFactory(repository, lifecycle)
  val recorder = FeatureTaskRuntimePhaseRecorder(database, NoopWorkflowSnapshotValidator)
  val goalContinuationRecorder = FeatureTaskRuntimeGoalContinuationRecorder(database, NoopWorkflowSnapshotValidator)
  val decomposeTerminalRecorder =
    FeatureTaskRuntimeDecomposeTerminalRecorder(database, NoopWorkflowSnapshotValidator)
  val runInvariantsStore = FeatureTaskRuntimeRunInvariantsStore(database, NoopWorkflowSnapshotValidator)
  val branchSetupRunner = FeatureTaskRuntimeBranchSetupRunner(recorder, runtimeConfig.branchSetup.gitOperations)
  val decompositionPlanner = if (runtimeConfig.useRealDecompositionPlanner) {
    testDecompositionPlanner()
  } else {
    noOpDecompositionPlanner()
  }
  val planningStopper = FeatureTaskRuntimePlanningStopper(
    validator,
    decompositionPlanner,
    decomposeTerminalRecorder,
  )
  val runner = FeatureTaskRuntimeRunner(
    launcher,
    recorder,
    goalContinuationRecorder,
    runInvariantsStore,
    validator,
    runtimePhaseGates(
      branchSetupRunner,
      planningStopper,
      FeatureTaskRuntimeLifecycleTelemetry(
        LifecycleTelemetryService(database, EnabledRuntimeTelemetrySettingsProvider),
      ),
      runtimeConfig.branchSetup.gitOperations,
    ),
  )
  val request = FeatureTaskRuntimeRunRequest(
    issueKey = ISSUE_KEY,
    workflowId = WORKFLOW_ID,
    sessionId = SESSION_ID,
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = runtimeConfig.branchSetup.specReference,
      featureSize = runtimeConfig.branchSetup.featureSize,
      acceptanceCriteria = listOf("AC-1", "AC-2"),
      mandatesAndOverrides = listOf("mandate-X"),
    ),
    invokedAgentId = INVOKED_AGENT,
    dbPathOverride = runtimeConfig.dbPathOverride,
    repoRoot = runtimeConfig.repoRoot,
  )
  return TelemetryRunnerHarness(runner, lifecycle, request, database)
}

private fun noOpDecompositionPlanner(): FeatureTaskRuntimeDecompositionPlanner = FeatureTaskRuntimeDecompositionPlanner(
  preparationRuntime = FeatureSpecPreparationRuntime { intake ->
    FeatureSpecPreparationDecision(
      issueKey = intake.issueKey,
      intendedOutcome = intake.intendedOutcome,
      acceptanceCriteria = intake.acceptanceCriteria,
      constraints = intake.constraints,
      nonGoals = intake.nonGoals,
      mode = FeatureSpecPreparationMode.SINGLE_SPEC,
    )
  },
  preparationWriter = FeatureSpecPreparationWriter(
    decompositionManifestValidator = testDecompositionManifestValidator,
    fileStore = TestDecompositionManifestFileStore,
  ),
)

private fun testDecompositionPlanner(): FeatureTaskRuntimeDecompositionPlanner = FeatureTaskRuntimeDecompositionPlanner(
  preparationRuntime = FeatureSpecPreparationRuntime(),
  preparationWriter = FeatureSpecPreparationWriter(
    decompositionManifestValidator = testDecompositionManifestValidator,
    fileStore = TestDecompositionManifestFileStore,
  ),
)

internal fun facts(stdout: String): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = 0,
  stdout = stdout,
  stderr = "",
  timedOut = false,
  spawnFailed = false,
)

private val PHASE_LINE = Regex("^Phase: ([a-z_-]+) ", setOf(RegexOption.MULTILINE))

internal fun phaseIdFromPrompt(prompt: String): String =
  PHASE_LINE.find(prompt)?.groupValues?.get(1) ?: error("Prompt did not contain a phase header: $prompt")

// The default harness launcher returns a schema-valid, phase-attributed output per phase so a forward
// run completes. Phase-aware so the implement phase carries the reconciliation report the runtime's
// mutating-phase gate requires (SKILL-85 Subtask 3); every other phase carries its generic output.
internal fun defaultPhaseAwareLauncher(): RuntimeRecordingLauncher = RuntimeRecordingLauncher { request ->
  facts(defaultPhaseOutput(request))
}

// A schema-valid output for the phase the prompt names. Mutating phases (implement) carry the
// reconciliation report the runtime gate now requires; every other phase keeps the minimal
// VALID_OUTPUT so existing recorded-artifact equality assertions are unchanged.
internal fun defaultPhaseOutput(request: GoalRunnerSubtaskLaunchRequest): String {
  val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
  return when {
    FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId) -> validJsonOutput(phaseId)
    // A clean review must emit a verification signal (an empty findings array affirms no blocking
    // findings) or the review gate blocks (SKILL-85 Subtask 4 F-003).
    phaseId == "review" -> VALID_REVIEW_OUTPUT
    // A clean audit must likewise emit a verification signal (an empty unmet_criteria array affirms
    // every acceptance criterion is met) or the audit gate blocks (SKILL-85 Subtask 5 AC1).
    phaseId == "audit" -> VALID_AUDIT_OUTPUT
    else -> VALID_OUTPUT
  }
}

// Subtask 2: a synthetic non-mutating cycle over [preplan, plan] with a backward edge
// plan --needs_fix--> preplan, bounded by PLAN_FIX_CAP re-entries. Both phases are non-file-mutating
// so the cycle exercises the executor without entering branch setup.
private const val PLAN_FIX_CAP = 2

private val PLAN_FIX_CYCLE = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
  forwardPhaseIds = listOf("preplan", "plan"),
  backwardEdges = listOf(
    skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "plan",
      triggeringVerdict = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict("needs_fix"),
      destinationPhaseId = "preplan",
      loopId = "plan-fix",
      perEdgeCap = PLAN_FIX_CAP,
    ),
  ),
)

// SKILL-85 Subtask 3: a synthetic cycle whose backward edge re-enters the MUTATING implement phase.
// review --needs_fix--> implement, bounded by IMPLEMENT_FIX_CAP. The reopened span [implement, review]
// contains a mutating phase, so the remediation checkpoint boundary fires before re-entry.
private const val IMPLEMENT_FIX_CAP = 2

private val IMPLEMENT_FIX_CYCLE = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
  forwardPhaseIds = listOf("preplan", "plan", "implement", "review"),
  backwardEdges = listOf(
    skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "review",
      triggeringVerdict = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict("needs_fix"),
      destinationPhaseId = "implement",
      loopId = "implement-fix",
      perEdgeCap = IMPLEMENT_FIX_CAP,
    ),
  ),
)

// A schema-valid review output carrying a top-level `verdict` wire string the transition function reads.
internal fun verdictReviewOutput(verdict: String): String = """
  {
    "contract_version": "0.1",
    "phase_id": "review",
    "status": "completed",
    "summary": "Review produced a validated output.",
    "verdict": "$verdict",
    "produced_outputs": {"findings": []}
  }
""".trimIndent()

// SKILL-85 Subtask 4 (F-003): a completed review output carrying NEITHER a top-level verdict NOR a
// produced_outputs.findings array — prose only. The review gate must block on the missing signal.
private val REVIEW_NO_SIGNAL_OUTPUT: String = """
  {
    "contract_version": "0.1",
    "phase_id": "review",
    "status": "completed",
    "summary": "Looks good to me, shipping.",
    "produced_outputs": {"notes": "no concerns"}
  }
""".trimIndent()

// SKILL-85 Subtask 4: the unique Blocker-finding message a changes_requested review carries, so a fix
// briefing and a cap-exhaustion block can be asserted to contain it.
internal const val REVIEW_BLOCKER_MESSAGE = "Foo.kt leaks a connection in the error path"

// A schema-valid review output whose findings drive the verdict: a Blocker finding => changes_requested
// (the runtime classifies from findings, no top-level verdict needed), an empty findings list => the
// run advances. The findings ride inside produced_outputs the way the runner reads them.
internal fun reviewFindingsOutput(changesRequested: Boolean): String {
  val findings = if (changesRequested) {
    """{"severity": "blocker", "message": "$REVIEW_BLOCKER_MESSAGE"}"""
  } else {
    ""
  }
  return """
    {
      "contract_version": "0.1",
      "phase_id": "review",
      "status": "completed",
      "summary": "Review produced a validated output.",
      "produced_outputs": {"findings": [$findings]}
    }
  """.trimIndent()
}

// The real M1 review_fix launcher: review returns changes_requested findings until [convergeOnReview]
// (1-based review launch index at which it first approves); a value above the cap never converges.
// implement_fix and every other phase return their schema-valid reconciled output.
private fun reviewFixLauncher(convergeOnReview: Int, onReviewLaunch: (Int) -> Unit = {}): RuntimeRecordingLauncher {
  var reviewLaunches = 0
  return RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "review") {
      reviewLaunches += 1
      onReviewLaunch(reviewLaunches)
      facts(reviewFindingsOutput(changesRequested = reviewLaunches < convergeOnReview))
    } else {
      facts(validJsonOutput(phaseId))
    }
  }
}

// An implement output that completes WITHOUT the reconciliation report, so the runtime's
// mutating-phase reconciliation gate must reject it (silent skip fails the gate loudly).
private val IMPLEMENT_NO_RECONCILE_OUTPUT: String = """
  {
    "contract_version": "0.1",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": {"changed_files": ["src/Foo.kt"]}
  }
""".trimIndent()

// A schema-valid plan output carrying a top-level `verdict` wire string the transition function reads.
private fun verdictPlanOutput(verdict: String): String = """
  {
    "contract_version": "0.1",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan produced a validated output.",
    "verdict": "$verdict",
    "produced_outputs": {"tasks": ["task-1"]}
  }
""".trimIndent()

internal fun validJsonOutput(phaseId: String): String = """
  {
    "contract_version": "0.1",
    "phase_id": "$phaseId",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": ${validProducedOutputs(phaseId)}
  }
""".trimIndent()

internal fun validProducedOutputs(phaseId: String): String = when (phaseId) {
  "commit_push" -> """{"commit_push_result": {"commit_sha": "commit-runtime-1"}}"""
  // Mutating phases must carry the reconciliation report or the runtime's reconciliation gate
  // rejects the output (SKILL-85 Subtask 3). implement_fix is mutating too (SKILL-85 Subtask 4).
  "implement", "implement_fix" -> """{"changed_files": ["src/Foo.kt"], "reconciled_state": {"reconciled": true}}"""
  // A clean review must emit a verification signal or the review gate blocks (SKILL-85 Subtask 4):
  // an explicit empty findings array affirms "no blocking findings" and advances.
  "review" -> """{"findings": []}"""
  // A clean audit must emit a verification signal or the audit gate blocks (SKILL-85 Subtask 5):
  // an explicit empty unmet_criteria array affirms "every acceptance criterion is met" and advances.
  "audit" -> """{"unmet_criteria": []}"""
  else -> """{"tasks": ["task-1"]}"""
}

// A commit_push phase output that completes without emitting a commit_sha, modelling the
// goal-continuation SHA-drop the SKILL-68 capture-at-source path must recover from.
private val COMMIT_PUSH_NO_SHA_OUTPUT: String = """
  {
    "contract_version": "0.1",
    "phase_id": "commit_push",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": {"commit_push_result": {"status": "committed"}}
  }
""".trimIndent()

private val COMMIT_PUSH_BLOCKED_OUTPUT: String = """
  {
    "contract_version": "0.1",
    "phase_id": "commit_push",
    "status": "blocked",
    "summary": "Validation failed before commit.",
    "produced_outputs": {
      "commit_push_result": {
        "commit_sha": null,
        "pushed_status": "not_attempted"
      },
      "blocking_reasons": ["Working tree contains unrelated changes."]
    }
  }
""".trimIndent()

private val VALIDATE_BLOCKED_OUTPUT: String = """
  {
    "contract_version": "0.1",
    "phase_id": "validate",
    "status": "blocked",
    "summary": "Validation failed before finalization.",
    "produced_outputs": {
      "validation_result": "fail",
      "blocking_reasons": ["Repository validation still fails."]
    }
  }
""".trimIndent()

// Launcher for a suppress_pr goal-continuation run: every phase returns a valid output, with the
// commit_push phase returning the supplied payload so a test can vary whether a SHA is present.
private fun goalContinuationLauncher(commitPushOutput: String): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    facts(if (phaseId == "commit_push") commitPushOutput else validJsonOutput(phaseId))
  }

// Builds a suppress_pr goal-continuation harness with a caller-supplied git fake and launcher so a
// test can seed the measurable/blank HEAD and the commit_push payload independently.
private fun goalContinuationHarness(
  repoRoot: Path,
  git: RecordingWorkflowGitOperations,
  launcher: RuntimeRecordingLauncher,
): RunnerHarness = runnerHarness(
  launcher = launcher,
  agentAssignment = phasePerAgentAssignment(),
  runtimeConfig = RuntimeHarnessConfig(
    branchSetup = BranchSetupTestConfig(gitOperations = git),
    repoRoot = repoRoot,
    goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = ISSUE_KEY,
      subtaskId = 5,
      goalBranch = "feat/existing-runtime-branch",
      suppressPr = true,
      parentWorkflowId = "wfl-parent",
      reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
    ),
    useRealDecompositionPlanner = true,
  ),
)

private val DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.1",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "mode": "decompose",
      "reason": "Plan needs ordered subtasks.",
      "feature_name": "runtime decomposition parity",
      "parent_spec_overview": "Split the runtime work into ordered subtasks.",
      "validation_strategy": "bill-code-check",
      "base_branch": "main",
      "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
      "subtasks": [
        {
          "id": 1,
          "name": "domain contracts",
          "scope": "Add typed plan outcome detection.",
          "acceptance_criteria": ["Detect decompose mode."],
          "non_goals": [],
          "dependency_notes": "First subtask.",
          "validation_strategy": "unit tests",
          "next_path": "Work subtask 2 next.",
          "depends_on": []
        },
        {
          "id": 2,
          "name": "runtime stop",
          "scope": "Stop after writing decomposition.",
          "acceptance_criteria": ["Do not advance to implement."],
          "non_goals": [],
          "dependency_notes": "Depends on subtask 1.",
          "validation_strategy": "unit tests",
          "next_path": "Return to the parent workflow.",
          "depends_on": [1]
        }
      ]
    }
  }
""".trimIndent()

// A plan envelope that declares mode=decompose but emits a malformed package: the second subtask
// is missing its required `name`, so the typed projection loud-fails at parse. The runtime must
// turn this into a Blocked outcome rather than letting the exception escape run().
private val MALFORMED_DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.1",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "mode": "decompose",
      "reason": "Plan needs ordered subtasks.",
      "feature_name": "runtime decomposition parity",
      "parent_spec_overview": "Split the runtime work into ordered subtasks.",
      "validation_strategy": "bill-code-check",
      "base_branch": "main",
      "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
      "subtasks": [
        {
          "id": 1,
          "name": "domain contracts",
          "scope": "Add typed plan outcome detection.",
          "acceptance_criteria": ["Detect decompose mode."],
          "non_goals": [],
          "dependency_notes": "First subtask.",
          "validation_strategy": "unit tests",
          "next_path": "Work subtask 2 next.",
          "depends_on": []
        },
        {
          "id": 2,
          "scope": "Stop after writing decomposition.",
          "acceptance_criteria": ["Do not advance to implement."],
          "non_goals": [],
          "dependency_notes": "Depends on subtask 1.",
          "validation_strategy": "unit tests",
          "next_path": "Return to the parent workflow.",
          "depends_on": [1]
        }
      ]
    }
  }
""".trimIndent()

// A plan envelope that declares mode=decompose with a DECODER-VALID package (every subtask carries
// all required fields with correct types) but is WRITER-INVALID: the subtask ids descend [2, 1], so
// the typed decoder accepts it while FeatureSpecPreparationWriter.validateDecomposedSubtasks rejects
// the non-ascending order with InvalidFeatureSpecPreparationRequestError. The runtime must turn this
// writer business-rule rejection into a Blocked outcome rather than letting it escape run().
private val WRITER_INVALID_DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.1",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "mode": "decompose",
      "reason": "Plan needs ordered subtasks.",
      "feature_name": "runtime decomposition parity",
      "parent_spec_overview": "Split the runtime work into ordered subtasks.",
      "validation_strategy": "bill-code-check",
      "base_branch": "main",
      "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
      "subtasks": [
        {
          "id": 2,
          "name": "runtime stop",
          "scope": "Stop after writing decomposition.",
          "acceptance_criteria": ["Do not advance to implement."],
          "non_goals": [],
          "dependency_notes": "Listed first but ids descend.",
          "validation_strategy": "unit tests",
          "next_path": "Return to the parent workflow.",
          "depends_on": []
        },
        {
          "id": 1,
          "name": "domain contracts",
          "scope": "Add typed plan outcome detection.",
          "acceptance_criteria": ["Detect decompose mode."],
          "non_goals": [],
          "dependency_notes": "Listed second; out of ascending order.",
          "validation_strategy": "unit tests",
          "next_path": "Work subtask 2 next.",
          "depends_on": []
        }
      ]
    }
  }
""".trimIndent()

// An infrastructure spawn failure (no exit status, empty stdout).
internal fun spawnFailedFacts(): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = null,
  stdout = "",
  stderr = "spawn failed",
  timedOut = false,
  spawnFailed = true,
)

// An infrastructure timeout (no exit status, partial/empty stdout).
private fun timedOutFacts(): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = null,
  stdout = "",
  stderr = "timed out",
  timedOut = true,
  spawnFailed = false,
)

internal class RuntimeRecordingLauncher(
  private val handler: (GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
) : GoalRunnerSubtaskLauncher {
  val requests = mutableListOf<GoalRunnerSubtaskLaunchRequest>()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return handler(request)
  }
}

// A schema validator that rejects only the named phases.
private class ThrowingValidator(private val failPhases: Set<String>) : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    if (sourceLabel in failPhases) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "rejected by fake validator")
    }
  }
}

internal object AlwaysValidValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) = Unit
}

// Records every checkout, with configurable currentBranch/checkoutBranch results. The default
// currentBranch reports an already-feature branch so existing tests never enter the create path;
// branch-setup tests override these to drive starts-on-default / cannot-establish / resume cases.
internal class RecordingWorkflowGitOperations(
  var currentBranchValue: String = "feat/existing-runtime-branch",
  var currentBranchResult: WorkflowGitOperationResult? = null,
  var checkoutResult: WorkflowGitOperationResult? = null,
  // When set, a successful checkout updates the working-tree branch the next currentBranch read
  // reports, modelling git HEAD actually moving so the runner's post-checkout re-confirmation sees
  // the landed branch. Defaults to the checkout target.
  var landedBranchAfterCheckout: String? = null,
  // Branch names the repository reports as existing. null models every queried branch as existing
  // (the common case for re-attach tests); a concrete set models a repository where the persisted
  // branch may have been deleted. branchExistsResult overrides with a raw result for error cases.
  var existingBranches: Set<String>? = null,
  var branchExistsResult: WorkflowGitOperationResult? = null,
) : WorkflowGitOperations,
  GoalSubtaskReviewGitOperationsProvider,
  RuntimePhaseFileManifestGitOperationsProvider {
  // Seeded git HEAD for the SKILL-68 capture-at-source fallback: blank models an unmeasurable HEAD;
  // a concrete value models a measurable commit. headCommitShaResult overrides with a raw result.
  var headCommitShaValue: String = ""
  var headCommitShaResult: WorkflowGitOperationResult? = null
  val runtimePhaseHeadCommitSequence = ArrayDeque<String>()
  var changedPathsBetweenCommitsValue: String = ""

  // Models the working-tree cleanliness the remediation-checkpoint boundary reads: blank => clean
  // (no checkpoint commit), non-blank => dirty (a checkpoint commit is created). worktreeStatusResult
  // overrides with a raw result to model an unreadable worktree.
  var worktreeStatusValue: String = ""
  var worktreeStatusResult: WorkflowGitOperationResult? = null
  val worktreeStatusSequence = ArrayDeque<String>()

  // Records every remediation-checkpoint commit message; createCommitResult overrides the result to
  // model a failed checkpoint commit.
  val createCommitMessages = mutableListOf<String>()
  var createCommitResult: WorkflowGitOperationResult? = null

  // Counts stage-all calls (the checkpoint stages the full tree before committing); stageAllResult
  // overrides the result to model a failed staging.
  var stageAllCalls: Int = 0
  var stageAllResult: WorkflowGitOperationResult? = null
  val goalReviewBuildInputs = mutableListOf<GoalSubtaskReviewBaseline>()
  val goalReviewBuildResults = ArrayDeque<GoalSubtaskReviewInputResult>()
  var goalReviewRecoveredBaseline: GoalSubtaskReviewBaseline? = null
  var goalReviewRecoverCalls: Int = 0

  data class CheckoutCall(val branch: String, val baseBranch: String?)

  val checkoutCalls = mutableListOf<CheckoutCall>()
  val branchExistsCalls = mutableListOf<String>()
  var currentBranchCalls: Int = 0

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    checkoutCalls += CheckoutCall(branch, baseBranch)
    val result = checkoutResult ?: WorkflowGitOperationResult(status = "ok", value = branch)
    if (result.ok) {
      currentBranchValue = landedBranchAfterCheckout ?: branch
    }
    return result
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    branchExistsCalls += branch
    branchExistsResult?.let { return it }
    val exists = existingBranches?.contains(branch.trim()) ?: true
    return WorkflowGitOperationResult(status = "ok", value = exists.toString())
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult {
    currentBranchCalls++
    return currentBranchResult ?: WorkflowGitOperationResult(status = "ok", value = currentBranchValue)
  }

  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult {
    stageAllCalls++
    return stageAllResult ?: WorkflowGitOperationResult(status = "ok", value = "")
  }

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    createCommitMessages += message
    return createCommitResult ?: WorkflowGitOperationResult(status = "ok", value = "checkpoint-sha")
  }

  var headCommitShaCalls: Int = 0

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult {
    headCommitShaCalls++
    return headCommitShaResult ?: WorkflowGitOperationResult(status = "ok", value = headCommitShaValue)
  }

  override val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations =
    object : RuntimePhaseFileManifestGitOperations {
      override fun headCommit(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult(
        status = "ok",
        value = runtimePhaseHeadCommitSequence.removeFirstOrNull().orEmpty(),
      )

      override fun changedPathsBetweenCommits(
        repoRoot: Path,
        beforeCommit: String,
        afterCommit: String,
      ): WorkflowGitOperationResult = WorkflowGitOperationResult(
        status = "ok",
        value = changedPathsBetweenCommitsValue,
      )
    }

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    worktreeStatusResult ?: WorkflowGitOperationResult(
      status = "ok",
      value = worktreeStatusSequence.removeFirstOrNull() ?: worktreeStatusValue,
    )

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult = WorkflowWorktreeActivityResult(
    status = "ok",
    changedFileSummary = GoalObservabilityChangedFileSummary(
      total = 0,
      added = 0,
      modified = 0,
      deleted = 0,
      renamed = 0,
      untracked = 0,
    ),
    diffStat = GoalObservabilityDiffStat(filesChanged = 0, insertions = 0, deletions = 0),
  )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(
    status = "ok",
    selectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  )

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations =
    object : GoalSubtaskReviewGitOperations {
      override fun captureBaseline(repoRoot: Path, expectedBranch: String) =
        error("Goal review baseline capture is not used by this runtime runner fixture.")

      override fun buildInput(
        repoRoot: Path,
        baseline: GoalSubtaskReviewBaseline,
        expectedBranch: String,
      ): GoalSubtaskReviewInputResult {
        goalReviewBuildInputs += baseline
        return goalReviewBuildResults.removeFirstOrNull() ?: GoalSubtaskReviewInputResult(
          status = "ok",
          input = GoalSubtaskReviewInput(
            reviewBaseSha = baseline.reviewBaseSha,
            currentHeadSha = baseline.reviewBaseSha,
            trackedDelta = "",
            ownedUntrackedPatches = "",
          ),
        )
      }

      override fun recoverBaseline(
        repoRoot: Path,
        baseline: GoalSubtaskReviewBaseline,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult {
        goalReviewRecoverCalls++
        return goalReviewRecoveredBaseline?.let { GoalSubtaskReviewBaselineResult(status = "ok", baseline = it) }
          ?: GoalSubtaskReviewBaselineResult(status = "error", error = "no recovered baseline configured")
      }
    }
}

// The runner only drives openRecord/updateRecord (no snapshotView casts), so a
// no-op snapshot validator is sufficient here.
private object NoopWorkflowSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
}

private fun FeatureTaskRuntimePhaseRecorder.recordPhaseStateForTest(
  phaseId: String,
  status: String,
  attemptCount: Int,
  resolvedAgentId: String,
  outputArtifact: String?,
): Boolean = recordPhaseState(
  skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
    workflowId = WORKFLOW_ID,
    phaseId = phaseId,
    status = status,
    attemptCount = attemptCount,
    resolvedAgentId = resolvedAgentId,
    finished = status == "completed",
    outputArtifact = outputArtifact,
  ),
)

internal class RuntimeFakeDatabaseSessionFactory(
  private val repository: InMemoryRuntimeWorkflowRepository,
  private val lifecycle: LifecycleTelemetryRepository? = null,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/metrics.db")
  val transactionDbOverrides = mutableListOf<String?>()

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T {
    transactionDbOverrides += dbOverride
    return block(unitOfWork())
  }

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@RuntimeFakeDatabaseSessionFactory.dbPath
    override val reviews: ReviewRepository get() = error("unused")
    override val learnings: LearningRepository get() = error("unused")
    override val lifecycleTelemetry: LifecycleTelemetryRepository
      get() = lifecycle ?: error("unused")
    override val telemetryReconciliation: TelemetryReconciliationRepository get() = error("unused")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused")
    override val workflowStates: WorkflowStateRepository = repository
    override val workList = skillbill.ports.persistence.EmptyWorkListRepository
  }
}

private object EnabledRuntimeTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = TelemetrySettings(
    configPath = Path.of("/fake/config.json"),
    level = "full",
    enabled = true,
    installId = "install-1",
    proxyUrl = "",
    customProxyUrl = null,
    batchSize = 50,
  )
}

@Suppress("TooManyFunctions") // mirrors the full LifecycleTelemetryRepository contract
internal class RecordingLifecycleTelemetryRepository : LifecycleTelemetryRepository {
  val startedRecords = mutableListOf<FeatureTaskRuntimeStartedRecord>()
  val finishedRecords = mutableListOf<FeatureTaskRuntimeFinishedRecord>()

  override fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String) {
    startedRecords += record
  }

  override fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String) {
    finishedRecords += record
  }

  override fun featureImplementStarted(
    record: skillbill.telemetry.model.FeatureImplementStartedRecord,
    level: String,
  ) = error("unused")

  override fun featureImplementFinished(
    record: skillbill.telemetry.model.FeatureImplementFinishedRecord,
    level: String,
  ) = error("unused")

  override fun qualityCheckStarted(record: skillbill.telemetry.model.QualityCheckStartedRecord, level: String) =
    error("unused")

  override fun qualityCheckFinished(record: skillbill.telemetry.model.QualityCheckFinishedRecord, level: String) =
    error("unused")

  override fun featureVerifyStarted(record: skillbill.telemetry.model.FeatureVerifyStartedRecord, level: String) =
    error("unused")

  override fun featureVerifyFinished(record: skillbill.telemetry.model.FeatureVerifyFinishedRecord, level: String) =
    error("unused")

  override fun prDescriptionGenerated(record: skillbill.telemetry.model.PrDescriptionGeneratedRecord, level: String) =
    error("unused")

  override fun goalStarted(record: skillbill.telemetry.model.GoalStartedRecord, level: String) = error("unused")

  override fun goalSubtaskFinished(record: skillbill.telemetry.model.GoalSubtaskFinishedRecord, level: String) =
    error("unused")

  override fun goalFinished(record: skillbill.telemetry.model.GoalFinishedRecord, level: String) = error("unused")

  override fun goalIssueFinished(record: skillbill.telemetry.model.GoalIssueFinishedRecord, level: String) =
    error("unused")
}

private fun FeatureTaskRuntimeWorkerOwnership.matchesActiveOwnership(
  workflowId: String,
  ownerToken: String,
  generation: Long,
): Boolean = this.workflowId == workflowId &&
  this.ownerToken == ownerToken &&
  this.generation == generation &&
  leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE

internal class InMemoryRuntimeWorkflowRepository : WorkflowStateRepository {
  private var workerOwnership: FeatureTaskRuntimeWorkerOwnership? = null

  fun seedWorkerOwnership(ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership) {
    workerOwnership = ownership
  }

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String) =
    synchronized(this) { workerOwnership?.takeIf { it.workflowId == workflowId } }

  override fun acquireFeatureTaskRuntimeWorker(
    ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean = synchronized(this) {
    if (workerOwnership != null || taskRuntimeRows[ownership.workflowId]?.updatedAt != expectedUpdatedAt) return false
    workerOwnership = ownership
    true
  }

  override fun reserveFeatureTaskRuntimeWorkerTakeover(
    workflowId: String,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = synchronized(this) {
    val current = workerOwnership ?: return false
    if (!current.matchesActiveOwnership(workflowId, expectedOwnerToken, expectedGeneration)) return false
    workerOwnership = current.copy(
      leaseState = skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED,
    )
    true
  }

  override fun transferFeatureTaskRuntimeWorker(
    ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = synchronized(this) {
    val current = workerOwnership ?: return false
    if (
      current.ownerToken != expectedOwnerToken || current.generation != expectedGeneration ||
      current.leaseState != skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED
    ) {
      return false
    }
    workerOwnership = ownership
    true
  }

  override fun heartbeatFeatureTaskRuntimeWorker(
    ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership,
  ): Boolean = synchronized(this) {
    val current = workerOwnership ?: return false
    if (current.ownerToken != ownership.ownerToken || current.generation != ownership.generation) return false
    workerOwnership = ownership
    true
  }

  override fun releaseFeatureTaskRuntimeWorker(workflowId: String, ownerToken: String, generation: Long): Boolean =
    synchronized(this) {
      val current = workerOwnership ?: return false
      if (current.workflowId != workflowId || current.ownerToken != ownerToken || current.generation != generation) {
        return false
      }
      workerOwnership = null
      true
    }
  override fun saveFeatureTaskExecutionIdentity(
    identity: skillbill.ports.persistence.model.FeatureTaskExecutionIdentity,
  ) = Unit

  override fun findStandaloneFeatureTaskCandidates(normalizedIssueKey: String, repositoryIdentity: String) =
    emptyList<skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate>()

  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()

  // Prose feature-task rows route through the legacy prose family store (see WorkflowStateRepository).
  private val implementRows = linkedMapOf<String, WorkflowStateRecord>()

  fun taskRuntimeArtifacts(workflowId: String): Map<String, Any?> {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    return skillbill.contracts.JsonSupport.parseObjectOrNull(record.artifactsJson)
      ?.let(skillbill.contracts.JsonSupport::jsonElementToValue)
      ?.let(skillbill.contracts.JsonSupport::anyToStringAnyMap)
      .orEmpty()
  }

  // Overwrites the per-phase records key with a present-but-non-map blob to
  // simulate corrupt durable state.
  fun corruptRecordsArtifact(workflowId: String, corruptValue: Any?) {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    val artifacts = LinkedHashMap(taskRuntimeArtifacts(workflowId)).apply {
      put(FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY, corruptValue)
    }
    taskRuntimeRows[workflowId] = record.copy(
      artifactsJson = skillbill.contracts.JsonSupport.mapToJsonString(artifacts),
    )
  }

  fun replaceTaskRuntimeArtifacts(workflowId: String, artifacts: Map<String, Any?>) {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    taskRuntimeRows[workflowId] = record.copy(
      artifactsJson = skillbill.contracts.JsonSupport.mapToJsonString(artifacts),
    )
  }

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    implementRows[row.workflowId] = row
  }

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = implementRows[workflowId]

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = null

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = null

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null
}

class InfraFailureReasonStderrSurfacingTest {
  @Test
  fun `non-zero exit with non-blank stderr surfaces a bounded stderr excerpt in blocked reason`() {
    val stderrContent = "Error: something went wrong with the child process"
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CLAUDE,
          exitStatus = 1,
          stdout = "",
          stderr = stderrContent,
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "exited with non-zero status 1")
    assertContains(blocked.blockedReason, stderrContent)
  }

  @Test
  fun `non-zero exit with blank stderr and non-blank stdout surfaces stdout excerpt in blocked reason`() {
    val stdoutContent = "unexpected non-json output from the child"
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CLAUDE,
          exitStatus = 2,
          stdout = stdoutContent,
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "exited with non-zero status 2")
    assertContains(blocked.blockedReason, stdoutContent)
  }

  @Test
  fun `non-zero exit with both blank stderr and stdout does not append an excerpt to blocked reason`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CLAUDE,
          exitStatus = 1,
          stdout = "",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "exited with non-zero status 1")
    assertFalse(
      blocked.blockedReason.contains("\n"),
      "reason must not contain a newline when no output is available: '${blocked.blockedReason}'",
    )
  }
}
