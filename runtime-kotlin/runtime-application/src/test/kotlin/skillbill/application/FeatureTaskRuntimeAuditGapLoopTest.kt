package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.review.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// SKILL-85 Subtask 5 (M2): the audit-gap re-plan/re-implement loopback exercised over the production
// transition topology (audit --gaps_found--> plan -> implement -> review -> audit, capped at 2), with a
// fake launcher. Mirrors the M1 review_fix matrix in FeatureTaskRuntimeRunnerTest, reusing its shared
// package-internal harness/launcher/output helpers.
class FeatureTaskRuntimeAuditGapLoopTest {
  // (a) AC1/AC2: a satisfied audit advances straight to validate; the audit_gap edge never fires.
  @Test
  fun `m2 satisfied audit advances to validate without firing the loop`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 1))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(ALL_PHASES, launched, "a satisfied audit launches the forward pipeline, never re-planning")
    assertEquals(1, launched.count { it == "audit" })
    assertEquals(1, launched.count { it == "plan" })
    assertTrue(
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
    )
  }

  // (b)+(e) AC2/AC3: one gaps_found iteration re-enters plan -> implement -> review -> audit then
  // advances on satisfied; the re-entered plan and implement briefings carry the failing criteria and
  // the driving gaps_found verdict.
  @Test
  fun `m2 one gaps_found iteration loops plan implement review audit then advances`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(2, launched.count { it == "audit" }, "initial audit + one re-audit")
    assertEquals(2, launched.count { it == "plan" }, "the re-plan re-enters plan once")
    assertEquals(2, launched.count { it == "implement" }, "the re-implement re-enters implement once")
    assertEquals(2, launched.count { it == "review" }, "the re-run passes back through review")
    // The reopened span runs plan -> implement -> review -> audit after the first audit.
    val firstAudit = launched.indexOf("audit")
    val rePlan = launched.withIndex().first { (index, phase) -> phase == "plan" && index > firstAudit }.index
    val reImplement = launched.withIndex().first { (index, phase) -> phase == "implement" && index > rePlan }.index
    assertTrue(reImplement > rePlan, "the re-implement runs after the re-plan")
    // (e) the re-entered plan AND implement briefings carry the failing criteria and driving verdict.
    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    val planBriefing = requireNotNull(briefings["plan"]).briefingText
    val implementBriefing = requireNotNull(briefings["implement"]).briefingText
    assertContains(planBriefing, AUDIT_GAP_MESSAGE)
    assertContains(planBriefing, "driving_verdict: gaps_found")
    assertContains(implementBriefing, AUDIT_GAP_MESSAGE)
    // (AC7) the audit_gap loop edge is recorded once with iteration 1.
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
  }

  @Test
  fun `m2 audit re-entry retains the selected review mode for every review launch`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val reviewPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { it.contains("Phase: review") }
    assertEquals(2, reviewPrompts.size)
    assertTrue(reviewPrompts.all { it.contains("bill-code-review execution-mode:delegated") })
  }

  // (c) AC2: convergence on the last allowed (2nd) iteration still advances.
  @Test
  fun `m2 converges on the last allowed iteration and advances`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 3))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(3, launched.count { it == "audit" }, "initial audit + two re-audits")
    assertEquals(3, launched.count { it == "plan" }, "two re-plans before converging")
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(listOf(1, 2), loopEdges.mapNotNull { it.edgeIteration })
  }

  // (d) AC6: two unsuccessful iterations block loudly with audit_gap + iteration + unmet criteria,
  // never advancing to validate.
  @Test
  fun `m2 cap exhaustion blocks loudly with audit_gap iteration and unmet criteria`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 99))

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "audit_gap")
    assertContains(blocked.blockedReason, "2")
    assertContains(blocked.blockedReason, "gaps_found")
    assertContains(blocked.blockedReason, AUDIT_GAP_MESSAGE)
    // Never advanced to validate on unmet acceptance criteria.
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
    // The audit_gap edge has perEdgeCap=2: it fires twice (two re-plans), so plan launches the initial
    // pass plus the two re-plans before the third audit trips the cap and blocks.
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(3, launched.count { it == "plan" }, "plan ran the initial pass plus cap re-plans")
    assertEquals(3, launched.count { it == "audit" }, "audit ran cap+1 times: the last triggers the block")
    // Durable terminal blocked record carries the structured loop context.
    val auditRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"])
    assertEquals("blocked", auditRecord.status)
    assertEquals("audit_gap", auditRecord.loopId)
    assertEquals(2, auditRecord.edgeIteration)
  }

  // (f) AC5: M1 and M2 compose with independent counters. The re-run after an audit gap passes through
  // review, which itself runs a review_fix iteration; the review_fix counter resets per audit-gap
  // iteration while the audit_gap counter is independent.
  @Test
  fun `m2 composes with m1 keeping independent loop counters`() {
    var auditLaunches = 0
    var reviewLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          // Each review launch demands one fix (changes_requested), then approves on the immediate
          // re-review; this yields exactly one review_fix iteration per audit-gap iteration.
          "review" -> {
            reviewLaunches += 1
            facts(reviewFindingsOutput(changesRequested = reviewLaunches % 2 == 1))
          }
          // The first audit reports a gap; the second is satisfied (one audit-gap iteration).
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    val reviewFixIterations = loopEdges.filter { it.loopId == "review_fix" }.mapNotNull { it.edgeIteration }
    val auditGapIterations = loopEdges.filter { it.loopId == "audit_gap" }.mapNotNull { it.edgeIteration }
    // The audit_gap counter is independent and reached 1; the review_fix counter is per-visit and never
    // exceeds 1 because each visit converges on a single fix (it resets across the audit-gap iteration).
    assertEquals(listOf(1), auditGapIterations)
    assertTrue(reviewFixIterations.all { it == 1 }, "review_fix resets per audit-gap iteration, never accruing")
    assertEquals(2, reviewFixIterations.size, "one review_fix iteration per review visit (initial + re-run)")
  }

  // (g) AC4: the re-entered implement is idempotent — a re-implement that omits the reconciliation
  // report blocks loudly on the reconciliation gate rather than silently double-applying.
  @Test
  fun `m2 re-implement without a reconciliation report blocks on the idempotency gate`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(auditGapsOutput())
          "implement" -> {
            implementLaunches += 1
            // The first implement reconciles; the audit-gap re-implement omits reconciled_state.
            if (implementLaunches == 1) {
              facts(validJsonOutput(phaseId))
            } else {
              facts(
                """{"contract_version":"0.1","phase_id":"implement","status":"completed",""" +
                  """"summary":"re-impl","produced_outputs":{"changed_files":["src/Foo.kt"]}}""",
              )
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "reconcil")
  }

  // (h) AC4: a crash mid-loopback (the audit-gap re-implement spawn-fails) resumes at the correct phase
  // with the audit_gap watermark preserved, then converges.
  @Test
  fun `m2 crash during the re-implement resumes with the loop context preserved and converges`() {
    var implementLaunches = 0
    var auditLaunches = 0
    var crashOnReImplement = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 2 && crashOnReImplement) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: the gaps_found audit fires the edge (iteration 1) -> re-plan -> re-implement crashes.
    val firstReport = harness.runner.run(harness.request())
    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("implement", firstBlocked.lastIncompletePhase)
    // The edge destination (plan) carries the audit_gap watermark the resume reconstruction relies on;
    // the durable ledger likewise records iteration 1, so the cap is never reset on resume.
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("audit_gap", planRecord.loopId)
    assertEquals(1, planRecord.edgeIteration)

    // Run 2 (resume): the crash heals; on resume the completed-but-still-gaps audit re-settles its
    // verdict and fires a SECOND audit-gap iteration from the PRESERVED watermark (edge 2, not a reset
    // back to 1), the re-audit is then satisfied, and the run completes. The two-iteration ledger makes
    // the cross-crash de-duplication load-bearing: the iterations advance monotonically, never repeat,
    // and stay within the cap of 2 — a watermark reset on resume would have re-fired iteration 1.
    crashOnReImplement = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
      .mapNotNull { it.edgeIteration }
    assertEquals(
      listOf(1, 2),
      edgeIterations,
      "the watermark is preserved across the crash: edge 2 follows edge 1, never reset",
    )
  }

  // (i) AC6: an audit_gap loop that already burned its cap re-blocks on resume without relaunching.
  @Test
  fun `m2 cap-exhausted audit_gap loop re-blocks on resume without relaunching`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 99))
    // Seed a loop that already burned its cap: the plan phase carries the audit_gap watermark at the
    // perEdgeCap (2), and the audit carries a gaps_found output, modelling a prior exhausted run.
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, """{"preplan":"done"}""")
    harness.seedReentryPhase("plan", "completed", 2, INVOKED_AGENT, """{"plan":"done"}""", "audit_gap", 2)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "completed", 2, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 2)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "audit_gap")
    assertContains(blocked.blockedReason, "2")
    // "without relaunching": the cap-exhausted audit_gap loop's own phases (its destination plan and
    // source audit) must NOT relaunch on resume — the run re-blocks before re-entering them, and never
    // advances to validate.
    val launched = harness.launchedPromptPhaseOrder()
    assertTrue(launched.none { it == "plan" }, "the cap-exhausted loop's plan never relaunches on resume")
    assertTrue(launched.none { it == "audit" }, "the cap-exhausted loop's audit never relaunches on resume")
    assertTrue(launched.none { it == "validate" })
  }

  // (j) AC1: an audit that reports completed without ANY verification signal (no verdict, no
  // unmet_criteria array) blocks via the audit verification-signal gate rather than silently advancing.
  @Test
  fun `m2 audit without a verification signal blocks on the gate`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          facts(
            """{"contract_version":"0.1","phase_id":"audit","status":"completed",""" +
              """"summary":"Looks complete to me.","produced_outputs":{"notes":"all good"}}""",
          )
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "verification signal")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
  }

  // (k) AC7: finished telemetry reflects the audit-gap iteration count (>0 when the loop ran, 0 clean).
  @Test
  fun `m2 finished telemetry reflects the audit-gap iteration count`() {
    val looped = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 3))
    looped.runner.run(looped.request)
    val loopedFinished = looped.lifecycle.finishedRecords.single()
    assertEquals(2, loopedFinished.auditGapIterationCount, "two audit-gap iterations are reflected in telemetry")

    val clean = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 1))
    clean.runner.run(clean.request)
    assertEquals(0, clean.lifecycle.finishedRecords.single().auditGapIterationCount)
  }

  // (l) AC5/SKILL-85-F-001: the review_fix per-iteration reset is DURABLE across a crash. The pre-gap
  // pass burns review_fix to its cap (3), the audit_gap edge then fires and resets review_fix, and the
  // run crashes after the reset but before the reopened span re-runs. On resume the cleared watermark
  // must NOT be re-imported from the durable per-phase records: the resumed audit-gap iteration gets a
  // FRESH review_fix budget, so its re-review can fire a fix again (edge iteration 1) and converge,
  // rather than immediately re-blocking on the cap "after 3 iteration(s)".
  @Test
  fun `m2 audit-gap reset of review_fix survives a crash and grants a fresh per-iteration budget`() {
    var auditLaunches = 0
    var implementLaunches = 0
    var reviewFixesThisSegment = 0
    var crashOnReImplement = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          // The pre-gap segment (no gaps_found audit yet) burns review_fix to its cap of 3 fixes before
          // approving; the resumed audit-gap segment needs only ONE fix, proving the budget is fresh.
          "review" -> {
            val segmentCap = if (auditLaunches == 0) 3 else 1
            if (reviewFixesThisSegment < segmentCap) {
              reviewFixesThisSegment += 1
              facts(reviewFindingsOutput(changesRequested = true))
            } else {
              facts(reviewFindingsOutput(changesRequested = false))
            }
          }
          // The first audit reports a gap (resetting the review_fix segment); the second is satisfied.
          "audit" -> {
            auditLaunches += 1
            reviewFixesThisSegment = 0
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          // The audit-gap re-implement (the second implement launch) crashes once, AFTER the audit_gap
          // edge fired and reset review_fix; it heals on resume.
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 2 && crashOnReImplement) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: review_fix burns to its cap (3), the audit fires gaps_found (audit_gap iteration 1, which
    // resets+durably-clears review_fix), then the re-implement crashes.
    val firstReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    val preGapReviewFix = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "review_fix" }
      .mapNotNull { it.edgeIteration }
    assertEquals(listOf(1, 2, 3), preGapReviewFix, "the pre-gap pass burned review_fix to its cap of 3")
    // The reset durably cleared the nested loop's per-phase watermark: review/implement_fix no longer
    // carry the stale review_fix=3 context that resume reconstruction would otherwise re-import.
    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertEquals(null, requireNotNull(records["review"]).loopId, "the reset cleared review's stale review_fix context")
    assertEquals(null, requireNotNull(records["implement_fix"]).loopId)

    // Run 2 (resume): the crash heals; the resumed audit-gap iteration's re-review fires a fix at a
    // FRESH review_fix iteration 1 (not a 4th edge that would block on the cap) and converges.
    crashOnReImplement = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    val auditGapSeq = loopEdges.first { it.loopId == "audit_gap" }.sequenceNumber
    val postResetReviewFix = loopEdges.filter { it.loopId == "review_fix" && it.sequenceNumber > auditGapSeq }
    assertEquals(
      listOf(1),
      postResetReviewFix.mapNotNull { it.edgeIteration },
      "the resumed audit-gap iteration fired a FRESH review_fix at iteration 1, not a capped 4th edge",
    )
    // The cap is still enforced WITHIN a single audit-gap iteration: no review_fix edge exceeds 3.
    assertTrue(
      loopEdges.filter { it.loopId == "review_fix" }.mapNotNull { it.edgeIteration }.all { it <= 3 },
      "review_fix never exceeds its per-iteration cap of 3",
    )
  }
}

// The unique unmet-criterion message a gaps_found audit carries, so the re-plan/re-implement briefing
// and a cap-exhaustion block can be asserted to contain it.
internal const val AUDIT_GAP_MESSAGE = "AC-2 acceptance criterion is not yet implemented"

// A schema-valid audit output whose unmet_criteria drive the verdict: a non-empty array => gaps_found
// (the runtime classifies from the criteria, no top-level verdict needed), an empty array => satisfied.
internal fun auditGapsOutput(): String = """
  {
    "contract_version": "0.1",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "produced_outputs": {"unmet_criteria": [{"message": "$AUDIT_GAP_MESSAGE"}]}
  }
""".trimIndent()

internal fun auditSatisfiedOutput(): String = """
  {
    "contract_version": "0.1",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Every acceptance criterion is met.",
    "produced_outputs": {"unmet_criteria": []}
  }
""".trimIndent()

// The real M2 audit_gap launcher: audit returns gaps_found until [convergeOnAudit] (1-based audit
// launch index at which it first reports satisfied); a value above the cap never converges. Every other
// phase returns its schema-valid reconciled output (review carries an empty findings array so its own
// gate passes and the re-run advances through review without a review_fix detour).
internal fun auditGapLauncher(convergeOnAudit: Int): RuntimeRecordingLauncher {
  var auditLaunches = 0
  return RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "audit") {
      auditLaunches += 1
      facts(if (auditLaunches < convergeOnAudit) auditGapsOutput() else auditSatisfiedOutput())
    } else {
      facts(validJsonOutput(phaseId))
    }
  }
}
