package skillbill.engine

import skillbill.engine.featuretask.ApprovingReviewDriverStub
import skillbill.engine.featuretask.FeatureTaskRuntimeReviewDriver
import skillbill.engine.featuretask.FeatureTaskRuntimeStatusService
import skillbill.engine.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.FeatureTaskRuntimeStatusRequest
import skillbill.ports.diff.DiffResolverPort
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditGapLoopTest {
  @Test
  fun `m2 satisfied audit advances to validate without firing the loop`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = auditGapLauncher(convergeOnAudit = 1)))

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

  @Test
  fun `m2 one gaps_found iteration loops implement audit then reviews once and advances`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = auditGapLauncher(convergeOnAudit = 2)))

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
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        reviewDriver = FeatureTaskRuntimeReviewDriver { request ->
          commitMessagesObservedAtReview = git.createCommitMessages + git.amendCommitMessages
          ApprovingReviewDriverStub.run(request)
        },
      ).copy(launcher = launcher),
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
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = auditGapLauncher(convergeOnAudit = 2)))

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

  @Test
  fun `m2 converges on the last allowed iteration and advances`() {
    val harness =
      runnerHarness(RuntimeHarnessConfig(launcher = auditGapLauncher(convergeOnAudit = 3, progressiveGaps = true)))

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
    val harness =
      runnerHarness(
        RuntimeHarnessConfig(launcher = auditGapLauncher(convergeOnAudit = threshold + 2, progressiveGaps = true)),
      )

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
      RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)).copy(
        launcher = auditGapLauncher(convergeOnAudit = 3),
      ),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertContains(report.pauseReason, "Audit made no progress")
    assertContains(report.pauseReason, "unresolved acceptance criteria did not strictly decrease")
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
  fun `repository changes do not allow recurring gaps to continue`() {
    val git = RecordingWorkflowGitOperations().apply {
      repositoryFingerprintSequence.addAll(
        listOf("before-repair", "before-repair", "after-repair", "after-repair", "after-repair", "after-repair"),
      )
    }
    val harness = runnerHarness(
      RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = git)).copy(
        launcher = auditGapLauncher(convergeOnAudit = 3),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertEquals("after-repair", harness.recorder.loadAuditGapProgress(WORKFLOW_ID)?.repositoryFingerprint)
    assertEquals(2, harness.launchedPromptPhaseOrder().count { it == "audit" })
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "validate" })
  }

  @Test
  fun `recurring audit keeps the cumulative ledger identity and counters`() {
    var auditLaunches = 0
    var implementLaunches = 0
    val harness = runnerHarness(
      RuntimeHarnessConfig(
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
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    val edges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertTrue(edges.isNotEmpty(), "a recurring unmet criterion must drive the audit_gap edge")
    assertEquals((1..edges.size).toList(), edges.mapNotNull { it.edgeIteration })
    assertEquals(2, auditLaunches)
    assertEquals(1, edges.size)
  }

  @Test
  fun `m2 composes with m1 keeping independent loop counters`() {
    var auditLaunches = 0
    val harness = runnerHarness(
      reviewFixRuntimeConfig(2).copy(
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
      ),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    val reviewFixIterations = loopEdges.filter { it.loopId == "review_fix" }.mapNotNull { it.edgeIteration }
    val auditGapIterations = loopEdges.filter { it.loopId == "audit_gap" }.mapNotNull { it.edgeIteration }
    assertEquals(listOf(1), auditGapIterations)
    assertTrue(reviewFixIterations.all { it == 1 })
    assertEquals(1, reviewFixIterations.size, "the approving re-review settled the loop after one fix")
    assertEquals(1, harness.launchOrder().count { it == "review" }, "one fix still runs exactly one review pass")
    assertTrue(harness.launchOrder().contains("verify_findings"), "findings are verified before the fix round")
  }

  @Test
  fun `m2 finished telemetry reflects the audit-gap iteration count`() {
    val looped = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 3, progressiveGaps = true))
    looped.runner.run(looped.request)
    val loopedFinished = looped.lifecycle.finishedRecords.single()
    assertEquals(2, loopedFinished.auditGapIterationCount, "two audit-gap iterations are reflected in telemetry")
    assertEquals(false, loopedFinished.auditFirstPassConvergence)
    assertEquals(0, loopedFinished.auditRecurringGapCount)
    assertEquals(0, loopedFinished.auditAttemptedRepairItemCount)
    assertEquals(0, loopedFinished.auditResolvedRepairItemCount)

    val clean = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 1, progressiveGaps = true))
    clean.runner.run(clean.request)
    val cleanFinished = clean.lifecycle.finishedRecords.single()
    assertEquals(0, cleanFinished.auditGapIterationCount)
    assertEquals(true, cleanFinished.auditFirstPassConvergence)
  }

  @Test
  fun `m2 audit-gap reentry never touches the review_fix budget across a crash`() {
    var auditLaunches = 0
    var implementLaunches = 0
    var crashOnReImplement = true
    val harness = runnerHarness(
      reviewFixRuntimeConfig(2).copy(
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
      ),
    )

    val firstReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals(0, harness.launchOrder().count { it == "review" }, "review is unreachable until the audit satisfies")
    val preGapReviewFix = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "review_fix" }
      .mapNotNull { it.edgeIteration }
    assertEquals(emptyList(), preGapReviewFix, "an audit gap cannot mint a review_fix edge")
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

  @Test
  fun `m2 crash after audit_gap implement completes resumes at audit without empty-criteria block`() {
    var auditLaunches = 0
    var crashOnReAudit = true
    val harness = runnerHarness(
      RuntimeHarnessConfig(
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
      ),
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
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ).copy(
        launcher = RuntimeRecordingLauncher { request ->
          val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
          if (phaseId == "audit") {
            auditLaunches += 1
            facts(auditGapsWithoutCanonicalRefsOutput())
          } else {
            facts(defaultPhaseOutput(request))
          }
        },
      ),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertContains(report.pauseReason, "Audit made no progress")
    assertContains(report.pauseReason, "unresolved acceptance criteria did not strictly decrease")
    assertContains(report.pauseReason, "unresolved acceptance criteria did not strictly decrease")
  }

  @Test
  fun `substitution without a clear and an unchanged repository pauses as no progress`() {
    var auditLaunches = 0
    val harness = runnerHarness(
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ).copy(
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
      ),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertContains(report.pauseReason, "Audit made no progress")
    assertContains(report.pauseReason, "unresolved acceptance criteria did not strictly decrease")
  }

  @Test
  fun `a retry_fix grant allows exactly one further round then pauses again`() {
    val harness = runnerHarness(
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ).copy(launcher = auditGapLauncher(convergeOnAudit = 99)),
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
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ).copy(launcher = auditGapLauncher(convergeOnAudit = 99)),
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
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ).copy(launcher = auditGapLauncher(convergeOnAudit = 99)),
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
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ).copy(launcher = auditGapLauncher(convergeOnAudit = 99)),
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
      RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations().apply { repositoryFingerprintValue = "unchanged" },
        ),
      ).copy(launcher = auditGapLauncher(convergeOnAudit = 99)),
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
    "contract_version": "0.6",
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
    "contract_version": "0.6",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "value": "AC-7 login flow is incomplete; no canonical criterion list."
    }
  }
""".trimIndent()

internal fun auditTwoGapsOutput(): String {
  val valueLiteral =
    "\"{\\\"gaps\\\":[{\\\"criterion\\\":\\\"AC-003\\\",\\\"note\\\":\\\"$AUDIT_GAP_MESSAGE\\\"}," +
      "{\\\"criterion\\\":\\\"AC-002\\\",\\\"note\\\":\\\"$AUDIT_GAP_MESSAGE\\\"}]," +
      "\\\"non_blocking_findings\\\":[]}\""
  return """
  {
    "contract_version": "0.6",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "value": $valueLiteral
    }
  }
  """.trimIndent()
}

internal fun auditSatisfiedOutput(): String = """
  {
    "contract_version": "0.6",
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
    "contract_version": "0.6",
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

internal fun auditGapLauncher(convergeOnAudit: Int, progressiveGaps: Boolean = false): RuntimeRecordingLauncher {
  var auditLaunches = 0
  return RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "audit") {
      auditLaunches += 1
      facts(
        if (auditLaunches < convergeOnAudit) {
          if (progressiveGaps) shrinkingAuditGaps(convergeOnAudit - auditLaunches) else auditGapsOutput()
        } else {
          auditSatisfiedOutput()
        },
      )
    } else {
      facts(defaultPhaseOutput(request))
    }
  }
}

@Test
fun `prior-gap memory appears on the second audit_gap implement and the audit that follows it`() {
  val harness =
    runnerHarness(RuntimeHarnessConfig(launcher = auditGapLauncher(convergeOnAudit = 3, progressiveGaps = true)))

  val report = harness.runner.run(harness.request())

  assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
  val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
  val implementBriefing = requireNotNull(briefings["implement"]).briefingText
  val auditBriefing = requireNotNull(briefings["audit"]).briefingText
  assertContains(implementBriefing, "prior_gap_memory")
  assertContains(implementBriefing, "prior_audit_values")
  assertContains(implementBriefing, "AC-002")
  assertContains(implementBriefing, "re-justify recurrence against prior audit prose")
  assertContains(auditBriefing, "prior_gap_memory")
  assertContains(auditBriefing, "prior_audit_values")
}

@Test
fun `in-flight workflow without a second comparable audit completes with empty sticky memory`() {
  val harness =
    runnerHarness(RuntimeHarnessConfig(launcher = auditGapLauncher(convergeOnAudit = 2, progressiveGaps = true)))

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
      RuntimeHarnessConfig(
        repoRoot = createTempDirectory("audit-gap-shared-evidence"),
        sharedEvidenceResolver = store,
        diffResolver = object : DiffResolverPort {
          override fun runProcess(args: List<String>, workDir: Path): String =
            "diff --git a/src/A.kt b/src/A.kt\n@@ -1 +1 @@\n+x\n"
        },
      ).copy(launcher = auditGapLauncher(convergeOnAudit = 2)),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertTrue(store.derivationCount >= 1)
    assertTrue(store.reuseCount >= 1, "unchanged checkpoint must reuse at least once")
  }
}

internal fun shrinkingAuditGaps(remaining: Int): String =
  auditCriteriaOutput(*(2..remaining + 1).map { "AC-%03d".format(it) }.toTypedArray())
