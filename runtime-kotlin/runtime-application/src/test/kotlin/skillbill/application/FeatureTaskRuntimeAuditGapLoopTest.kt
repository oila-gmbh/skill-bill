package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The audit-gap context-reuse loop exercised over the production transition topology
// (audit --gaps_found--> implement -> review -> audit), with a
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
    assertEquals(1, launched.count { it == "plan" }, "the original plan remains immutable")
    assertEquals(2, launched.count { it == "implement" }, "the re-implement re-enters implement once")
    assertEquals(2, launched.count { it == "review" }, "the re-run passes back through review")
    // The reopened span runs plan -> implement -> review -> audit after the first audit.
    val firstAudit = launched.indexOf("audit")
    val reImplement = launched.withIndex().first { (index, phase) -> phase == "implement" && index > firstAudit }.index
    assertTrue(reImplement > firstAudit, "implementation remediation runs directly after the audit gap")
    // (e) the re-entered implement briefing carries immutable planning context and the latest gaps.
    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    val planBriefing = requireNotNull(briefings["plan"]).briefingText
    val implementBriefing = requireNotNull(briefings["implement"]).briefingText
    assertTrue(!planBriefing.contains(AUDIT_GAP_MESSAGE))
    assertContains(implementBriefing, AUDIT_GAP_MESSAGE)
    assertContains(implementBriefing, "### from: preplan")
    assertContains(implementBriefing, "### from: plan")
    val planningRecords = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertEquals(1, requireNotNull(planningRecords["preplan"]).attemptCount)
    assertEquals(1, requireNotNull(planningRecords["plan"]).attemptCount)
    assertEquals(null, planningRecords.getValue("preplan").loopId)
    assertEquals(null, planningRecords.getValue("plan").loopId)
    // (AC7) the audit_gap loop edge is recorded once with iteration 1.
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
  }

  @Test
  fun `m2 audit re-entry uses the bounded inline remediation review mode`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.DELEGATED),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val reviewPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { it.contains("Phase: review") }
    assertEquals(2, reviewPrompts.size)
    assertContains(reviewPrompts[0], "bill-code-review mode:delegated")
    assertContains(reviewPrompts[1], "bill-code-review mode:inline")
  }

  // (c) AC2: convergence on the last allowed (2nd) iteration still advances.
  @Test
  fun `m2 converges on the last allowed iteration and advances`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 3))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(3, launched.count { it == "audit" }, "initial audit + two re-audits")
    assertEquals(1, launched.count { it == "plan" }, "planning is not regenerated")
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(listOf(1, 2), loopEdges.mapNotNull { it.edgeIteration })
  }

  @Test
  fun `m2 audit gaps continue until a later audit satisfies the criteria`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 99))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "plan" })
    assertEquals(99, launched.count { it == "audit" })
    assertTrue(launched.any { it == "validate" })
    val auditRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["audit"])
    assertEquals("completed", auditRecord.status)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(98, loopEdges.maxOf { requireNotNull(it.edgeIteration) })
  }

  // (f) AC5: M1 and M2 compose with independent counters. The re-run after an audit gap passes through
  // review, while the shared pass budget prevents another review after review_fix consumed pass two.
  @Test
  fun `m2 composes with m1 keeping independent loop counters`() {
    var auditLaunches = 0
    var reviewLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          // The initial review demands one fix, then pass two approves.
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
    // The audit-gap counter is independent and reached 1; review-fix consumed the only later review.
    assertEquals(listOf(1), auditGapIterations)
    assertTrue(reviewFixIterations.all { it == 1 })
    assertEquals(1, reviewFixIterations.size, "the shared two-pass budget prevents a third review")
    assertEquals(2, harness.launchedPromptPhaseOrder().count { it == "review" }, "review-fix consumed pass two")
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

  // (h) AC4: a crash mid-loopback (the audit-gap re-implement spawn-fails) resumes the unfinished
  // reentry span without reusing the audit verdict that caused it, then converges.
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

    // Run 1: the gaps_found audit fires the edge (iteration 1) -> implementation remediation crashes.
    val firstReport = harness.runner.run(harness.request())
    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("implement", firstBlocked.lastIncompletePhase)
    // The original plan stays untouched; the implement destination and durable ledger carry iteration 1.
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals(null, planRecord.loopId)
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("audit_gap", implementRecord.loopId)
    assertEquals(1, implementRecord.edgeIteration)

    // Run 2 (resume): the crash heals; review and audit from before edge 1 are stale and must rerun.
    // The satisfied re-audit completes edge 1 without minting edge 2.
    crashOnReImplement = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
      .mapNotNull { it.edgeIteration }
    assertEquals(
      listOf(1),
      edgeIterations,
      "resume finishes the in-flight edge instead of reusing its stale driving verdict",
    )
    assertEquals(2, auditLaunches, "the original audit and the resumed re-audit both ran")
    assertEquals(2, harness.launchedPromptPhaseOrder().count { it == "review" })
  }

  @Test
  fun `ledger-only audit gap resumes at implement without relaunching planning`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") auditSatisfiedOutput() else validJsonOutput(phaseId))
      },
    )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, auditGapsOutput())
    harness.seedLoopEdge("implement", "audit_gap", 1)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals("implement", launched.first())
    assertTrue(launched.none { it == "preplan" || it == "plan" })
    assertEquals(
      listOf(1),
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
        .mapNotNull { it.edgeIteration },
    )
  }

  @Test
  fun `completed audit gap implement without complete ledger resumes at review`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") auditSatisfiedOutput() else validJsonOutput(phaseId))
      },
    )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, auditGapsOutput())
    harness.seedLoopEdge("implement", "audit_gap", 1)
    harness.seedReentryPhase(
      "implement",
      "completed",
      2,
      INVOKED_AGENT,
      validJsonOutput("implement"),
      "audit_gap",
      1,
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals("review", launched.first())
    assertTrue(launched.none { it == "preplan" || it == "plan" || it == "implement" })
  }

  @Test
  fun `m2 high-iteration audit_gap loop keeps reconciling on resume`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 99))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "completed", 2, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 2)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertTrue(launched.none { it == "plan" })
    assertTrue(launched.any { it == "audit" })
    assertTrue(launched.any { it == "validate" })
  }

  @Test
  fun `legacy audit-to-plan record blocks instead of reusing overwritten planning context`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedReentryPhase("plan", "completed", 2, INVOKED_AGENT, validJsonOutput("plan"), "audit_gap", 1)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "completed", 2, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 1)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "cannot prove original planning-context identity")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "preplan" || it == "plan" })
  }

  @Test
  fun `m2 formerly blocked audit resumes reconciliation and preserves the branch`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 99))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 3, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 2, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "blocked", 3, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 2)
    harness.recorder.recordResolvedBranch(WORKFLOW_ID, FeatureTaskRuntimeResolvedBranch("feat/persisted-branch"))

    val report = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals("feat/persisted-branch", report.resolvedBranch)
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

  @Test
  fun `gaps_found rejects absent empty and malformed unmet criteria before remediation`() {
    val invalidAuditOutputs = listOf(
      invalidGapsFoundOutput("{}"),
      invalidGapsFoundOutput("{\"unmet_criteria\":[]}"),
      invalidGapsFoundOutput("{\"unmet_criteria\":[{\"message\":\"AC4\"},{}]}"),
    )
    invalidAuditOutputs.forEach { auditOutput ->
      val harness = runnerHarness(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          facts(if (phaseId == "audit") auditOutput else validJsonOutput(phaseId))
        },
      )

      val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
        harness.runner.run(harness.request()),
      )

      assertEquals("audit", blocked.lastIncompletePhase)
      assertTrue(harness.launchedPromptPhaseOrder().count { it == "implement" } == 1)
      assertTrue(
        harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
          .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
      )
    }
  }

  @Test
  fun `inferred gaps_found rejects malformed unmet criteria before remediation`() {
    val malformedInferredOutput =
      """{"contract_version":"0.1","phase_id":"audit","status":"completed","summary":"gap",""" +
        """"produced_outputs":{"unmet_criteria":[{"message":"AC4"},{}]}}"""
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "audit") malformedInferredOutput else validJsonOutput(phaseId))
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertTrue(
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
    )
  }

  @Test
  fun `audit-gap recovery rejects incompatible persisted planning output`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, validJsonOutput("implement"))
    harness.seedPhase("review", "completed", 1, INVOKED_AGENT, validJsonOutput("review"))
    harness.seedReentryPhase("audit", "completed", 2, INVOKED_AGENT, auditGapsOutput(), "audit_gap", 1)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "valid completed original 'preplan' output")
    assertTrue(harness.launchedPromptPhaseOrder().isEmpty())
  }

  private fun invalidGapsFoundOutput(producedOutputs: String): String =
    """{"contract_version":"0.1","phase_id":"audit","status":"completed","verdict":"gaps_found",""" +
      """"summary":"gap","produced_outputs":$producedOutputs}"""

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

  // (l) AC17: audit-gap re-entry does not replenish the globally bounded review-fix budget.
  @Test
  fun `m2 audit-gap reentry preserves exhausted review_fix budget across a crash`() {
    var auditLaunches = 0
    var implementLaunches = 0
    var reviewFixesThisSegment = 0
    var crashOnReImplement = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          // The pre-gap segment consumes review pass two before approving.
          "review" -> {
            val segmentCap = 1
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

    // Run 1: review_fix consumes pass two, the audit fires gaps_found (audit_gap iteration 1, which
    // resets+durably-clears review_fix), then the re-implement crashes.
    val firstReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    val preGapReviewFix = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "review_fix" }
      .mapNotNull { it.edgeIteration }
    assertEquals(listOf(1), preGapReviewFix, "review-fix consumed the single remaining review pass")
    // Run 2 (resume): the crash heals and the durable budget prevents any third review launch.
    crashOnReImplement = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    val auditGapSeq = loopEdges.first { it.loopId == "audit_gap" }.sequenceNumber
    val postResetReviewFix = loopEdges.filter { it.loopId == "review_fix" && it.sequenceNumber > auditGapSeq }
    assertTrue(postResetReviewFix.size <= 1, "the cap may settle but cannot relaunch beyond the remaining pass")
    assertEquals(
      2,
      harness.launcher.requests.count { it.skillRunRequest.promptOverride.orEmpty().contains("Phase: review") },
      "the durable budget prevents a third review launch",
    )
    // The cap is still enforced within a single audit-gap iteration: no review_fix edge exceeds one.
    assertTrue(
      loopEdges.filter { it.loopId == "review_fix" }.mapNotNull { it.edgeIteration }.all { it <= 1 },
      "review_fix never exceeds the single re-review allowance",
    )
  }
}

// The unique unmet-criterion message a gaps_found audit carries, so the implementation-remediation briefing
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
// launch index at which it first reports satisfied). Every other
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
