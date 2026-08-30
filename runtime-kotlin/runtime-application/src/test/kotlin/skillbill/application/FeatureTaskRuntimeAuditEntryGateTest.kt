package skillbill.application

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.featuretask.FeatureTaskRuntimeRunState
import skillbill.application.featuretask.REVIEW_INVALIDATION_AGENT_ID
import skillbill.application.featuretask.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.transitionsFor
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-135 Subtask 1 remediation: the `review` phase-entry gate is only worth declaring if it
 * reaches the production topology and survives a durable record minted under the pre-reorder
 * ordering. These cases exercise the wiring end-to-end through the runner rather than asserting
 * against the shipped definition object, which every seam already agreed on while the gate was
 * inert in every real run.
 */
class FeatureTaskRuntimeAuditEntryGateTest {

  @Test
  fun `the production transition declaration carries the declared entry gate`() {
    val harness = runnerHarness()

    val transitions = transitionsFor(harness.request())

    assertTrue(
      FeatureTaskRuntimePhaseWorkflowDefinition.transitions.entryGates.isNotEmpty(),
      "the shipped definition must declare at least one gate for this assertion to mean anything",
    )
    assertEquals(
      FeatureTaskRuntimePhaseWorkflowDefinition.transitions.entryGates,
      transitions.entryGates,
      "a gate the resolver drops is dead code: both enforcement seams read this declaration",
    )
  }

  @Test
  fun `a gate carried unfiltered into a truncated pipeline fails construction`() {
    // The hazard the resolver's filter exists to prevent: transitionsFor runs outside the runner's
    // failure handling and before telemetry starts, so a gate naming a truncated-away phase would
    // surface as an untyped error rather than as a gate.
    val truncated = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
      .takeWhile { it != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW }

    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeTransitionDeclaration(
        forwardPhaseIds = truncated,
        entryGates = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.entryGates,
      )
    }
  }

  @Test
  fun `a durable review completed before audit is invalidated so audit runs first and review re-runs`() {
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment())))
    seedThroughImplement(harness)
    // The pre-reorder ordering ran review before audit, and burned the review fix-loop budget doing
    // it: the re-run must still get a fresh per-visit budget rather than re-blocking immediately.
    harness.seedPhase("review", "completed", 3, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launched = harness.launchOrder()
    assertTrue(launched.contains("audit"), "the gating audit must run on the reordered graph")
    assertTrue(
      launched.indexOf("audit") < launched.indexOf("review"),
      "review must be relaunched only after audit settled, was $launched",
    )
  }

  @Test
  fun `a migration resume runs the relaunched review as pass one and completes its remediation cycle`() {
    var firstRelaunchedReviewPassNumber: Int? = null
    lateinit var harness: RunnerHarness
    harness = runnerHarness(reviewFixRuntimeConfig(2).copy(agentAssignment = phasePerAgentAssignment(), launcher = RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (phaseIdFromPrompt(prompt)) {
          "implement_fix" -> {
            firstRelaunchedReviewPassNumber = harness.recorder
              .loadPhaseRecords(WORKFLOW_ID)
              .orEmpty()["review"]
              ?.reviewPassNumber
            facts(FRESH_IMPLEMENT_FIX_OUTPUT)
          }
          else -> facts(defaultPhaseOutput(request))
        }
      }))
    seedThroughImplement(harness)
    harness.seedPhase("review", "completed", 3, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertMigrationRemediationLaunchOrder(harness.launchOrder())
    assertEquals(1, firstRelaunchedReviewPassNumber)
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals(1, reviewRecord.reviewPassNumber)
  }

  @Test
  fun `audit gate invalidation is durable across the audit completion crash window`() {
    var failAuditLaunch = true
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit" && failAuditLaunch) spawnFailedFacts() else facts(defaultPhaseOutput(request))
      },)))
    seedThroughImplement(harness)
    harness.seedPhase("review", "completed", 2, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    val tombstone = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("running", tombstone.status)
    assertEquals("audit-gate-migration", tombstone.resolvedAgentId)
    assertEquals(null, tombstone.outputArtifact)
    assertEquals(null, tombstone.reviewPassNumber)

    failAuditLaunch = false
    harness.seedPhase("audit", "completed", 2, phaseAgent("audit"), SYNONYM_SATISFIED_AUDIT_OUTPUT)
    val resumed = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumed)
    assertEquals(1, harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty().getValue("review").reviewPassNumber)
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it == "validate" } ||
        harness.launchOrder().indexOf("review") < harness.launchOrder().indexOf("validate"),
      "validation must remain behind the replacement review",
    )
  }

  @Test
  fun `an explicit legacy pass two is replaced by a fresh pass one`() {
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment())))
    seedThroughImplement(harness)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        status = "completed",
        attemptCount = 2,
        resolvedAgentId = phaseAgent("review"),
        finished = true,
        outputArtifact = CLEAN_REVIEW_OUTPUT,
        loopId = "review_fix",
        edgeIteration = 1,
        reviewPassNumber = 2,
      ),
    )
    harness.seedLoopEdge("implement_fix", "review_fix", 1)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val review = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty().getValue("review")
    assertEquals(1, review.reviewPassNumber)
    assertEquals(null, review.loopId)
    assertEquals(null, review.edgeIteration)
  }

  private fun assertMigrationRemediationLaunchOrder(launched: List<String>) {
    assertEquals(
      1,
      launched.count { it == "review" },
      "the relaunched review runs exactly once before verification, was $launched",
    )
    assertTrue(
      launched.indexOf("audit") < launched.indexOf("review"),
      "review must be relaunched only after audit settled, was $launched",
    )
    assertTrue(
      launched.indexOf("verify_findings") > launched.indexOf("review"),
      "finding verification must follow the relaunched review, was $launched",
    )
    assertTrue(
      launched.indexOf("implement_fix") > launched.indexOf("verify_findings"),
      "the fix round must follow verified findings, was $launched",
    )
  }

  @Test
  fun `a migration resume cannot complete while the blocker awaits verification review`() {
    val harness = runnerHarness(RuntimeHarnessConfig(
        reviewDriver = reviewFixDriver(2),
      ).copy(agentAssignment = phasePerAgentAssignment(), launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "verify_findings") spawnFailedFacts() else facts(defaultPhaseOutput(request))
      }))
    seedThroughImplement(harness)
    harness.seedPhase("review", "completed", 3, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("verify_findings", blocked.lastIncompletePhase)
    assertEquals(
      1,
      harness.launchOrder().count { it == "verify_findings" },
      "the failed verification launch must remain reserved",
    )
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it in setOf("validate", "commit_push", "pr") },
      "downstream phases must remain unreachable until findings are verified",
    )
  }

  @Test
  fun `a legacy in-flight review fix re-entry does not step over the audit that never ran`() {
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment())))
    seedThroughImplement(harness)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)
    // A prior run under the old ordering fired review_fix and crashed with implement_fix in flight.
    harness.seedLoopEdge("implement_fix", "review_fix", 1)
    harness.seedReentryPhase(SeedReentryPhaseSeed("implement_fix", "running", 1, phaseAgent("implement"), null, "review_fix", 1))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchOrder()
    assertTrue(
      report.completedPhaseIds.contains("audit"),
      "resuming at the re-entry destination must not reach a terminal report with audit unvisited",
    )
    // Discriminating against the pre-reorder graph, under which audit also runs and also completes:
    // what the reordering owns is that it runs BEFORE the review it gates.
    assertTrue(
      launched.indexOf("audit") in 0 until launched.indexOf("review"),
      "audit must run before the review it gates, not merely somewhere in the run: launched=$launched",
    )
  }

  @Test
  fun `an undecidable audit fails the phase-output schema rather than wedging the run behind the gate`() {
    var auditLaunches = 0
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment(),
      validator = realFeatureTaskRuntimePhaseOutputValidator,
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          auditLaunches += 1
          facts(if (auditLaunches == 1) UNDECIDABLE_AUDIT_OUTPUT else defaultPhaseOutput(request))
        } else {
          facts(defaultPhaseOutput(request))
        }
      },)))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals(1, auditLaunches, "an off-vocabulary audit verdict settles on the schema gate, without a relaunch")
    assertGateBlockNamesRule(blocked.blockedReason, "phase-output-schema")
    assertTrue(
      !blocked.blockedReason.contains("off-vocabulary verdict 'x' and no y'"),
      "the blocked reason must not quote the response wire verdict",
    )
    assertTrue(
      !harness.launchOrder().contains("review"),
      "review must stay unreachable until audit has settled: launched=${harness.launchOrder()}",
    )
  }

  @Test
  fun `an audit with satisfied verdict and audit prose advances to review`() {
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") SYNONYM_SATISFIED_AUDIT_OUTPUT else defaultPhaseOutput(request))
      },)))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(
      harness.launchOrder().contains("review"),
      "an audit whose envelope verdict is satisfied must not block review",
    )
  }

  @Test
  fun `a resumed gate migration does not charge the fresh review with the legacy attempt watermark`() {
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment())))
    seedThroughImplement(harness)
    // The durable state an earlier migrating load left behind: the review tombstone carrying the
    // legacy generation's exhausted attempt watermark. That load's in-memory generation reset does
    // not survive, and a non-completed tombstone never re-enters the gate-invalidation set.
    harness.seedPhase(
      "review",
      "running",
      3,
      REVIEW_INVALIDATION_AGENT_ID,
      null,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(
      harness.launchOrder().contains("review"),
      "the migrated review must launch fresh, not re-block as an exhausted fix loop before launch",
    )
  }

  @Test
  fun `a dropped legacy review fix re-entry does not spend the fresh generation's fix pass`() {
    val harness = runnerHarness(reviewFixRuntimeConfig(2).copy(agentAssignment = phasePerAgentAssignment()))
    seedThroughImplement(harness)
    // A legacy run fired review_fix once and left implement_fix in flight; review never durably
    // completed, so there is no tombstone and the re-entry is dropped for its gate-blocked span.
    harness.seedLoopEdge("implement_fix", "review_fix", 1)
    harness.seedReentryPhase(SeedReentryPhaseSeed("implement_fix", "running", 1, phaseAgent("implement"), null, "review_fix", 1))

    harness.runner.run(harness.request())

    assertTrue(
      harness.launchedPromptPhaseOrder().contains("implement_fix"),
      "the fresh review's changes_requested must still earn its fix pass rather than being charged " +
        "the watermark the dropped re-entry left behind",
    )
    assertEquals(
      1,
      harness.launchOrder().count { it == "review" },
      "the single review pass still runs before verification",
    )
    assertTrue(
      harness.launchOrder().contains("verify_findings"),
      "verified findings must be settled before the earned fix pass",
    )
  }

  @Test
  fun `dropping a gate-blocked re-entry releases its per-edge watermark`() {
    val state = FeatureTaskRuntimeRunState(
      initialRecords = mapOf(
        "implement_fix" to FeatureTaskRuntimePhaseRecord(
          phaseId = "implement_fix",
          status = "running",
          attemptCount = 1,
          startedAt = "2026-06-02T00:00:00Z",
          resolvedAgentId = "implementer",
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
          edgeIteration = 1,
        ),
      ),
      transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
      initialLedger = listOf(
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
          sequenceNumber = 1,
          timestamp = "2026-06-02T00:00:00Z",
          phaseId = "implement_fix",
          attemptCount = 1,
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
          edgeIteration = 1,
        ),
      ),
      outputValidator = AlwaysValidValidator,
    )
    // Precondition: the legacy generation's watermark is loaded, and its span cannot be completed
    // because review sits behind an audit that never settled.
    assertEquals(1, state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID))
    assertTrue(state.spanBlockedByEntryGate(listOf("implement_fix", "review")))

    state.discardStaleReentry(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID)

    assertEquals(
      0,
      state.edgeIterationCount(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
      "a watermark surviving its dropped re-entry charges the fresh generation for a fix pass it never took",
    )
    assertFalse(state.isLoopLiveClaimed(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID))
  }

  @Test
  fun `standalone and goal-child runs resolve the same phase order and the same gates`() {
    val standalone = transitionsFor(runnerHarness().request())
    val goalChild = transitionsFor(
      runnerHarness(RuntimeHarnessConfig(
          goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
            parentIssueKey = "SKILL-0",
            subtaskId = 1,
            goalBranch = "feat/goal-branch",
            suppressPr = true,
            parentWorkflowId = "wfl-parent",
            reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
          ),
        ))).request(),
    )

    // The goal child truncates at pr and nowhere else: same order, same gates, same backward edges.
    assertEquals(standalone.forwardPhaseIds.dropLast(1), goalChild.forwardPhaseIds)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR, standalone.forwardPhaseIds.last())
    assertEquals(standalone.entryGates, goalChild.entryGates)
    assertEquals(standalone.backwardEdges, goalChild.backwardEdges)
    assertEquals(standalone.loopOnlyPhaseIds, goalChild.loopOnlyPhaseIds)
  }

  @Test
  fun `a review generation restart over retained evidence advances without discarding the prior generation`() {
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment())))
    seedThroughImplement(harness)
    // The observed database state: the capped generation retained review evidence at both attempts
    // with differing bytes, and the watermark is about to rewind below them.
    seedReviewEvidence(harness, attempt = 1, payload = LEGACY_FIRST_REVIEW_PAYLOAD)
    seedReviewEvidence(harness, attempt = 2, payload = LEGACY_SECOND_REVIEW_PAYLOAD)
    harness.seedPhase(
      "review",
      "running",
      3,
      REVIEW_INVALIDATION_AGENT_ID,
      null,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(
      harness.launchOrder().contains("review"),
      "the restarted review must launch; a colliding evidence key aborts the run before it advances",
    )
    assertTrue(
      LEGACY_FIRST_REVIEW_PAYLOAD.contentEquals(
        harness.io.database.producerEvidenceAt(
          ProducerEvidenceKey(WORKFLOW_ID, "review", 0, 1, phaseAgent("review")),
        )?.payload,
      ),
      "the prior generation's attempt-1 evidence must survive byte-for-byte",
    )
    assertTrue(
      LEGACY_SECOND_REVIEW_PAYLOAD.contentEquals(
        harness.io.database.producerEvidenceAt(
          ProducerEvidenceKey(WORKFLOW_ID, "review", 0, 2, phaseAgent("review")),
        )?.payload,
      ),
      "the prior generation's attempt-2 evidence must survive byte-for-byte",
    )
    val fresh = harness.io.database.producerEvidenceAt(
      ProducerEvidenceKey(WORKFLOW_ID, "review", 1, 1, phaseAgent("review")),
    )
    assertTrue(
      fresh != null && !LEGACY_FIRST_REVIEW_PAYLOAD.contentEquals(fresh.payload),
      "the fresh review's evidence must land on its own generation rather than the rewound key",
    )
    assertNoRawResponseSpan(
      harness.events.joinToString(" ") { it.toString() },
      LEGACY_FIRST_REVIEW_MARKER,
      LEGACY_SECOND_REVIEW_MARKER,
    )
  }

  @Test
  fun `an already-tombstoned workflow reconciles its evidence generation with no operator surgery`() {
    val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment())))
    seedThroughImplement(harness)
    seedReviewEvidence(harness, attempt = 1, payload = LEGACY_FIRST_REVIEW_PAYLOAD)
    harness.seedPhase(
      "review",
      "running",
      3,
      REVIEW_INVALIDATION_AGENT_ID,
      null,
    )

    harness.runner.run(harness.request())

    assertEquals(1, reviewGenerationOrdinal(harness))
    assertEquals(
      1,
      harness.io.database.retainedProducerEvidence().count { it.phaseId == "review" && it.generation == 0 },
      "reconciliation must not discard the prior generation's evidence",
    )

    harness.runner.run(harness.request())

    assertEquals(
      1,
      reviewGenerationOrdinal(harness),
      "a run that does not restart the generation must leave the durable ordinal alone",
    )
  }

  private fun reviewGenerationOrdinal(harness: RunnerHarness): Int = (
    harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)[
      FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY,
    ] as Number
    ).toInt()

  private fun seedReviewEvidence(harness: RunnerHarness, attempt: Int, payload: ByteArray) {
    harness.io.database.retainProducerEvidence(
      ProducerOutputEvidence(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        attempt = attempt,
        agentId = phaseAgent("review"),
        model = "gpt",
        recordedAt = Instant.parse("2026-06-01T00:00:00Z"),
        byteSize = payload.size.toLong(),
        sha256 = RejectedOutputDiagnosticService.sha256(payload),
        payload = payload,
        generation = 0,
      ),
    )
  }

  private fun seedThroughImplement(harness: RunnerHarness) {
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_DIGEST_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_STEPS_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
  }

  @Test
  fun `gaps_found with blank or missing audit value blocks before remediation`() {
    listOf(
      "{}",
      "{\"value\":\"\"}",
      "{\"value\":\"   \"}",
    ).forEach { producedOutputs ->
      var auditLaunches = 0
      val harness = runnerHarness(RuntimeHarnessConfig(agentAssignment = phasePerAgentAssignment(),
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId != "audit") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
          auditLaunches += 1
          facts(gapsFoundAuditOutput(producedOutputs))
        },)))

      assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
      assertEquals(1, auditLaunches, producedOutputs)
      assertTrue(!harness.launchOrder().contains("review"), "a rejected audit must not reach review")
    }
  }

  private fun gapsFoundAuditOutput(producedOutputs: String): String =
    """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"audit",""" +
      """"status":"completed","verdict":"gaps_found","summary":"Audit found unmet criteria.",""" +
      """"produced_outputs":$producedOutputs}"""
}

private const val LEGACY_FIRST_REVIEW_MARKER = "legacy-review-generation-attempt-one"
private const val LEGACY_SECOND_REVIEW_MARKER = "legacy-review-generation-attempt-two"
private val LEGACY_FIRST_REVIEW_PAYLOAD = LEGACY_FIRST_REVIEW_MARKER.encodeToByteArray()
private val LEGACY_SECOND_REVIEW_PAYLOAD = LEGACY_SECOND_REVIEW_MARKER.encodeToByteArray()

private const val FRESH_IMPLEMENT_FIX_MARKER = "fresh-migration-resume-fix"
private const val FRESH_IMPLEMENT_FIX_OUTPUT =
  """{"contract_version":"0.1","phase_id":"implement_fix","status":"completed","summary":"fix",""" +
    """"produced_outputs":{"changed_files":["$FRESH_IMPLEMENT_FIX_MARKER"],""" +
    """"reconciled_state":{"reconciled":true}}}"""

// preplan and plan feed the bounded planning projections, so their seeded records are full envelopes
// carrying the declared projection body rather than bare produced_outputs fragments.
private val PREPLAN_DIGEST_OUTPUT = validJsonOutput("preplan")
private val PLAN_STEPS_OUTPUT = validJsonOutput("plan")
private const val CLEAN_REVIEW_OUTPUT = """{"contract_version":"0.1","produced_outputs":{"findings":[]}}"""

// Carries a verdict but one outside the closed audit vocabulary, with no criteria array to derive a
// decidable verdict from, so the audit verification-signal gate rejects it. The interior
// `' and no` in the wire value is the realistic scrub bug: a non-greedy `'.*?'(?= and no)` match
// stops early and leaves a response-derived suffix in Violated constraint outside the authorized
// repair section.
private const val UNDECIDABLE_AUDIT_OUTPUT =
  """{"contract_version":"0.6","phase_id":"audit","status":"completed","summary":"audit",""" +
    """"verdict":"x' and no y","produced_outputs":{"value":"{\"gaps\":[]}"}}"""

private const val SYNONYM_SATISFIED_AUDIT_OUTPUT =
  """{"contract_version":"0.6","phase_id":"audit","status":"completed","summary":"criteria met",""" +
    """"verdict":"satisfied","produced_outputs":{"value":"{\"gaps\":[],\"non_blocking_findings\":[]}"}}"""
