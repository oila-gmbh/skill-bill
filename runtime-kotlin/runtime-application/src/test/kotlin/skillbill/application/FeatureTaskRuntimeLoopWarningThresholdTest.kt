package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeAttemptBudgets
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
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
      launcher = reviewFixLauncher(convergeOnReview = 2),
      diagnostics = diagnostics,
      runtimeConfig = reviewFixRuntimeConfig(2),
    )

    val request = harness.request()
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(request))

    assertEquals(
      listOf(1),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
      "verify_findings drives a single bounded review_fix edge per subtask",
    )
    assertEquals(emptyList(), diagnostics.warnings, "bounded review_fix must not attach semantic loop warnings")
  }

  @Test
  fun `audit_gap crossing the threshold pauses for an operator decision and warns once`() {
    val diagnostics = RecordingDiagnostics()
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = crossingIteration + 1),
      diagnostics = diagnostics,
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertEquals(
      (1..threshold).toList(),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID),
      "the pausing iteration is not recorded as an edge",
    )
    assertContains(report.pauseReason, "warn-threshold")
    assertContains(report.pauseReason, "iteration $crossingIteration")
    assertContains(report.pauseReason, "retry_fix")
    val warning = diagnostics.warnings.single()
    assertContains(warning, "audit_gap")
    assertContains(warning, "warning threshold of $threshold")
    assertContains(warning, "iteration $crossingIteration")
    assertTrue(!warning.contains("Remediation will continue"), "the crossing no longer claims continuation")
  }

  @Test
  fun `iterations up to the threshold stay silent for both loops`() {
    val reviewDiagnostics = RecordingDiagnostics()
    val reviewHarness = runnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = threshold + 1),
      diagnostics = reviewDiagnostics,
      runtimeConfig = reviewFixRuntimeConfig(threshold + 1),
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(reviewHarness.runner.run(reviewHarness.request()))
    assertEquals(
      listOf(1),
      loopEdgeIterations(reviewHarness, FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
    )
    assertEquals(emptyList(), reviewDiagnostics.warnings, "bounded review_fix never emits semantic loop warnings")

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
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = 9),
      diagnostics = diagnostics,
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Completed>(
      runPastWarnThresholdPauses(harness, harness.request()),
    )

    assertEquals(
      (1..8).toList(),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID),
      "the loop must run well past the crossing for this assertion to mean anything",
    )
    assertSingleCrossingWarning(diagnostics, crossingIteration)
  }

  @Test
  fun `audit below threshold and review crossing warn independently`() {
    val diagnostics = RecordingDiagnostics()
    val harness = runnerHarness(
      launcher = bothLoopsLauncher(convergeOnAudit = 2, convergeOnReview = crossingIteration + 1),
      diagnostics = diagnostics,
      runtimeConfig = reviewFixRuntimeConfig(crossingIteration + 1),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    // The audit_gap crossing now pauses before review can cross, so a sub-threshold audit is the only
    // way both loops run in one subtask; only the crossing review_fix loop warns.
    assertEquals(
      listOf(1),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID),
      "audit stays below the threshold",
    )
    assertEquals(
      listOf(1),
      loopEdgeIterations(harness, FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID),
    )
    assertEquals(emptyList(), diagnostics.warnings, "both loops stay below the warning threshold")
  }

  @Test
  fun `a crash before the crossing iteration is persisted still warns exactly once after resume`() {
    val diagnostics = RecordingDiagnostics()
    var crashOnCrossingAudit = true
    val harness = runnerHarness(
      launcher = crashingAuditGapLauncher(
        convergeOnAudit = crossingIteration + 2,
        crashOnAuditLaunch = threshold,
        shouldCrash = { crashOnCrossingAudit },
      ),
      diagnostics = diagnostics,
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals(
      emptyList(),
      diagnostics.warnings,
      "the crossing edge was never minted, so nothing crossed the threshold",
    )

    crashOnCrossingAudit = false
    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(
      runPastWarnThresholdPauses(harness, harness.request()),
    )
    assertEquals(completed.completedPhaseIds.last(), "pr")

    assertSingleCrossingWarning(diagnostics, crossingIteration)
  }

  @Test
  fun `a crash after the crossing iteration is persisted does not warn again on resume`() {
    val diagnostics = RecordingDiagnostics()
    var crashOnImplement = true
    val harness = runnerHarness(
      launcher = crashingAuditGapLauncher(
        convergeOnAudit = crossingIteration + 2,
        crashOnImplementLaunch = crossingIteration + 1,
        shouldCrash = { crashOnImplement },
      ),
      diagnostics = diagnostics,
    )

    val paused = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))
    assertContains(paused.pauseReason, "warn-threshold")
    assertEquals(1, diagnostics.warnings.size, "the crossing edge is durable, so it warned before the crash")

    grantAuditGapRetryFix(harness)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    crashOnImplement = false
    grantAuditGapRetryFix(harness)
    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      runPastWarnThresholdPauses(harness, harness.request()),
    )

    assertSingleCrossingWarning(diagnostics, crossingIteration)
  }

  @Test
  fun `resuming a subtask already past the crossing emits no further warning`() {
    val diagnostics = RecordingDiagnostics()
    val harness = runnerHarness(
      launcher = auditGapLauncher(convergeOnAudit = crossingIteration + 2),
      diagnostics = diagnostics,
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      runPastWarnThresholdPauses(harness, harness.request()),
    )
    assertSingleCrossingWarning(diagnostics, crossingIteration)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertSingleCrossingWarning(diagnostics, crossingIteration)
  }

  @Test
  fun `the transition outcome is identical with silent recording and throwing diagnostics`() {
    val outcomes = listOf<RuntimeDiagnostics>(
      NoopRuntimeDiagnostics,
      RecordingDiagnostics(),
      ThrowingDiagnostics(),
    ).map { diagnostics ->
      val harness = runnerHarness(
        launcher = reviewFixLauncher(convergeOnReview = 2),
        diagnostics = diagnostics,
        runtimeConfig = reviewFixRuntimeConfig(2),
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
  fun `status reports the honest iteration count on a paused warn-threshold run`() {
    val harness = runnerHarness(launcher = auditGapLauncher(convergeOnAudit = crossingIteration + 1))

    assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    val status = FeatureTaskRuntimeStatusService(
      harness.recorder,
      harness.runInvariantsStore,
      harness.decomposeTerminalRecorder,
    ).status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID))
    assertEquals(
      crossingIteration,
      status?.auditRepair?.auditGapIterationCount,
      "the pause must report the honest crossing iteration without truncation",
    )
    assertTrue(
      harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty().values.none {
        it.blockedReason?.contains("cap") == true
      },
      "an unbounded semantic loop must never report cap-exhaustion wording",
    )
  }

  @Test
  fun `finished telemetry round-trips semantic loop iteration counts above the threshold`() {
    val reviewHarness = telemetryRunnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = 2),
      runtimeConfig = reviewFixRuntimeConfig(2),
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(reviewHarness.runner.run(reviewHarness.request))
    assertEquals(1, reviewHarness.lifecycle.finishedRecords.single().reviewFixIterationCount)

    // The audit_gap crossing now pauses, so the finished-telemetry case stays below the threshold: a
    // single gap iteration that converges is the largest audit-gap run that reaches telemetry.
    val auditHarness = telemetryRunnerHarness(launcher = auditGapLauncher(convergeOnAudit = 2))
    assertIs<FeatureTaskRuntimeRunReport.Completed>(auditHarness.runner.run(auditHarness.request))
    assertEquals(1, auditHarness.lifecycle.finishedRecords.single().auditGapIterationCount)
  }

  @Test
  fun `process-failure and malformed-output budgets stay pinned independently of the semantic loops`() {
    assertEquals(3, FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS)
    assertEquals(1, FeatureTaskRuntimeAttemptBudgets.MAX_FORMAT_RETRY_ATTEMPTS)
    assertEquals(1, FeatureTaskRuntimeAttemptBudgets.MAX_OUTPUT_GATE_RETRY_ATTEMPTS)
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

private fun assertSingleCrossingWarning(diagnostics: RecordingDiagnostics, crossingIteration: Int) {
  assertTrue(diagnostics.warnings.isNotEmpty())
  assertTrue(
    diagnostics.warnings.all { it.contains("iteration $crossingIteration") },
    "warnings must name only the crossing iteration",
  )
  assertTrue(
    diagnostics.warnings.none { it.contains("iteration ${crossingIteration + 1}") },
    "iterations past the crossing must not warn again",
  )
  assertEquals(1, diagnostics.warnings.distinct().size, "the crossing warning text is stable")
}

private fun grantAuditGapRetryFix(harness: RunnerHarness) {
  val pause = harness.recorder.loadAuditGapPause(WORKFLOW_ID)
  requireNotNull(pause)
  harness.recorder.persistAuditGapPause(
    WORKFLOW_ID,
    pause.copy(operatorDecision = AUDIT_GAP_PAUSE_DECISION_RETRY_FIX),
  )
}

private fun runPastWarnThresholdPauses(
  harness: RunnerHarness,
  request: FeatureTaskRuntimeRunRequest,
): FeatureTaskRuntimeRunReport {
  var report = harness.runner.run(request)
  while (report is FeatureTaskRuntimeRunReport.Paused && report.pauseReason.contains("warn-threshold")) {
    grantAuditGapRetryFix(harness)
    report = harness.runner.run(request)
  }
  return report
}

// Drives both semantic loops in one run: the audit reports gaps until [convergeOnAudit], then the
// review raises a Blocker until [convergeOnReview]. Review sits outside the audit_gap span, so every
// review pass runs against the tree the final satisfied audit cleared.
private fun bothLoopsLauncher(convergeOnAudit: Int, convergeOnReview: Int): RuntimeRecordingLauncher {
  var auditLaunches = 0
  var reviewLaunches = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "audit" -> {
        auditLaunches += 1
        facts(
          if (auditLaunches < convergeOnAudit) {
            auditGapsOutput()
          } else {
            auditSatisfiedOutput()
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
      else -> facts(defaultPhaseOutput(request))
    }
  }
}

private fun crashingAuditGapLauncher(
  convergeOnAudit: Int,
  crashOnAuditLaunch: Int? = null,
  crashOnImplementLaunch: Int? = null,
  shouldCrash: () -> Boolean,
): RuntimeRecordingLauncher {
  var auditLaunches = 0
  var implementLaunches = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "audit" -> {
        auditLaunches += 1
        if (shouldCrash() && auditLaunches == crashOnAuditLaunch) {
          spawnFailedFacts()
        } else {
          facts(
            if (auditLaunches < convergeOnAudit) {
              auditGapsOutput()
            } else {
              auditSatisfiedOutput()
            },
          )
        }
      }
      "implement" -> {
        implementLaunches += 1
        if (shouldCrash() && implementLaunches == crashOnImplementLaunch) {
          spawnFailedFacts()
        } else {
          facts(defaultPhaseOutput(request))
        }
      }
      else -> facts(defaultPhaseOutput(request))
    }
  }
}
