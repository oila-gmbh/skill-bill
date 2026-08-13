package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeContinuationKind
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-150 subtask 1: an implement receipt claiming `completed` while plan tasks remain open cannot
 * advance the run. Exercised over the production transition topology with the shared runner harness.
 */
class FeatureTaskRuntimeImplementationConvergenceTest {
  @Test
  fun `two honest partial segments converge on the third and the run completes`() {
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = 3))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(3, harness.launchedPromptPhaseOrder().count { it == "implement" })
  }

  @Test
  fun `a semantically false completed receipt cannot advance and the continuation names the missing task id`() {
    // Never closes task-3; the agent eventually reports blocked so the harness can terminate without
    // a continuation-segment cap.
    val harness = runnerHarness(
      launcher = convergingImplementLauncher(closeAllOnSegment = Int.MAX_VALUE, agentBlockAfterSegments = 4),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    val implementBriefings = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "implement" }
    assertTrue(implementBriefings.size > 1, "the runtime must re-enter implement rather than advance")
    assertTrue(implementBriefings.any { it.contains("task-3") }, "continuation must name the still-open task")
  }

  @Test
  fun `flipping only the top-level status to completed does not escape the gate`() {
    // The receipt is structurally valid and says `completed`; only the receipt body is honest about
    // what is still open. Structural validity must not buy a forward transition.
    val harness = runnerHarness(
      launcher = convergingImplementLauncher(closeAllOnSegment = Int.MAX_VALUE, agentBlockAfterSegments = 4),
    )

    harness.runner.run(harness.request())

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(0, launched.count { it == "audit" }, "audit must never launch from an unclosed receipt")
    assertEquals(0, launched.count { it == "review" }, "review must never launch from an unclosed receipt")
  }

  @Test
  fun `continuation continues past the former segment cap when work eventually closes`() {
    // Former hard cap was 5 segments. Closing on segment 6 proves continuation is uncapped.
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = 6))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(6, harness.launchedPromptPhaseOrder().count { it == "implement" })
  }

  @Test
  fun `a legacy continuation-budget block is re-entered on resume`() {
    // The removed 5-segment cap persisted needs_user_action. That block is stale: resume must relaunch
    // implement rather than re-surface the old reason.
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = 1))
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), threeTaskPlanOutput())
    harness.seedBlockedPhase(
      "implement",
      5,
      phaseAgent("implement"),
      "Phase 'implement' exhausted the bounded implementation-continuation budget after 5 segments " +
        "(cap=5) with obligations still open; the run blocks for an operator decision rather than " +
        "continuing indefinitely. The malformed-output and semantic fix-loop budgets were not consumed.",
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(
      harness.launchedPromptPhaseOrder().any { it == "implement" },
      "the stale continuation-budget block must relaunch implement",
    )
  }

  @Test
  fun `continuation segments are stamped as implementation continuation not schema correction`() {
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = 3))

    harness.runner.run(harness.request())

    val kinds = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION && it.phaseId == "implement" }
      .mapNotNull { FeatureTaskRuntimeContinuationKind.fromLedgerDetail(it.blockedReason) }

    assertEquals(
      List(2) { FeatureTaskRuntimeContinuationKind.IMPLEMENTATION_CONTINUATION },
      kinds,
      "an honest partial receipt is a continuation, not a schema repair",
    )
  }

  @Test
  fun `every segment is durably recorded so a resume carries no lost obligations`() {
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = 3))

    harness.runner.run(harness.request())

    val attempts = harness.recorder.loadImplementationAttempts(WORKFLOW_ID).orEmpty()
      .filter { it.phaseId == "implement" }

    assertEquals(3, attempts.size, "each segment appends exactly one durable attempt")
    assertEquals(
      listOf(
        FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
        FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
        FeatureTaskRuntimeImplementationAttemptStatus.COMPLETED,
      ),
      attempts.map { it.status },
    )
    assertEquals(
      listOf("task-1", "task-2", "task-3"),
      attempts.last().completedTaskIds,
      "the terminal attempt closes every plan task",
    )
    assertEquals(
      attempts.map { it.sequenceNumber }.sorted(),
      attempts.map { it.sequenceNumber },
      "the durable history stays ordered by sequence_number",
    )
  }

  @Test
  fun `a retryable blocked envelope is not relabelled schema-invalid`() {
    val harness = runnerHarness(launcher = terminalThenConvergingLauncher(status = "blocked"))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val schemaCorrections = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION && it.phaseId == "implement" }
      .mapNotNull { FeatureTaskRuntimeContinuationKind.fromLedgerDetail(it.blockedReason) }
      .count { it == FeatureTaskRuntimeContinuationKind.SCHEMA_CORRECTION }

    assertEquals(0, schemaCorrections, "a retryable terminal envelope is semantic, not a structural repair")
  }

  @Test
  fun `a retryable failed envelope is not relabelled schema-invalid`() {
    val harness = runnerHarness(launcher = terminalThenConvergingLauncher(status = "failed"))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
  }

  @Test
  fun `an unrelenting retryable terminal envelope is never prompted or blocked as a schema failure`() {
    val harness = runnerHarness(launcher = alwaysTerminalImplementLauncher(status = "blocked", recoverAfter = 2))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "implement" }
      .forEach { prompt ->
        assertTrue(
          !prompt.contains("REJECTED by the schema gate"),
          "a retryable terminal envelope must never be told its output was rejected",
        )
      }
  }

  @Test
  fun `a retryable terminal re-entry is stamped as a process retry, not a schema correction`() {
    val harness = runnerHarness(launcher = alwaysTerminalImplementLauncher(status = "blocked", recoverAfter = 2))

    harness.runner.run(harness.request())

    val kinds = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION && it.phaseId == "implement" }
      .mapNotNull { FeatureTaskRuntimeContinuationKind.fromLedgerDetail(it.blockedReason) }

    assertTrue(kinds.isNotEmpty(), "the terminal envelope must re-enter the loop at least once")
    assertEquals(
      emptyList(),
      kinds.filter { it == FeatureTaskRuntimeContinuationKind.SCHEMA_CORRECTION },
      "a schema-VALID terminal envelope must never be stamped schema_correction",
    )
    assertEquals(
      List(kinds.size) { FeatureTaskRuntimeContinuationKind.PROCESS_RETRY },
      kinds,
    )
  }

  @Test
  fun `a receipt that both under-closes and breaks its projection contract takes the structural path`() {
    // AC-005: evaluating incompleteness before the projection gate routed a repairable contract defect
    // into the continuation loop, where the defect is never named to the agent and the run burns every
    // continuation segment before blocking. The structural gates must see it first.
    val harness = runnerHarness(launcher = malformedProjectionImplementLauncher())

    harness.runner.run(harness.request())

    val attempts = harness.recorder.loadImplementationAttempts(WORKFLOW_ID).orEmpty()
      .filter { it.phaseId == "implement" }
    assertEquals(
      emptyList(),
      attempts.filter { it.status == FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE },
      "a receipt failing its projection contract is a structural failure, not an incomplete-work segment",
    )
    val implementPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "implement" }
    assertTrue(implementPrompts.size > 1, "the structural-repair path must re-prompt the phase")
    assertTrue(
      implementPrompts.drop(1).any { it.contains("REJECTED by the schema gate") },
      "the repairable contract defect must be named to the agent rather than silently retried",
    )
  }
}

internal const val CONVERGENCE_PLAN_TASK_COUNT = 3

// A plan declaring three tasks, so a receipt closing fewer than three is detectably dishonest.
private fun threeTaskPlanOutput(): String {
  val tasks = (1..CONVERGENCE_PLAN_TASK_COUNT).joinToString(",") { index ->
    """{
      "task_id":"task-$index",
      "description":"Convergence fixture task $index.",
      "criterion_refs":["AC-00$index"],
      "target_paths_or_symbols":["src/Foo$index.kt"],
      "test_obligations":["Focused test $index."]
    }"""
  }
  return """
    {
      "contract_version": "0.2",
      "phase_id": "plan",
      "status": "completed",
      "summary": "Plan produced a validated output.",
      "produced_outputs": {
        "projection_kind":"executable_plan",
        "contract_version":"0.1",
        "mode":"direct",
        "tasks":[$tasks],
        "validation_strategy":["Focused runtime tests."]
      }
    }
  """.trimIndent()
}

// A `completed` implement envelope whose receipt closes only [closedTaskCount] of the three plan
// tasks. Structurally valid at every segment; only the receipt body differs.
private fun partialImplementOutput(closedTaskCount: Int): String {
  val closed = (1..closedTaskCount).joinToString(",") { "\"task-$it\"" }
  val paths = (1..closedTaskCount).joinToString(",") { "\"src/Foo$it.kt\"" }
  return """
    {
      "contract_version": "0.2",
      "phase_id": "implement",
      "status": "completed",
      "summary": "Implementation segment produced a validated output.",
      "produced_outputs": {
        "projection_kind":"implementation_receipt",
        "contract_version":"0.1",
        "completed_task_ids":[$closed],
        "changed_paths":[$paths],
        "tests_executed":[],
        "reconciliation_evidence":{"reconciled":true,"evidence":"Segment tree at target state."},
        "repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},
        "reconciled_state":{"reconciled":true},
        "deferred_repair_item_ids":[],
        "repair_item_results":[]
      }
    }
  """.trimIndent()
}

private fun terminalImplementOutput(status: String, disposition: String = "retryable"): String = """
  {
    "contract_version": "0.2",
    "phase_id": "implement",
    "status": "$status",
    "failure_disposition": "$disposition",
    "summary": "Implementation hit a transient obstacle.",
    "produced_outputs": {}
  }
""".trimIndent()

// implement closes one more task per segment and closes all three at [closeAllOnSegment]; every other
// phase returns the shared default output. When [agentBlockAfterSegments] is set, that many incomplete
// segments are followed by a non-retryable agent `blocked` envelope so tests that never close work can
// still terminate without a continuation-segment cap.
internal fun convergingImplementLauncher(
  closeAllOnSegment: Int,
  agentBlockAfterSegments: Int? = null,
): RuntimeRecordingLauncher {
  var implementSegment = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(threeTaskPlanOutput())
      "implement" -> {
        implementSegment += 1
        if (agentBlockAfterSegments != null && implementSegment > agentBlockAfterSegments) {
          facts(terminalImplementOutput("blocked", disposition = "needs_user_action"))
        } else {
          // A non-closing segment is capped BELOW the plan's task count: coercing to the count itself
          // let segment three close every task even when the fixture was asked never to close.
          val closed = if (implementSegment >= closeAllOnSegment) {
            CONVERGENCE_PLAN_TASK_COUNT
          } else {
            implementSegment.coerceAtMost(CONVERGENCE_PLAN_TASK_COUNT - 1)
          }
          facts(partialImplementOutput(closed))
        }
      }
      else -> facts(defaultPhaseOutput(request))
    }
  }
}

// A `completed` implement envelope that both under-closes the plan AND breaks the receipt projection
// contract: deviations must carry {ref, note} objects, so free-text entries fail the projection gate.
private fun malformedProjectionImplementOutput(): String = """
  {
    "contract_version": "0.2",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Implementation segment produced a validated envelope with an invalid receipt.",
    "produced_outputs": {
      "projection_kind":"implementation_receipt",
      "contract_version":"0.1",
      "completed_task_ids":["task-1"],
      "changed_paths":["src/Foo1.kt"],
      "tests_executed":[],
      "deviations":["a free-text deviation the projection contract forbids"],
      "reconciliation_evidence":{"reconciled":true,"evidence":"Segment tree at target state."},
      "repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},
      "reconciled_state":{"reconciled":true},
      "deferred_repair_item_ids":[],
      "repair_item_results":[]
    }
  }
""".trimIndent()

private fun malformedProjectionImplementLauncher(): RuntimeRecordingLauncher {
  var implementLaunches = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(threeTaskPlanOutput())
      "implement" -> {
        implementLaunches += 1
        facts(
          if (implementLaunches == 1) {
            malformedProjectionImplementOutput()
          } else {
            partialImplementOutput(CONVERGENCE_PLAN_TASK_COUNT)
          },
        )
      }
      else -> facts(defaultPhaseOutput(request))
    }
  }
}

private fun alwaysTerminalImplementLauncher(status: String, recoverAfter: Int = Int.MAX_VALUE): RuntimeRecordingLauncher {
  var implementLaunches = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(threeTaskPlanOutput())
      "implement" -> {
        implementLaunches += 1
        facts(
          if (implementLaunches <= recoverAfter) {
            terminalImplementOutput(status)
          } else {
            partialImplementOutput(CONVERGENCE_PLAN_TASK_COUNT)
          },
        )
      }
      else -> facts(defaultPhaseOutput(request))
    }
  }
}

// The first implement launch returns a retryable terminal envelope; the second closes every plan task.
private fun terminalThenConvergingLauncher(status: String): RuntimeRecordingLauncher {
  var implementSegment = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(threeTaskPlanOutput())
      "implement" -> {
        implementSegment += 1
        if (implementSegment == 1) {
          facts(terminalImplementOutput(status))
        } else {
          facts(partialImplementOutput(CONVERGENCE_PLAN_TASK_COUNT))
        }
      }
      else -> facts(defaultPhaseOutput(request))
    }
  }
}
