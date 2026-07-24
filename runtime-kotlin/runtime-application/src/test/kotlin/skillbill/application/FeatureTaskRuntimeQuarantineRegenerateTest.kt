package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-140 quarantine-and-regenerate runner coverage: crash-resume cap continuity (AC-003),
 * truncated-pipeline block (AC-005), and per-run regeneration telemetry (AC-006). The end-to-end
 * legacy-record quarantine, regeneration, and advance (AC-001, AC-002) is proven in
 * [FeatureTaskRuntimeProjectionRejectionTest]; domain cap/edge behavior in
 * FeatureTaskRuntimeTransitionFunctionTest.
 */
class FeatureTaskRuntimeQuarantineRegenerateTest {
  private val legacyPlan =
    """{"contract_version":"0.2","phase_id":"plan","status":"completed","summary":"Legacy plan.",""" +
      """"produced_outputs":{"steps":["do the thing"],"narration":"free-form legacy body"}}"""

  // AC-003: a crash mid-regeneration resumes the same cap sequence without resetting the per-edge
  // counter, mirroring the implement-fix crash test (`crash mid-implement resume reconciles without
  // resetting the per-edge cap`). Run 1 fires the edge once and the plan regeneration crashes; the
  // durable plan record retains the regeneration loop context across the crash — the watermark a reset
  // would drop. Run 2 heals and advances, and the edge is never re-minted at 1. (The record-stamping
  // half of the fix, which seeds that watermark when a crash lands before the LOOP_EDGE ledger write,
  // is isolated in `invalidating a quarantined producer stamps the regeneration watermark durably`;
  // the at-cap terminal block is covered in FeatureTaskRuntimeTransitionFunctionTest.)
  @Test
  fun `a crash mid-regeneration resumes without resetting the regeneration cap`() {
    var planLaunches = 0
    var crashOnRegeneration = true
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when {
          // The crash hits the plan regeneration (plan's first launch this run is the re-entry).
          phaseId == "plan" && ++planLaunches == 1 && crashOnRegeneration -> spawnFailedFacts()
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), legacyPlan)

    // Run 1: implement rejects the legacy plan, the regeneration edge fires (iteration 1), and the
    // plan regeneration crashes.
    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals("plan", firstBlocked.lastIncompletePhase)
    // The terminal blocked producer record retained the regeneration loop context across the crash —
    // the watermark a reset would drop (AC-003). Without it the resume would reconstruct the per-edge
    // counter to 0 and re-mint iteration 1.
    val blockedPlan = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PLAN_REGENERATION_LOOP_ID, blockedPlan.loopId)
    assertEquals(1, blockedPlan.edgeIteration)

    // Run 2 (resume): the crash heals. The surviving watermark means the edge is NOT re-fired at 1;
    // plan regenerates a valid record and the consumer advances to completion.
    crashOnRegeneration = false
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val regenerationEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.PLAN_REGENERATION_LOOP_ID
      }
      .mapNotNull { it.edgeIteration }
    assertEquals(
      listOf(1),
      regenerationEdges,
      "the regeneration edge fired once and its cap watermark survived the crash without reset",
    )
  }

  // AC-003 (F-001 isolation): the durable producer invalidation stamps the regeneration loop context
  // onto the running record in the SAME transaction that clears its settled completion, so the per-edge
  // cap watermark survives a crash landing after that commit but before the LOOP_EDGE ledger write.
  // `FeatureTaskRuntimeRunState.edgeIterationByLoop` reseeds the cap from this record context on resume,
  // so a reset to 0 (which would let the cap be exceeded) is impossible. Stamping is proven here rather
  // than through the launch-time crash test, whose crash lands after the ledger write.
  @Test
  fun `invalidating a quarantined producer stamps the regeneration watermark durably`() {
    val harness = runnerHarness()
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), legacyPlan)

    harness.recorder.invalidateQuarantinedProducerRecord(
      WORKFLOW_ID,
      "plan",
      FeatureTaskRuntimePhaseWorkflowDefinition.PLAN_REGENERATION_LOOP_ID,
      edgeIteration = 1,
    )

    val plan = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("running", plan.status, "the settled completion is cleared so the producer relaunches")
    assertEquals(
      FeatureTaskRuntimePhaseWorkflowDefinition.PLAN_REGENERATION_LOOP_ID,
      plan.loopId,
      "the regeneration loop id is stamped durably, seeding the resume watermark before the ledger write",
    )
    assertEquals(1, plan.edgeIteration, "the per-edge iteration is stamped durably so the cap cannot reset")
    assertEquals(legacyPlan, plan.rejectedOutput, "the rejected record is retained as evidence, not a usable output")
    assertEquals(null, plan.outputArtifact, "the rejected payload is no longer selectable as the producer output")
  }

  // AC-005: a goal-continuation truncation dropped the producer, so the rejected record cannot be
  // regenerated in-band; the run blocks durably with an actionable reason instead of attempting an
  // impossible re-entry.
  @Test
  fun `a rejected record whose producer the pipeline dropped blocks durably with an actionable reason`() {
    val surviving = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )
    val truncated = FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = surviving,
      backwardEdges = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
        .filter { it.fromPhaseId in surviving && it.destinationPhaseId in surviving },
      loopOnlyPhaseIds = emptySet(),
      entryGates = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.entryGates
        .filter { it.phaseId in surviving && it.requiredPhaseId in surviving },
    )
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    // The producing plan phase left a record but is absent from the resolved pipeline.
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), legacyPlan)

    val report = harness.runner.run(harness.request(truncated))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "absent from this run's resolved pipeline")
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "plan" },
      "a dropped producer is never re-entered",
    )
  }

  // AC-006: per-run regeneration telemetry records activation and attempt counts and outcome classes,
  // and the emitted payload carries no rejected-record bytes.
  @Test
  fun `regeneration telemetry records activation and attempt counts without record contents`() {
    val harness = telemetryRunnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), legacyPlan)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request))

    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals(1, finished.regenerationActivationCount, "one producer's regeneration loop activated")
    assertEquals(1, finished.regenerationAttemptCount, "the regeneration edge fired once")
    assertEquals(mapOf("regenerated" to 1), finished.regenerationOutcomeCounts)
    // Content-leak guard: no rejected-record bytes reach the emitted telemetry payload.
    assertTrue(
      finished.regenerationOutcomeCounts.keys.none { it.contains("free-form") } &&
        !finished.blockedReason.contains("free-form legacy body"),
      "regeneration telemetry must carry counts and class labels only, never record contents",
    )
  }

  // AC-002: the quarantine evidence store is append-only, retrievable in order, and re-appending an
  // already-recorded entry (crash replay) never duplicates or mutates a prior entry.
  @Test
  fun `quarantine evidence is append-only retrievable in order and crash-replay idempotent`() {
    val harness = runnerHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    val first = FeatureTaskRuntimeQuarantineEntry(
      producingPhaseId = "plan",
      consumingPhaseId = "implement",
      producingIteration = 1,
      rejectionClass = "planning_projection_schema",
      rejectionDetail = "plan#produced_outputs: projection_kind is missing",
      regenerationAttempt = 1,
      quarantinedAtIteration = 1,
      rejectedRecordPayload = "payload-one",
    )
    val second = first.copy(producingIteration = 2, regenerationAttempt = 2, rejectedRecordPayload = "payload-two")
    harness.recorder.appendQuarantineEntry(WORKFLOW_ID, first)
    harness.recorder.appendQuarantineEntry(WORKFLOW_ID, second)

    val loaded = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
    assertEquals(listOf("payload-one", "payload-two"), loaded.map { it.rejectedRecordPayload })

    // Crash replay: re-appending the first entry is a no-op; the store never duplicates or reorders.
    harness.recorder.appendQuarantineEntry(WORKFLOW_ID, first)
    val reloaded = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
    assertEquals(2, reloaded.size, "an already-recorded entry is never duplicated")
    assertEquals(loaded.map { it.rejectedRecordPayload }, reloaded.map { it.rejectedRecordPayload })
  }
}
