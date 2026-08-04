package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeContinuationKind
import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertContains
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
  fun `a semantically false completed receipt cannot advance and the block names the missing task id`() {
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = Int.MAX_VALUE))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    val implementBriefings = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "implement" }
    assertTrue(implementBriefings.size > 1, "the runtime must re-enter implement rather than advance")
    assertContains(implementBriefings.last(), "task-3")
  }

  @Test
  fun `flipping only the top-level status to completed does not escape the gate`() {
    // The receipt is structurally valid and says `completed`; only the receipt body is honest about
    // what is still open. Structural validity must not buy a forward transition.
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = Int.MAX_VALUE))

    harness.runner.run(harness.request())

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(0, launched.count { it == "audit" }, "audit must never launch from an unclosed receipt")
    assertEquals(0, launched.count { it == "review" }, "review must never launch from an unclosed receipt")
  }

  @Test
  fun `the continuation loop is bounded and blocks naming the still-open obligations`() {
    val harness = runnerHarness(launcher = convergingImplementLauncher(closeAllOnSegment = Int.MAX_VALUE))

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals(
      FeatureTaskRuntimeFixLoopPolicy.MAX_IMPLEMENTATION_CONTINUATION_SEGMENTS,
      harness.launchedPromptPhaseOrder().count { it == "implement" },
      "the runtime must stop at the declared continuation cap, not loop indefinitely",
    )
    assertEquals("implement", blocked.phaseId)
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

private fun terminalImplementOutput(status: String): String = """
  {
    "contract_version": "0.2",
    "phase_id": "implement",
    "status": "$status",
    "failure_disposition": "retryable",
    "summary": "Implementation hit a transient obstacle.",
    "produced_outputs": {}
  }
""".trimIndent()

// implement closes one more task per segment and closes all three at [closeAllOnSegment]; every other
// phase returns the shared default output.
internal fun convergingImplementLauncher(closeAllOnSegment: Int): RuntimeRecordingLauncher {
  var implementSegment = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(threeTaskPlanOutput())
      "implement" -> {
        implementSegment += 1
        val closed = if (implementSegment >= closeAllOnSegment) CONVERGENCE_PLAN_TASK_COUNT else implementSegment
        facts(partialImplementOutput(closed.coerceAtMost(CONVERGENCE_PLAN_TASK_COUNT)))
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
