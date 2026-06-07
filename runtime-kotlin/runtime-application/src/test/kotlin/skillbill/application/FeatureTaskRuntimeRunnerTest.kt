package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
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
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    assertTrue(finished.blockedReason.isNotBlank())
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
  }

  @Test
  fun `runtime finished error emits even when phase outcome loading fails`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), lifecycle)
    val telemetry = FeatureTaskRuntimeLifecycleTelemetry(
      LifecycleTelemetryService(database, EnabledRuntimeTelemetrySettingsProvider),
    )

    telemetry.finishedError(SESSION_ID, phaseOutcomes = { error("phase load failed") }, dbOverride = null)

    val finished = lifecycle.finishedRecords.single()
    assertEquals(SESSION_ID, finished.sessionId)
    assertEquals("error", finished.completionStatus)
    assertEquals(emptyMap(), finished.phaseOutcomes)
    assertEquals(emptyList(), finished.completedPhaseIds)
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
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("implement")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "does not participate in a fix loop")
    assertEquals(listOf("preplan", "plan"), blocked.completedPhaseIds)
    assertEquals(listOf("preplan", "plan", "implement"), harness.launchedPhaseOrder())
    assertEquals(listOf("preplan", "plan", "implement"), harness.launchOrder())
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
    assertEquals(VALID_OUTPUT, auditBriefing.upstreamOutputsByPhaseId["review"])
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
  fun `resume of a phase with a durable blocked record re-blocks without relaunching`() {
    // F-002: a phase persisted with a terminal blocked record (the durable marker that survives
    // ledger pruning) re-blocks on resume without launching the agent again.
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedBlockedPhase("implement", attemptCount = 1, phaseAgent("implement"), "implement gate failed")

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
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
    assertEquals(VALID_OUTPUT, briefings.getValue("review").upstreamOutputsByPhaseId["implement"])
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
        if (request.invokedAgentId == phaseAgent("review")) timedOutFacts() else facts(VALID_OUTPUT)
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
        "parent_workflow_id" to "wfl-parent",
      ),
      artifacts["goal_continuation"],
    )
    val outcome = artifacts["goal_continuation_outcome"] as Map<*, *>
    assertEquals("complete", outcome["status"])
    assertEquals("commit-runtime-1", outcome["commit_sha"])
    assertEquals("commit_push", outcome["last_resumable_step"])
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
        facts(VALID_OUTPUT)
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
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertContains(Files.readString(specPath), "status: Pending")
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
      facts(VALID_OUTPUT)
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

private const val WORKFLOW_ID = "wftr-20260602-test-0001"
private const val SESSION_ID = "ftr-test-001"
private const val ISSUE_KEY = "SKILL-65"
private const val SPEC_REFERENCE = ".feature-specs/SKILL-65/spec.md"

// A spec whose parent directory follows the `{ISSUE_KEY}-{feature-name}` convention so the
// derived feature branch is `feat/{ISSUE_KEY}-{feature-name}` (GoalRunnerTest-style assertion).
private const val CONVENTION_SPEC_REFERENCE =
  ".feature-specs/SKILL-65-runtime-feature-task-parity/spec_subtask_1.md"
private const val EXPECTED_FEATURE_BRANCH = "feat/SKILL-65-runtime-feature-task-parity"
private const val INVOKED_AGENT = "claude-code"
private const val VALID_OUTPUT = """{"contract_version":"0.1"}"""
private const val PREPLAN_OUTPUT = """{"preplan_digest":"scope-boundaries-risks-rollout"}"""
private const val PLAN_OUTPUT = """{"plan":"do-the-thing"}"""
private const val IMPLEMENT_OUTPUT = """{"implement":"done"}"""

private val ALL_PHASES =
  listOf("preplan", "plan", "implement", "review", "audit", "validate", "write_history", "commit_push", "pr")
private val NON_FILE_MUTATING_PHASES = setOf("preplan", "plan")

// A distinct invoking agent per phase so a captured launch request is
// phase-attributable from its invokedAgentId.
private fun phaseAgent(phaseId: String): String = "agent-$phaseId"

private fun phasePerAgentAssignment(): FeatureTaskRuntimeAgentAssignment =
  FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = ALL_PHASES.associateWith(::phaseAgent))

// Bundles the persistence + git collaborators a harness exposes so the harness constructor stays
// within the parameter budget.
private class RunnerHarnessIo(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  val repository: InMemoryRuntimeWorkflowRepository,
  val gitOperations: RecordingWorkflowGitOperations,
)

private class RunnerHarness(
  val launcher: RuntimeRecordingLauncher,
  val io: RunnerHarnessIo,
  val runner: FeatureTaskRuntimeRunner,
  val events: MutableList<FeatureTaskRuntimeRunEvent>,
  private val runRequest: FeatureTaskRuntimeRunRequest,
) {
  val recorder: FeatureTaskRuntimePhaseRecorder get() = io.recorder
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder get() = io.decomposeTerminalRecorder
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore get() = io.runInvariantsStore
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

  // Ensures the runtime workflow row exists first: the recorder write seam is a
  // no-op against a missing row.
  fun seedPhase(phaseId: String, status: String, attemptCount: Int, agentId: String, outputArtifact: String?) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseStateForTest(phaseId, status, attemptCount, agentId, outputArtifact)
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
}

// Mirrors the runner's branch-setup sentinel agent id so tests can seed a branch-setup-origin block.
private const val BRANCH_SETUP_AGENT_ID = "branch-setup"

// Bundles the branch-setup-relevant test inputs so runnerHarness stays within the parameter budget.
private data class BranchSetupTestConfig(
  val gitOperations: RecordingWorkflowGitOperations = RecordingWorkflowGitOperations(),
  val specReference: String = SPEC_REFERENCE,
  val featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
)

private data class RuntimeHarnessConfig(
  val branchSetup: BranchSetupTestConfig = BranchSetupTestConfig(),
  val repoRoot: Path = Path.of("/tmp/repo"),
  val environment: Map<String, String> = emptyMap(),
  val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  val useRealDecompositionPlanner: Boolean = false,
  val eventSink: FeatureTaskRuntimeRunEventSink? = null,
  val dbPathOverride: String? = null,
)

private fun runtimePhaseGates(
  branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  planningStopper: FeatureTaskRuntimePlanningStopper,
  lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
): FeatureTaskRuntimePhaseGates = FeatureTaskRuntimePhaseGates(
  branchSetupRunner,
  planningStopper,
  lifecycleTelemetry,
  gitOperations,
  FeatureTaskRuntimeSpecStatusProjector(TestDecompositionManifestFileStore),
)

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

private fun runnerHarness(
  launcher: RuntimeRecordingLauncher = RuntimeRecordingLauncher { facts(VALID_OUTPUT) },
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
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
  val runner = FeatureTaskRuntimeRunner(
    launcher,
    recorder,
    goalContinuationRecorder,
    runInvariantsStore,
    validator,
    FeatureTaskRuntimePhaseGates(
      branchSetupRunner,
      planningStopper,
      disabledRuntimeLifecycleTelemetry(database),
      runtimeConfig.branchSetup.gitOperations,
      FeatureTaskRuntimeSpecStatusProjector(TestDecompositionManifestFileStore),
    ),
  )
  // Always capture events; a caller-supplied sink is chained after the capture.
  val captured = mutableListOf<FeatureTaskRuntimeRunEvent>()
  val sink = FeatureTaskRuntimeRunEventSink { event ->
    captured += event
    runtimeConfig.eventSink?.emit(event)
  }
  val runRequest = FeatureTaskRuntimeRunRequest(
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
  val io = RunnerHarnessIo(
    recorder,
    decomposeTerminalRecorder,
    runInvariantsStore,
    repository,
    runtimeConfig.branchSetup.gitOperations,
  )
  return RunnerHarness(launcher, io, runner, captured, runRequest)
}

private class TelemetryRunnerHarness(
  val runner: FeatureTaskRuntimeRunner,
  val lifecycle: RecordingLifecycleTelemetryRepository,
  val request: FeatureTaskRuntimeRunRequest,
  val database: RuntimeFakeDatabaseSessionFactory,
)

private fun telemetryRunnerHarness(
  launcher: RuntimeRecordingLauncher = RuntimeRecordingLauncher { facts(VALID_OUTPUT) },
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

private fun facts(stdout: String): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = 0,
  stdout = stdout,
  stderr = "",
  timedOut = false,
  spawnFailed = false,
)

private val PHASE_LINE = Regex("^Phase: ([a-z_-]+) ", setOf(RegexOption.MULTILINE))

private fun phaseIdFromPrompt(prompt: String): String =
  PHASE_LINE.find(prompt)?.groupValues?.get(1) ?: error("Prompt did not contain a phase header: $prompt")

private fun validJsonOutput(phaseId: String): String = """
  {
    "contract_version": "0.1",
    "phase_id": "$phaseId",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": ${validProducedOutputs(phaseId)}
  }
""".trimIndent()

private fun validProducedOutputs(phaseId: String): String = if (phaseId == "commit_push") {
  """{"commit_push_result": {"commit_sha": "commit-runtime-1"}}"""
} else {
  """{"tasks": ["task-1"]}"""
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
private fun spawnFailedFacts(): AgentRunLaunchOutcome = AgentRunLaunchFacts(
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

private class RuntimeRecordingLauncher(
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

private object AlwaysValidValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) = Unit
}

// Records every checkout, with configurable currentBranch/checkoutBranch results. The default
// currentBranch reports an already-feature branch so existing tests never enter the create path;
// branch-setup tests override these to drive starts-on-default / cannot-establish / resume cases.
private class RecordingWorkflowGitOperations(
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
) : WorkflowGitOperations {
  // Seeded git HEAD for the SKILL-68 capture-at-source fallback: blank models an unmeasurable HEAD;
  // a concrete value models a measurable commit. headCommitShaResult overrides with a raw result.
  var headCommitShaValue: String = ""
  var headCommitShaResult: WorkflowGitOperationResult? = null
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

  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "recorded")

  var headCommitShaCalls: Int = 0

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult {
    headCommitShaCalls++
    return headCommitShaResult ?: WorkflowGitOperationResult(status = "ok", value = headCommitShaValue)
  }

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = "")

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

private class RuntimeFakeDatabaseSessionFactory(
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
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused")
    override val workflowStates: WorkflowStateRepository = repository
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
private class RecordingLifecycleTelemetryRepository : LifecycleTelemetryRepository {
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
}

private class InMemoryRuntimeWorkflowRepository : WorkflowStateRepository {
  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()

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
