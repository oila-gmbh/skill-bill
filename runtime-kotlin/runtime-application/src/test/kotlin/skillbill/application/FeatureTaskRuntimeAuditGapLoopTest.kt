package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    assertEquals(AGENT_LAUNCHED_PHASES, launched, "a satisfied audit launches the forward pipeline, never re-planning")
    assertEquals(1, launched.count { it == "audit" })
    assertEquals(1, launched.count { it == "plan" })
    assertTrue(
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
    )
    val status = FeatureTaskRuntimeStatusService(
      harness.recorder,
      harness.runInvariantsStore,
      harness.decomposeTerminalRecorder,
    ).status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID))
    assertEquals(true, status?.auditRepair?.firstPassConvergence)
  }

  // (b)+(e) AC2/AC3: one gaps_found iteration re-enters plan -> implement -> review -> audit then
  // advances on satisfied; the re-entered plan and implement briefings carry the failing criteria and
  // the driving gaps_found verdict.
  @Test
  fun `m2 one gaps_found iteration loops implement audit then reviews once and advances`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(2, launched.count { it == "audit" }, "initial audit + one re-audit")
    assertEquals(1, launched.count { it == "plan" }, "the original plan remains immutable")
    assertEquals(2, launched.count { it == "implement" }, "the re-implement re-enters implement once")
    assertEquals(1, harness.launchOrder().count { it == "review" }, "an audit gap never reopens review")
    val firstAudit = launched.indexOf("audit")
    val reImplement = launched.withIndex().first { (index, phase) -> phase == "implement" && index > firstAudit }.index
    assertTrue(reImplement > firstAudit, "implementation remediation runs directly after the audit gap")
    assertTrue(
      harness.launchOrder().indexOf("review") > launched.indexOfLast { it == "audit" },
      "review runs only after the final satisfied audit",
    )
    // (e) the re-entered implement briefing carries the immutable executable plan and latest gaps,
    // without restoring the discarded preplan narrative.
    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    val planBriefing = requireNotNull(briefings["plan"]).briefingText
    val implementBriefing = requireNotNull(briefings["implement"]).briefingText
    assertTrue(!planBriefing.contains(AUDIT_GAP_MESSAGE))
    assertContains(implementBriefing, AUDIT_GAP_MESSAGE)
    assertTrue(!implementBriefing.contains("### from: preplan"))
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
    val status = FeatureTaskRuntimeStatusService(
      harness.recorder,
      harness.runInvariantsStore,
      harness.decomposeTerminalRecorder,
    ).status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID))
    assertEquals(1, status?.auditRepair?.auditGapIterationCount)
    assertEquals(false, status?.auditRepair?.firstPassConvergence, "one remediation round is not first-pass")
  }

  @Test
  fun `final audit repair iteration is committed before review`() {
    // The tree is clean when ownership is baselined at branch setup; the file is this run's work
    // because a writing phase is what makes it appear.
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val delegate = auditGapLauncher(convergeOnAudit = 2)
    var commitMessagesObservedAtReview: List<String> = emptyList()
    val launcher = RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      if (phaseId == "implement" || phaseId == "implement_fix") {
        git.worktreeStatusValue = " M src/Foo.kt"
        git.ownedPathsValue = listOf("src/Foo.kt")
      }
      delegate.launch(request)
    }
    val harness = runnerHarness(
      launcher = launcher,
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        reviewDriver = skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver { request ->
          commitMessagesObservedAtReview = git.createCommitMessages + git.amendCommitMessages
          skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request)
        },
      ),
    )

    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)

    assertEquals(2, commitMessagesObservedAtReview.size)
    assertContains(commitMessagesObservedAtReview[0], "remediation checkpoint")
    assertContains(commitMessagesObservedAtReview[1], "audited implementation checkpoint")
    assertEquals(
      1,
      git.createCommitMessages.size,
      "both checkpoints collapse onto one subtask commit; the second amends it",
    )
    assertEquals(
      3,
      git.stagePathsCalls.size,
      "each checkpoint stages exactly its owned inventory, then finalisation stages the agent's path set",
    )
  }

  @Test
  fun `m2 audit re-entry does not consume a review pass so review keeps the requested mode`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val reviewPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { it.contains("Phase: review") }
    assertEquals(emptyList(), reviewPrompts, "runtime-owned review must not launch a review-phase agent")
    assertNotNull(
      harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("review"),
      "review still completes as a runtime-owned phase",
    )
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
  fun `m2 audit gaps continue past the warn-threshold crossing`() {
    val threshold = FeatureTaskRuntimePhaseWorkflowDefinition.SEMANTIC_LOOP_WARNING_THRESHOLD
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = threshold + 2))

    val report = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
      .mapNotNull { it.edgeIteration }
    assertEquals(
      (1..threshold + 1).toList(),
      loopEdges,
      "the crossing iteration must be recorded as an edge",
    )
    assertTrue(harness.launchedPromptPhaseOrder().any { it == "validate" })
  }

  @Test
  fun `equivalent recurring gaps with an unchanged repository pause as non progress`() {
    val git = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" }
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 3),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertContains(report.pauseReason, "Audit made no progress")
    assertContains(report.pauseReason, "repository fingerprint is unchanged")
    assertContains(report.pauseReason, "retry_fix")
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
    assertEquals(
      listOf(1),
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
        .mapNotNull { it.edgeIteration },
      "the no-progress pause fires before the second recurring edge is recorded",
    )
  }

  @Test
  fun `repository changes between audits allow recurring gaps to continue`() {
    // Each audit iteration reads the fingerprint twice: once to refresh the receipt projection's
    // repository checkpoint at launch (AC-012), then once for audit-gap progress detection. Both reads
    // in an iteration observe the same repository, so the values are paired.
    val git = RecordingWorkflowGitOperations().apply {
      repositoryFingerprintSequence.addAll(
        listOf("before-repair", "before-repair", "after-repair", "after-repair", "after-repair", "after-repair"),
      )
    }
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 3),
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertTrue(
      git.repositoryFingerprintCalls >= 6,
      "projection refreshes may resolve the same repository checkpoint at multiple launch seams",
    )
    assertEquals(3, harness.launchedPromptPhaseOrder().count { it == "audit" })
    assertTrue(harness.launchedPromptPhaseOrder().any { it == "validate" })
  }

  @Test
  fun `recurring audit keeps the cumulative ledger identity and counters`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            auditLaunches += 1
            facts(auditGapsOutput())
          }
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 3) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    // The loop's durable record is the ledger's audit_gap edges: an audit that keeps naming the same
    // criterion drives the edge until its cap, and the criterion identity survives on the audit record
    // rather than in a repair ledger.
    val edges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertTrue(edges.isNotEmpty(), "a recurring unmet criterion must drive the audit_gap edge")
    assertEquals((1..edges.size).toList(), edges.mapNotNull { it.edgeIteration })
    assertTrue(auditLaunches > 1, "the audit ran again after the remediation round")
  }

  // (f) AC5: M1 and M2 compose with independent counters. The re-run after an audit gap passes through
  // review, while the shared pass budget prevents another review after review_fix consumed pass two.
  @Test
  fun `m2 composes with m1 keeping independent loop counters`() {
    var auditLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches < 2) auditGapsOutput() else auditSatisfiedOutput())
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      runtimeConfig = reviewFixRuntimeConfig(2),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    val reviewFixIterations = loopEdges.filter { it.loopId == "review_fix" }.mapNotNull { it.edgeIteration }
    val auditGapIterations = loopEdges.filter { it.loopId == "audit_gap" }.mapNotNull { it.edgeIteration }
    // The audit-gap counter is independent and reached 1; the re-review then approved, so review-fix
    // stopped on the verdict rather than on any count.
    assertEquals(listOf(1), auditGapIterations)
    assertTrue(reviewFixIterations.all { it == 1 })
    assertEquals(1, reviewFixIterations.size, "the approving re-review settled the loop after one fix")
    assertEquals(1, harness.launchOrder().count { it == "review" }, "one fix still runs exactly one review pass")
    assertTrue(harness.launchOrder().contains("verify_findings"), "findings are verified before the fix round")
  }

  @Test
  fun `m2 finished telemetry reflects the audit-gap iteration count`() {
    val looped = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 3))
    looped.runner.run(looped.request)
    val loopedFinished = looped.lifecycle.finishedRecords.single()
    assertEquals(2, loopedFinished.auditGapIterationCount, "two audit-gap iterations are reflected in telemetry")
    assertEquals(false, loopedFinished.auditFirstPassConvergence)
    // The per-item repair counters these once carried counted a repair ledger the runtime no longer
    // keeps, so they report zero rather than being dropped from the relay's wire contract.
    assertEquals(0, loopedFinished.auditRecurringGapCount)
    assertEquals(0, loopedFinished.auditAttemptedRepairItemCount)
    assertEquals(0, loopedFinished.auditResolvedRepairItemCount)

    val clean = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 1))
    clean.runner.run(clean.request)
    val cleanFinished = clean.lifecycle.finishedRecords.single()
    assertEquals(0, cleanFinished.auditGapIterationCount)
    assertEquals(true, cleanFinished.auditFirstPassConvergence)
  }

  // (l) AC17 under the audit-first order: the audit_gap loop runs entirely BEFORE review is reachable,
  // so it can never mint, reset, or replenish a review_fix edge. Once the audit finally satisfies,
  // review gets its full single-re-review allowance exactly once, and a crash inside the audit-gap
  // re-implement does not change that.
  @Test
  fun `m2 audit-gap reentry never touches the review_fix budget across a crash`() {
    var auditLaunches = 0
    var implementLaunches = 0
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
      runtimeConfig = reviewFixRuntimeConfig(2),
    )

    // Run 1: the audit fires gaps_found (audit_gap iteration 1), then the re-implement crashes. Review
    // has not been reachable at any point, so no review_fix edge can exist yet.
    val firstReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals(0, harness.launchOrder().count { it == "review" }, "review is unreachable until the audit satisfies")
    val preGapReviewFix = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "review_fix" }
      .mapNotNull { it.edgeIteration }
    assertEquals(emptyList(), preGapReviewFix, "an audit gap cannot mint a review_fix edge")
    // Run 2 (resume): the crash heals, the audit satisfies, and review takes its single allowance.
    crashOnReImplement = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    val auditGapSeq = loopEdges.first { it.loopId == "audit_gap" }.sequenceNumber
    val reviewFixEdges = loopEdges.filter { it.loopId == "review_fix" }
    assertTrue(
      reviewFixEdges.all { it.sequenceNumber > auditGapSeq },
      "every review_fix edge is minted after the audit_gap loop has already closed",
    )
    assertEquals(
      1,
      harness.launchOrder().count { it == "review" },
      "review runs exactly once after the audit gap closes",
    )
    assertTrue(
      reviewFixEdges.mapNotNull { it.edgeIteration }.all { it <= 1 },
      "review_fix never exceeds the single re-review allowance",
    )
  }

  // Operator stop / crash after the audit_gap re-implement completed and the re-audit had started:
  // resume must continue at audit, not re-block implement because the running audit no longer carries
  // unmet_criteria.
  @Test
  fun `m2 crash after audit_gap implement completes resumes at audit without empty-criteria block`() {
    var auditLaunches = 0
    var crashOnReAudit = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            when {
              auditLaunches == 1 -> facts(auditGapsOutput())
              auditLaunches == 2 && crashOnReAudit -> spawnFailedFacts()
              else -> facts(auditSatisfiedOutput())
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    val firstReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertTrue(
      "carries none" !in firstReport.blockedReason,
      "first interruption is the re-audit spawn failure, not an empty-criteria wedge",
    )
    assertEquals(2, auditLaunches, "gaps_found audit plus one crashed re-audit")

    crashOnReAudit = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    assertTrue(auditLaunches >= 3, "resume relaunches audit rather than blocking on empty criteria")
    assertEquals(
      2,
      harness.launchedPromptPhaseOrder().count { it == "implement" },
      "implement is not relaunched after it already completed the audit_gap span",
    )
  }

  @Test
  fun `gaps_found without scrapeable criterion refs pauses when the repository is unchanged`() {
    var auditLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          auditLaunches += 1
          facts(auditGapsWithoutCanonicalRefsOutput())
        } else {
          facts(defaultPhaseOutput(request))
        }
      },
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertContains(report.pauseReason, "Audit made no progress")
    assertContains(report.pauseReason, "envelope verdict is still gaps_found")
    assertContains(report.pauseReason, "repository fingerprint is unchanged")
  }

  @Test
  fun `substitution without a clear and an unchanged repository pauses as no progress`() {
    var auditLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") {
          auditLaunches += 1
          facts(
            if (auditLaunches == 1) {
              auditCriteriaOutput("AC-002")
            } else {
              auditCriteriaOutput("AC-001", "AC-002")
            },
          )
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertContains(report.pauseReason, "Audit made no progress")
    assertContains(report.pauseReason, "repository fingerprint is unchanged")
  }

  @Test
  fun `a retry_fix grant allows exactly one further round then pauses again`() {
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 99),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ),
    )

    val first = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))
    assertContains(first.pauseReason, "Audit made no progress")
    val auditsBeforeRetry = harness.launchedPromptPhaseOrder().count { it == "audit" }

    val pause = harness.recorder.loadAuditGapPause(WORKFLOW_ID)
    assertNotNull(pause)
    harness.recorder.persistAuditGapPause(
      WORKFLOW_ID,
      pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX),
    )

    val second = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))
    assertContains(second.pauseReason, "Audit made no progress")
    val auditsAfterRetry = harness.launchedPromptPhaseOrder().count { it == "audit" }
    assertEquals(
      auditsBeforeRetry + 1,
      auditsAfterRetry,
      "exactly one further audit round runs on the grant before the repeated no-progress re-pauses",
    )
  }

  @Test
  fun `stale retry_fix after a satisfied audit does not re-force audit_gap`() {
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 99),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))
    val pause = assertNotNull(harness.recorder.loadAuditGapPause(WORKFLOW_ID))
    harness.recorder.persistAuditGapPause(
      WORKFLOW_ID,
      pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX),
    )
    harness.seedPhase("audit", "completed", 2, INVOKED_AGENT, auditSatisfiedOutput())
    val edgesBefore = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .count { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }

    val report = harness.runner.run(harness.request())

    assertTrue(
      report !is FeatureTaskRuntimeRunReport.Blocked ||
        !report.blockedReason.contains("unmet acceptance criteria") &&
        !report.blockedReason.contains("durably readable"),
      "stale retry_fix over a satisfied audit must not block on empty audit-gap criteria: $report",
    )
    val edgesAfter = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .count { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(edgesBefore, edgesAfter, "satisfied audit must not mint another audit_gap edge")
    val consumed = assertNotNull(harness.recorder.loadAuditGapPause(WORKFLOW_ID))
    assertEquals(true, consumed.grantConsumed)
    assertEquals(null, consumed.operatorDecision)
  }

  @Test
  fun `resume with no decision re-pauses without relaunching implement`() {
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 99),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ),
    )

    val first = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))
    assertContains(first.pauseReason, "Audit made no progress")
    val implementsBeforeResume = harness.launchedPromptPhaseOrder().count { it == "implement" }

    val reSurfaced = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))
    assertContains(reSurfaced.pauseReason, "Audit made no progress")
    assertEquals(
      implementsBeforeResume,
      harness.launchedPromptPhaseOrder().count { it == "implement" },
      "a decision-less resume must not relaunch implement",
    )
  }

  @Test
  fun `abandon_subtask on an audit-gap pause terminalizes the subtask`() {
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 99),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))
    val pause = harness.recorder.loadAuditGapPause(WORKFLOW_ID)
    assertNotNull(pause)
    harness.recorder.persistAuditGapPause(
      WORKFLOW_ID,
      pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertContains(report.blockedReason, "abandon_subtask")
  }

  @Test
  fun `a paused audit-gap child surfaces an operator-decision pause with the honest iteration count`() {
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 99),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    val status = FeatureTaskRuntimeStatusService(
      harness.recorder,
      harness.runInvariantsStore,
      harness.decomposeTerminalRecorder,
    ).status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID))
    val resolved = requireNotNull(status)
    assertContains(requireNotNull(resolved.operatorDecisionPause?.reason), "Audit made no progress")
    assertEquals(
      2,
      requireNotNull(resolved.auditRepair).auditGapIterationCount,
      "the pause reports the honest edge iteration the run reached",
    )
  }
}

internal const val AUDIT_GAP_MESSAGE = "AC-2 acceptance criterion is not yet implemented"

internal fun auditGapsOutput(): String = """
  {
    "contract_version": "0.5",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "value": "{\"gaps\":[{\"criterion\":\"AC-002\",\"note\":\"$AUDIT_GAP_MESSAGE\"}],\"non_blocking_findings\":[]}"
    }
  }
""".trimIndent()

internal fun auditGapsWithoutCanonicalRefsOutput(): String = """
  {
    "contract_version": "0.5",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "value": "AC-7 login flow is incomplete; no canonical criterion list."
    }
  }
""".trimIndent()

internal fun auditTwoGapsOutput(): String = """
  {
    "contract_version": "0.5",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "value": "{\"gaps\":[{\"criterion\":\"AC-003\",\"note\":\"$AUDIT_GAP_MESSAGE\"},{\"criterion\":\"AC-002\",\"note\":\"$AUDIT_GAP_MESSAGE\"}],\"non_blocking_findings\":[]}"
    }
  }
""".trimIndent()

internal fun auditSatisfiedOutput(): String = """
  {
    "contract_version": "0.5",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Every acceptance criterion is met.",
    "verdict": "satisfied",
    "produced_outputs": {
      "value": "{\"gaps\":[],\"non_blocking_findings\":[]}"
    }
  }
""".trimIndent()

internal fun auditCriteriaOutput(vararg criteria: String): String {
  val gapEntries = criteria.joinToString(",") {
    """{\"criterion\":\"$it\",\"note\":\"$AUDIT_GAP_MESSAGE\"}"""
  }
  return """
  {
    "contract_version": "0.5",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "value": "{\"gaps\":[$gapEntries],\"non_blocking_findings\":[]}"
    }
  }
  """.trimIndent()
}

internal fun auditGapLauncher(convergeOnAudit: Int): RuntimeRecordingLauncher {
  var auditLaunches = 0
  return RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "audit") {
      auditLaunches += 1
      facts(
        if (auditLaunches < convergeOnAudit) {
          auditGapsOutput()
        } else {
          auditSatisfiedOutput()
        },
      )
    } else {
      facts(defaultPhaseOutput(request))
    }
  }
}

// AC-005: two gaps_found rounds deliver the prior-gap memory projection on the second audit_gap
// implement re-entry (with sticky ids from the two prior audits) and on the audit that follows it.
@Test
fun `prior-gap memory appears on the second audit_gap implement and the audit that follows it`() {
  val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 3))

  val report = harness.runner.run(harness.request())

  assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
  val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
  val implementBriefing = requireNotNull(briefings["implement"]).briefingText
  val auditBriefing = requireNotNull(briefings["audit"]).briefingText
  // The second audit_gap implement (round 2) carries the memory projection; sticky ids come from the
  // two prior audits both reporting AC-002.
  assertContains(implementBriefing, "prior_gap_memory")
  assertContains(implementBriefing, "prior_audit_values")
  assertContains(implementBriefing, "AC-002")
  assertContains(implementBriefing, "re-justify recurrence against prior audit prose")
  assertContains(auditBriefing, "prior_gap_memory")
  assertContains(auditBriefing, "prior_audit_values")
}

// AC-004: an in-flight workflow without a second comparable audit still completes with empty memory;
// the first audit_gap implement delivers the memory projection with no sticky ids rather than failing.
@Test
fun `in-flight workflow without a second comparable audit completes with empty sticky memory`() {
  val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))

  val report = harness.runner.run(harness.request())

  assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
  val implementBriefing = requireNotNull(
    harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["implement"],
  ).briefingText
  assertContains(implementBriefing, "prior_gap_memory")
  assertContains(implementBriefing, "prior_audit_values")
}

class FeatureTaskRuntimeAuditGapSharedEvidenceTest {
  @Test
  fun `audit_gap at an unchanged checkpoint reuses shared evidence without a second derivation`() {
    val store = CountingSharedEvidenceStore()
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 2),
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = kotlin.io.path.createTempDirectory("audit-gap-shared-evidence"),
        sharedEvidenceResolver = store,
        diffResolver = object : skillbill.ports.diff.DiffResolverPort {
          override fun runProcess(args: List<String>, workDir: java.nio.file.Path): String =
            "diff --git a/src/A.kt b/src/A.kt\n@@ -1 +1 @@\n+x\n"
        },
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertTrue(store.derivationCount >= 1)
    assertTrue(store.reuseCount >= 1, "unchanged checkpoint must reuse at least once")
  }
}
