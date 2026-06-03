package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
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
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
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
  fun `runs phases deterministically in plan implement review audit validate order`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(listOf("plan", "implement", "review", "audit", "validate"), completed.completedPhaseIds)
    assertEquals(
      listOf("plan", "implement", "review", "audit", "validate"),
      harness.launchedPhaseOrder(),
    )
    assertEquals(
      listOf("plan", "implement", "review", "audit", "validate"),
      harness.launchOrder(),
    )
  }

  @Test
  fun `each phase briefing includes unconditional run-invariants latest upstream and derived diff for review`() {
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = SPEC_REFERENCE,
      acceptanceCriteria = listOf("AC-1", "AC-2"),
      mandatesAndOverrides = listOf("mandate-X"),
    )
    val recorded = listOf(
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("plan", 1, PLAN_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("implement", 1, IMPLEMENT_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("review", 1, VALID_OUTPUT),
    )

    val briefings = listOf("plan", "implement", "review", "audit", "validate").associateWith { phaseId ->
      val declaration =
        skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations.getValue(phaseId)
      val handoff = skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract.assembleHandoff(
        declaration = declaration,
        runInvariants = invariants,
        recordedOutputs = recorded,
      )
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    }

    briefings.forEach { (phaseId, briefing) ->
      assertEquals(SPEC_REFERENCE, briefing.specReference, "spec reference for $phaseId")
      assertEquals(listOf("AC-1", "AC-2"), briefing.acceptanceCriteria, "criteria for $phaseId")
      assertContains(briefing.briefingText, SPEC_REFERENCE)
      assertContains(briefing.briefingText, "mandate-X")
    }
    assertTrue(briefings.getValue("implement").upstreamOutputsByPhaseId.containsKey("plan"))
    assertEquals(PLAN_OUTPUT, briefings.getValue("implement").upstreamOutputsByPhaseId.getValue("plan"))
    assertEquals(listOf("diff"), briefings.getValue("review").derivedContextKeys)
    assertContains(briefings.getValue("review").briefingText, "diff")
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
    assertEquals(listOf("plan"), blocked.completedPhaseIds)
    assertEquals(listOf("plan", "implement"), harness.launchedPhaseOrder())
    assertEquals(listOf("plan", "implement"), harness.launchOrder())
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
    listOf("plan", "implement", "review", "audit", "validate").forEach { phaseId ->
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
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(listOf("review", "audit", "validate"), harness.launchedPhaseOrder())
    assertEquals(listOf("review", "audit", "validate"), harness.launchOrder())

    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    val reviewBriefing = requireNotNull(briefings["review"]) { "review briefing must be persisted" }
    assertEquals(IMPLEMENT_OUTPUT, reviewBriefing.upstreamOutputsByPhaseId["implement"])
    val auditBriefing = requireNotNull(briefings["audit"]) { "audit briefing must be persisted" }
    assertEquals(PLAN_OUTPUT, auditBriefing.upstreamOutputsByPhaseId["plan"])
    assertEquals(IMPLEMENT_OUTPUT, auditBriefing.upstreamOutputsByPhaseId["implement"])
    assertEquals(VALID_OUTPUT, auditBriefing.upstreamOutputsByPhaseId["review"])
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
    assertEquals("validate", row.currentStepId)
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
    assertEquals(listOf("plan", "implement", "review", "audit", "validate"), started)
    assertEquals(listOf("plan", "implement", "review", "audit", "validate"), done)

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

  @Test
  fun `persists per-phase briefing durably with run-invariants upstream and review diff`() {
    val harness = runnerHarness()

    harness.runner.run(harness.request())

    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    assertEquals(setOf("plan", "implement", "review", "audit", "validate"), briefings.keys)

    briefings.forEach { (phaseId, briefing) ->
      assertEquals(SPEC_REFERENCE, briefing.specReference, "spec reference for $phaseId")
      assertEquals(listOf("AC-1", "AC-2"), briefing.acceptanceCriteria, "criteria for $phaseId")
      assertEquals(listOf("mandate-X"), briefing.mandatesAndOverrides, "mandates for $phaseId")
      assertContains(briefing.briefingText, SPEC_REFERENCE)
      assertContains(briefing.briefingText, "mandate-X")
    }
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
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "failed to launch")
    assertTrue(!blocked.blockedReason.contains("schema"))
    assertTrue(!blocked.blockedReason.contains("exhausted the bounded fix loop"))
    assertEquals(listOf("plan"), harness.launchedPhaseOrder())
  }

  @Test
  fun `launch timeout on a fix-loop phase blocks distinctly without burning the budget`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        if (request.invokedAgentId == phaseAgent("review")) timedOutFacts() else facts(VALID_OUTPUT)
      },
      agentAssignment = phasePerAgentAssignment(),
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
}

private const val WORKFLOW_ID = "wftr-20260602-test-0001"
private const val SESSION_ID = "ftr-test-001"
private const val ISSUE_KEY = "SKILL-65"
private const val SPEC_REFERENCE = ".feature-specs/SKILL-65/spec.md"
private const val INVOKED_AGENT = "claude-code"
private const val VALID_OUTPUT = """{"contract_version":"0.1"}"""
private const val PLAN_OUTPUT = """{"plan":"do-the-thing"}"""
private const val IMPLEMENT_OUTPUT = """{"implement":"done"}"""

private val ALL_PHASES = listOf("plan", "implement", "review", "audit", "validate")

// A distinct invoking agent per phase so a captured launch request is
// phase-attributable from its invokedAgentId.
private fun phaseAgent(phaseId: String): String = "agent-$phaseId"

private fun phasePerAgentAssignment(): FeatureTaskRuntimeAgentAssignment =
  FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = ALL_PHASES.associateWith(::phaseAgent))

private class RunnerHarness(
  val launcher: RuntimeRecordingLauncher,
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val repository: InMemoryRuntimeWorkflowRepository,
  val runner: FeatureTaskRuntimeRunner,
  val events: MutableList<FeatureTaskRuntimeRunEvent>,
  private val runRequest: FeatureTaskRuntimeRunRequest,
) {
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

  fun request(): FeatureTaskRuntimeRunRequest = runRequest
}

private fun runnerHarness(
  launcher: RuntimeRecordingLauncher = RuntimeRecordingLauncher { facts(VALID_OUTPUT) },
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  eventSink: FeatureTaskRuntimeRunEventSink? = null,
): RunnerHarness {
  val repository = InMemoryRuntimeWorkflowRepository()
  val database = RuntimeFakeDatabaseSessionFactory(repository)
  val recorder = FeatureTaskRuntimePhaseRecorder(database, NoopWorkflowSnapshotValidator)
  val runner = FeatureTaskRuntimeRunner(launcher, recorder, validator)
  // Always capture events; a caller-supplied sink is chained after the capture.
  val captured = mutableListOf<FeatureTaskRuntimeRunEvent>()
  val sink = FeatureTaskRuntimeRunEventSink { event ->
    captured += event
    eventSink?.emit(event)
  }
  val runRequest = FeatureTaskRuntimeRunRequest(
    issueKey = ISSUE_KEY,
    workflowId = WORKFLOW_ID,
    sessionId = SESSION_ID,
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = SPEC_REFERENCE,
      acceptanceCriteria = listOf("AC-1", "AC-2"),
      mandatesAndOverrides = listOf("mandate-X"),
    ),
    invokedAgentId = INVOKED_AGENT,
    agentAssignment = agentAssignment,
    environment = emptyMap(),
    dbPathOverride = null,
    repoRoot = Path.of("/tmp/repo"),
    eventSink = sink,
  )
  return RunnerHarness(launcher, recorder, repository, runner, captured, runRequest)
}

private fun facts(stdout: String): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = 0,
  stdout = stdout,
  stderr = "",
  timedOut = false,
  spawnFailed = false,
)

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
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/metrics.db")

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@RuntimeFakeDatabaseSessionFactory.dbPath
    override val reviews: ReviewRepository get() = error("unused")
    override val learnings: LearningRepository get() = error("unused")
    override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused")
    override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused")
    override val workflowStates: WorkflowStateRepository = repository
  }
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
