package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-157 subtask 2: the semantic remediation loops warn once when they pass the shared warning
 * threshold, and warning is all they do. Every case drives the production topology through the runner
 * with a fake diagnostics port, because a warning asserted against the definition object alone would
 * stay green while the run loop never emitted it.
 */
class FeatureTaskRuntimeLoopWarningThresholdTest {
  private val threshold = FeatureTaskRuntimePhaseWorkflowDefinition.SEMANTIC_LOOP_WARNING_THRESHOLD
  private val crossingIteration = threshold + 1

  @Test
  fun `review_fix crossing the threshold warns once naming the loop the count and the work`() {
    val diagnostics = RecordingDiagnostics()
    val harness = runnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = crossingIteration + 1),
      diagnostics = diagnostics,
    )

    val request = harness.request()
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(request))

    assertEquals(
      (1..crossingIteration).toList(),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
      "the fixture must actually reach the crossing iteration",
    )
    val warning = diagnostics.warnings.single()
    assertContains(warning, "review_fix")
    assertContains(warning, "warning threshold of $threshold")
    assertContains(warning, "iteration $crossingIteration")
    assertContains(warning, request.issueKey)
    assertContains(warning, WORKFLOW_ID)
    assertContains(warning, request.runInvariants.specReference)
    assertContains(warning, "Remediation will continue")
  }

  @Test
  fun `audit_gap crossing the threshold warns once with the equivalent message`() {
    val diagnostics = RecordingDiagnostics()
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = crossingIteration + 1),
      diagnostics = diagnostics,
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(
      (1..crossingIteration).toList(),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID),
    )
    val warning = diagnostics.warnings.single()
    assertContains(warning, "audit_gap")
    assertContains(warning, "warning threshold of $threshold")
    assertContains(warning, "iteration $crossingIteration")
    assertContains(warning, "Remediation will continue")
  }

  @Test
  fun `iterations up to the threshold stay silent for both loops`() {
    val reviewDiagnostics = RecordingDiagnostics()
    val reviewHarness = runnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = threshold + 1),
      diagnostics = reviewDiagnostics,
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(reviewHarness.runner.run(reviewHarness.request()))
    assertEquals(
      (1..threshold).toList(),
      loopEdgeIterations(reviewHarness, FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
    )
    assertEquals(emptyList(), reviewDiagnostics.warnings, "iterations 1..$threshold are within the threshold")

    val auditDiagnostics = RecordingDiagnostics()
    val auditHarness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = threshold + 1),
      diagnostics = auditDiagnostics,
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(auditHarness.runner.run(auditHarness.request()))
    assertEquals(
      (1..threshold).toList(),
      loopEdgeIterations(auditHarness, FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID),
    )
    assertEquals(emptyList(), auditDiagnostics.warnings)
  }

  @Test
  fun `iterations past the crossing do not repeat the warning`() {
    val diagnostics = RecordingDiagnostics()
    val harness = runnerHarness(launcher = reviewFixLauncher(convergeOnReview = 9), diagnostics = diagnostics)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(
      (1..8).toList(),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
      "the loop must run well past the crossing for this assertion to mean anything",
    )
    assertEquals(1, diagnostics.warnings.size)
    assertContains(diagnostics.warnings.single(), "iteration $crossingIteration")
  }

  @Test
  fun `both loops crossing in one subtask warn independently`() {
    val diagnostics = RecordingDiagnostics()
    val harness = runnerHarness(
      launcher = bothLoopsLauncher(convergeOnAudit = crossingIteration + 1, convergeOnReview = crossingIteration + 1),
      diagnostics = diagnostics,
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(
      (1..crossingIteration).toList(),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID),
    )
    assertEquals(
      (1..crossingIteration).toList(),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
    )
    assertEquals(2, diagnostics.warnings.size, "each loop acknowledges its own crossing")
    assertEquals(1, diagnostics.warnings.count { it.contains("'audit_gap'") })
    assertEquals(1, diagnostics.warnings.count { it.contains("'review_fix'") })
  }

  @Test
  fun `a crash before the crossing iteration is persisted still warns exactly once after resume`() {
    val diagnostics = RecordingDiagnostics()
    var crashOnCrossingReview = true
    val harness = runnerHarness(
      launcher = crashingReviewFixLauncher(
        convergeOnReview = crossingIteration + 2,
        crashOnReviewLaunch = crossingIteration,
        shouldCrash = { crashOnCrossingReview },
      ),
      diagnostics = diagnostics,
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals(
      emptyList(),
      diagnostics.warnings,
      "the crossing edge was never minted, so nothing crossed the threshold",
    )

    crashOnCrossingReview = false
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(1, diagnostics.warnings.size)
    assertContains(diagnostics.warnings.single(), "iteration $crossingIteration")
  }

  @Test
  fun `a crash after the crossing iteration is persisted does not warn again on resume`() {
    val diagnostics = RecordingDiagnostics()
    var crashOnCrossingFix = true
    val harness = runnerHarness(
      launcher = crashingReviewFixLauncher(
        convergeOnReview = crossingIteration + 1,
        crashOnImplementFixLaunch = crossingIteration,
        shouldCrash = { crashOnCrossingFix },
      ),
      diagnostics = diagnostics,
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals(1, diagnostics.warnings.size, "the crossing edge is durable, so it warned before the crash")

    crashOnCrossingFix = false
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(1, diagnostics.warnings.size, "the resumed reentry reuses the persisted iteration silently")
  }

  @Test
  fun `resuming a subtask already past the crossing emits no further warning`() {
    val diagnostics = RecordingDiagnostics()
    val harness = runnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = crossingIteration + 1),
      diagnostics = diagnostics,
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(1, diagnostics.warnings.size)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(1, diagnostics.warnings.size, "a parent resume of settled work re-warns nothing")
  }

  @Test
  fun `the transition outcome is identical with silent recording and throwing diagnostics`() {
    val outcomes = listOf<RuntimeDiagnostics>(
      NoopRuntimeDiagnostics,
      RecordingDiagnostics(),
      ThrowingDiagnostics(),
    ).map { diagnostics ->
      val harness = runnerHarness(
        launcher = reviewFixLauncher(convergeOnReview = crossingIteration + 1),
        diagnostics = diagnostics,
      )
      val report = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
      RunOutcome(
        completedPhaseIds = report.completedPhaseIds,
        launchedPhases = harness.launchedPromptPhaseOrder(),
        reviewFixIterations = loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
        phaseStatuses = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
          .mapValues { (_, record) -> record.status },
      )
    }

    assertEquals(outcomes[0], outcomes[1], "recording the warning must not change the run")
    assertEquals(outcomes[0], outcomes[2], "a diagnostics fault must not change the run")
  }

  @Test
  fun `status reports semantic loop iteration counts above the threshold verbatim`() {
    listOf(crossingIteration, crossingIteration + 1, 10).forEach { iterations ->
      val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = iterations + 1))

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

      val status = FeatureTaskRuntimeStatusService(
        harness.recorder,
        harness.runInvariantsStore,
        harness.decomposeTerminalRecorder,
      ).status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID))
      assertEquals(
        iterations,
        status?.auditRepair?.auditGapIterationCount,
        "$iterations audit-gap iterations must be reported without truncation",
      )
      assertTrue(
        harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty().values.none {
          it.blockedReason?.contains("cap") == true
        },
        "an unbounded semantic loop must never report cap-exhaustion wording",
      )
    }
  }

  @Test
  fun `finished telemetry round-trips semantic loop iteration counts above the threshold`() {
    val reviewHarness = telemetryRunnerHarness(launcher = reviewFixLauncher(convergeOnReview = 11))
    assertIs<FeatureTaskRuntimeRunReport.Completed>(reviewHarness.runner.run(reviewHarness.request))
    assertEquals(10, reviewHarness.lifecycle.finishedRecords.single().reviewFixIterationCount)

    val auditHarness = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 5))
    assertIs<FeatureTaskRuntimeRunReport.Completed>(auditHarness.runner.run(auditHarness.request))
    assertEquals(crossingIteration, auditHarness.lifecycle.finishedRecords.single().auditGapIterationCount)
  }

  private data class RunOutcome(
    val completedPhaseIds: List<String>,
    val launchedPhases: List<String>,
    val reviewFixIterations: List<Int>,
    val phaseStatuses: Map<String, String>,
  )

  private fun loopEdgeIterations(harness: RunnerHarness, loopId: String): List<Int> =
    harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == loopId }
      .mapNotNull { it.edgeIteration }
}

internal class RecordingDiagnostics : RuntimeDiagnostics {
  val warnings: MutableList<String> = mutableListOf()

  override fun warning(message: String, error: Throwable?) {
    warnings += message
  }

  override fun error(message: String, error: Throwable?) = Unit
}

// Models the diagnostics port itself faulting at the moment of the crossing warning: the run must
// finish exactly as it would have with a silent port.
private class ThrowingDiagnostics : RuntimeDiagnostics {
  override fun warning(message: String, error: Throwable?): Nothing = kotlin.error("diagnostics sink unavailable")

  override fun error(message: String, error: Throwable?) = Unit
}

// Drives both semantic loops in one run: the audit reports gaps until [convergeOnAudit], then the
// review raises a Blocker until [convergeOnReview]. Review sits outside the audit_gap span, so every
// review pass runs against the tree the final satisfied audit cleared.
private fun bothLoopsLauncher(convergeOnAudit: Int, convergeOnReview: Int): RuntimeRecordingLauncher {
  var auditLaunches = 0
  var reviewLaunches = 0
  return RuntimeRecordingLauncher { request ->
    when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "audit" -> {
        auditLaunches += 1
        facts(
          if (auditLaunches < convergeOnAudit) {
            auditGapsOutput(followUp = auditLaunches > 1)
          } else {
            auditSatisfiedOutput(followUp = auditLaunches > 1)
          },
        )
      }
      "review" -> {
        reviewLaunches += 1
        facts(
          reviewFindingsOutput(
            changesRequested = reviewLaunches < convergeOnReview,
            dispositionedBlockerIds = if (reviewLaunches > 1) listOf("pass1-blocker-1") else emptyList(),
          ),
        )
      }
      else -> facts(validJsonOutput(phaseId))
    }
  }
}

// A review_fix launcher that can fail one specific launch, modelling a crash before the crossing edge
// is minted (crashOnReviewLaunch) or after it is durable (crashOnImplementFixLaunch).
private fun crashingReviewFixLauncher(
  convergeOnReview: Int,
  crashOnReviewLaunch: Int? = null,
  crashOnImplementFixLaunch: Int? = null,
  shouldCrash: () -> Boolean,
): RuntimeRecordingLauncher {
  var reviewLaunches = 0
  var fixLaunches = 0
  return RuntimeRecordingLauncher { request ->
    when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "review" -> {
        reviewLaunches += 1
        if (shouldCrash() && reviewLaunches == crashOnReviewLaunch) {
          spawnFailedFacts()
        } else {
          facts(
            reviewFindingsOutput(
              changesRequested = reviewLaunches < convergeOnReview,
              dispositionedBlockerIds = if (reviewLaunches > 1) listOf("pass1-blocker-1") else emptyList(),
            ),
          )
        }
      }
      "implement_fix" -> {
        fixLaunches += 1
        if (shouldCrash() && fixLaunches == crashOnImplementFixLaunch) {
          spawnFailedFacts()
        } else {
          facts(validJsonOutput(phaseId))
        }
      }
      else -> facts(validJsonOutput(phaseId))
    }
  }
}
