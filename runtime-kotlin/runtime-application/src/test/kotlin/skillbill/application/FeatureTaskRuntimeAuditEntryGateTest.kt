package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.featuretask.FeatureTaskRuntimeRunState
import skillbill.application.featuretask.REVIEW_INVALIDATION_AGENT_ID
import skillbill.application.featuretask.transitionsFor
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import kotlin.test.Test
import kotlin.test.assertContains
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
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    seedThroughImplement(harness)
    // The pre-reorder ordering ran review before audit, and burned the review fix-loop budget doing
    // it: the re-run must still get a fresh per-visit budget rather than re-blocking immediately.
    harness.seedPhase("review", "completed", 3, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPhaseOrder()
    assertTrue(launched.contains("audit"), "the gating audit must run on the reordered graph")
    assertTrue(
      launched.indexOf("audit") < launched.indexOf("review"),
      "review must be relaunched only after audit settled, was $launched",
    )
  }

  @Test
  fun `a migration resume runs the relaunched review as pass one and completes its remediation cycle`() {
    val reviewPrompts = mutableListOf<String>()
    var firstRelaunchedReviewPassNumber: Int? = null
    lateinit var harness: RunnerHarness
    harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (phaseIdFromPrompt(prompt)) {
          "review" -> {
            reviewPrompts += prompt
            facts(reviewFindingsOutput(changesRequested = reviewPrompts.size == 1))
          }
          "implement_fix" -> {
            firstRelaunchedReviewPassNumber = harness.recorder
              .loadPhaseRecords(WORKFLOW_ID)
              .orEmpty()["review"]
              ?.reviewPassNumber
            facts(FRESH_IMPLEMENT_FIX_OUTPUT)
          }
          else -> facts(defaultPhaseOutput(request))
        }
      },
    )
    seedThroughImplement(harness)
    harness.seedPhase("review", "completed", 3, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)

    val report = harness.runner.run(harness.request())

    val launched = harness.launchedPromptPhaseOrder()
    assertMigrationRemediationLaunchOrder(launched)
    assertEquals(1, firstRelaunchedReviewPassNumber)
    assertFalse(
      reviewPrompts[0].contains(PASS_TWO_REMEDIATION_SCOPE),
      "the gate-invalidated review must relaunch as pass one, not inherit the pre-reorder pass number",
    )
    assertContains(reviewPrompts[1], PASS_TWO_REMEDIATION_SCOPE)
    assertContains(
      reviewPrompts[1],
      FRESH_IMPLEMENT_FIX_MARKER,
      message = "the verification review must consume the newly produced implement_fix output",
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals(2, reviewRecord.reviewPassNumber)
    assertFalse(
      reviewRecord.outputArtifact.orEmpty().contains(REVIEW_BLOCKER_MESSAGE),
      "the run must not reach Completed while the blocker finding stands unverified",
    )
  }

  @Test
  fun `audit gate invalidation is durable across the audit completion crash window`() {
    var failAuditLaunch = true
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit" && failAuditLaunch) spawnFailedFacts() else facts(defaultPhaseOutput(request))
      },
    )
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
        harness.launchedPromptPhaseOrder().indexOf("review") < harness.launchedPromptPhaseOrder().indexOf("validate"),
      "validation must remain behind the replacement review",
    )
  }

  @Test
  fun `an explicit legacy pass two is replaced by a fresh pass one`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
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
      2,
      launched.count { it == "review" },
      "the blocker from the relaunched review must be re-verified by a second pass, was $launched",
    )
    assertTrue(
      launched.indexOf("audit") < launched.indexOf("review"),
      "review must be relaunched only after audit settled, was $launched",
    )
    assertTrue(
      launched.indexOf("implement_fix") in (launched.indexOf("review") + 1) until launched.lastIndexOf("review"),
      "the fix must run between the two review passes, was $launched",
    )
  }

  @Test
  fun `a migration resume cannot complete while the blocker awaits verification review`() {
    var reviewLaunches = 0
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "review" -> {
            reviewLaunches += 1
            if (reviewLaunches == 1) {
              facts(reviewFindingsOutput(changesRequested = true))
            } else {
              spawnFailedFacts()
            }
          }
          else -> facts(defaultPhaseOutput(request))
        }
      },
    )
    seedThroughImplement(harness)
    harness.seedPhase("review", "completed", 3, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("review", blocked.lastIncompletePhase)
    assertEquals(2, reviewLaunches, "the failed verification launch must remain reserved as pass two")
    assertTrue(
      harness.launchedPromptPhaseOrder().none { it in setOf("validate", "commit_push", "pr") },
      "downstream phases must remain unreachable until the blocker is re-verified",
    )
  }

  @Test
  fun `a legacy in-flight review fix re-entry does not step over the audit that never ran`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    seedThroughImplement(harness)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), CLEAN_REVIEW_OUTPUT)
    // A prior run under the old ordering fired review_fix and crashed with implement_fix in flight.
    harness.seedLoopEdge("implement_fix", "review_fix", 1)
    harness.seedReentryPhase("implement_fix", "running", 1, phaseAgent("implement"), null, "review_fix", 1)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPhaseOrder()
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
  fun `an undecidable audit fails its own schema gate rather than wedging the run behind the gate`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") UNDECIDABLE_AUDIT_OUTPUT else defaultPhaseOutput(request))
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    // Blocking AT audit, not at review: a completed-but-undecidable audit could never satisfy the
    // gate and is never itself invalidated, so the run would be unrecoverable in band.
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "off-vocabulary verdict 'pass'")
    assertTrue(
      harness.launchedPhaseOrder().count { it == "audit" } > 1,
      "an undecidable audit must be a bounded in-band retry, not a single terminal settle",
    )
    assertTrue(
      harness.launchedPhaseOrder().none { it == "review" },
      "review must stay unreachable while audit has not settled satisfied",
    )
  }

  @Test
  fun `an audit affirming no unmet criteria settles satisfied even under a synonym verdict`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") SYNONYM_SATISFIED_AUDIT_OUTPUT else defaultPhaseOutput(request))
      },
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(
      harness.launchedPhaseOrder().contains("review"),
      "an audit whose criteria array affirms completeness must not block review on its wording",
    )
  }

  @Test
  fun `a resumed gate migration does not charge the fresh review with the legacy attempt watermark`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    seedThroughImplement(harness)
    // The durable state an earlier migrating load left behind: the review tombstone carrying the
    // legacy generation's exhausted attempt watermark. That load's in-memory generation reset does
    // not survive, and a non-completed tombstone never re-enters the gate-invalidation set.
    harness.seedPhase(
      "review",
      "running",
      FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS,
      REVIEW_INVALIDATION_AGENT_ID,
      null,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(
      harness.launchedPhaseOrder().contains("review"),
      "the migrated review must launch fresh, not re-block as an exhausted fix loop before launch",
    )
  }

  @Test
  fun `a dropped legacy review fix re-entry does not spend the fresh generation's fix pass`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "review") BLOCKER_REVIEW_OUTPUT else defaultPhaseOutput(request))
      },
    )
    seedThroughImplement(harness)
    // A legacy run fired review_fix once and left implement_fix in flight; review never durably
    // completed, so there is no tombstone and the re-entry is dropped for its gate-blocked span.
    harness.seedLoopEdge("implement_fix", "review_fix", 1)
    harness.seedReentryPhase("implement_fix", "running", 1, phaseAgent("implement"), null, "review_fix", 1)

    harness.runner.run(harness.request())

    assertTrue(
      harness.launchedPromptPhaseOrder().contains("implement_fix"),
      "the fresh review's changes_requested must still earn its fix pass; a watermark left behind by " +
        "the dropped re-entry would exhaust the edge cap and advance to validation with the Blocker open",
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
      runnerHarness(
        runtimeConfig = RuntimeHarnessConfig(
          goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
            parentIssueKey = "SKILL-0",
            subtaskId = 1,
            goalBranch = "feat/goal-branch",
            suppressPr = true,
            parentWorkflowId = "wfl-parent",
            reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
          ),
        ),
      ).request(),
    )

    // The goal child truncates at pr and nowhere else: same order, same gates, same backward edges.
    assertEquals(standalone.forwardPhaseIds.dropLast(1), goalChild.forwardPhaseIds)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR, standalone.forwardPhaseIds.last())
    assertEquals(standalone.entryGates, goalChild.entryGates)
    assertEquals(standalone.backwardEdges, goalChild.backwardEdges)
    assertEquals(standalone.loopOnlyPhaseIds, goalChild.loopOnlyPhaseIds)
  }

  private fun seedThroughImplement(harness: RunnerHarness) {
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_DIGEST_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_STEPS_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
  }
}

private const val PASS_TWO_REMEDIATION_SCOPE =
  "Apply bill-code-review mode:inline context:feature-remediation to the subtask's complete delta"
private const val FRESH_IMPLEMENT_FIX_MARKER = "fresh-migration-resume-fix"
private const val FRESH_IMPLEMENT_FIX_OUTPUT =
  """{"contract_version":"0.1","phase_id":"implement_fix","status":"completed","summary":"fix",""" +
    """"produced_outputs":{"changed_files":["$FRESH_IMPLEMENT_FIX_MARKER"],""" +
    """"reconciled_state":{"reconciled":true}}}"""

private const val PREPLAN_DIGEST_OUTPUT = """{"preplan_digest":"scope-boundaries-risks-rollout"}"""
private const val PLAN_STEPS_OUTPUT = """{"plan":"do-the-thing"}"""
private const val CLEAN_REVIEW_OUTPUT = """{"contract_version":"0.1","produced_outputs":{"findings":[]}}"""

// A review carrying an unresolved Blocker, so the verdict derives to changes_requested and the
// review_fix backward edge must fire.
private const val BLOCKER_REVIEW_OUTPUT =
  """{"contract_version":"0.1","produced_outputs":{"findings":""" +
    """[{"severity":"blocker","message":"Foo.kt leaks a connection in the error path"}]}}"""

// Carries a verdict but one outside the closed audit vocabulary, with no criteria array to derive a
// decidable verdict from, so the audit verification-signal gate rejects it.
private const val UNDECIDABLE_AUDIT_OUTPUT = """{"contract_version":"0.1","verdict":"pass"}"""

// Affirms every criterion through the criteria array while wording the verdict off-vocabulary: the
// derived verdict is decidable, so this settles satisfied and review proceeds.
private const val SYNONYM_SATISFIED_AUDIT_OUTPUT =
  """{"contract_version":"0.1","verdict":"Satisfied","produced_outputs":{"unmet_criteria":[]}}"""
