package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeContinuationKind
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationConvergenceTest {
  @Test
  fun `a partial implement receipt advances without plan-task closure enforcement`() {
    val harness = runnerHarness(launcher = partialImplementLauncher())

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertTrue(harness.launchedPromptPhaseOrder().contains("audit"))
  }

  @Test
  fun `unresolved_items blocks completion and re-enters implement`() {
    val harness = runnerHarness(launcher = unresolvedItemsImplementLauncher())

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", report.lastIncompletePhase)
    val implementBriefings = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "implement" }
    assertTrue(implementBriefings.size > 1)
    assertTrue(implementBriefings.any { it.contains("unresolved_items") })
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
  fun `a receipt that breaks its projection contract takes the structural path`() {
    val harness = runnerHarness(launcher = malformedProjectionImplementLauncher())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("implement", blocked.lastIncompletePhase)
    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
    assertEquals(
      1,
      harness.launcher.requests
        .map { requireNotNull(it.skillRunRequest.promptOverride) }
        .count { phaseIdFromPrompt(it) == "implement" },
    )
  }
}

internal const val CONVERGENCE_PLAN_TASK_COUNT = 3

internal fun planProseOutput(): String = """
  {
    "contract_version": "0.4",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan produced prose.",
    "produced_outputs": {
      "value": "Fixture plan prose for downstream implement and audit."
    }
  }
""".trimIndent()

internal fun partialImplementOutput(closedTaskCount: Int = 1): String {
  val closed = (1..closedTaskCount).joinToString(",") { "\"task-$it\"" }
  val paths = (1..closedTaskCount).joinToString(",") { "\"src/Foo$it.kt\"" }
  return """
  {
    "contract_version": "0.4",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Implementation segment produced a validated output.",
    "produced_outputs": {
      "projection_kind":"implementation_receipt",
      "contract_version":"0.2",
      "completed_task_ids":[$closed],
      "changed_paths":[$paths],
      "tests_executed":[],
      "reconciliation_evidence":{"reconciled":true,"evidence":"Segment tree at target state."},
      "repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},
      "reconciled_state":{"reconciled":true}
    }
  }
  """.trimIndent()
}

private fun unresolvedImplementOutput(): String = """
  {
    "contract_version": "0.4",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Implementation segment produced a validated output.",
    "produced_outputs": {
      "projection_kind":"implementation_receipt",
      "contract_version":"0.2",
      "completed_task_ids":["task-1"],
      "changed_paths":["src/Foo1.kt"],
      "tests_executed":[],
      "unresolved_items":["tests still owed"],
      "reconciliation_evidence":{"reconciled":true,"evidence":"Segment tree at target state."},
      "repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},
      "reconciled_state":{"reconciled":true}
    }
  }
""".trimIndent()

private fun terminalImplementOutput(status: String, disposition: String = "retryable"): String = """
  {
    "contract_version": "0.4",
    "phase_id": "implement",
    "status": "$status",
    "failure_disposition": "$disposition",
    "summary": "Implementation hit a transient obstacle.",
    "produced_outputs": {}
  }
""".trimIndent()

private fun partialImplementLauncher(): RuntimeRecordingLauncher = RuntimeRecordingLauncher { request ->
  when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
    "plan" -> facts(planProseOutput())
    "implement" -> facts(partialImplementOutput())
    else -> facts(defaultPhaseOutput(request))
  }
}

internal fun unresolvedItemsImplementLauncher(agentBlockAfterSegments: Int? = null): RuntimeRecordingLauncher {
  var implementLaunches = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(planProseOutput())
      "implement" -> {
        implementLaunches += 1
        val keepUnresolvedThrough = agentBlockAfterSegments ?: 1
        if (implementLaunches > keepUnresolvedThrough) {
          facts(terminalImplementOutput("blocked", disposition = "needs_user_action"))
        } else {
          facts(unresolvedImplementOutput())
        }
      }
      else -> facts(defaultPhaseOutput(request))
    }
  }
}

private fun malformedProjectionImplementOutput(): String = """
  {
    "contract_version": "0.4",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Implementation segment produced a validated envelope with an invalid receipt.",
    "produced_outputs": {
      "projection_kind":"implementation_receipt",
      "contract_version":"0.2",
      "completed_task_ids":["task-1"],
      "changed_paths":["src/Foo1.kt"],
      "tests_executed":[],
      "deviations":["a free-text deviation the projection contract forbids"],
      "reconciliation_evidence":{"reconciled":true,"evidence":"Segment tree at target state."},
      "repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},
      "reconciled_state":{"reconciled":true}
    }
  }
""".trimIndent()

private fun malformedProjectionImplementLauncher(): RuntimeRecordingLauncher {
  var implementLaunches = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(planProseOutput())
      "implement" -> {
        implementLaunches += 1
        facts(
          if (implementLaunches == 1) {
            malformedProjectionImplementOutput()
          } else {
            partialImplementOutput()
          },
        )
      }
      else -> facts(defaultPhaseOutput(request))
    }
  }
}

private fun terminalThenConvergingLauncher(status: String): RuntimeRecordingLauncher {
  var implementSegment = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(planProseOutput())
      "implement" -> {
        implementSegment += 1
        if (implementSegment == 1) {
          facts(terminalImplementOutput(status))
        } else {
          facts(partialImplementOutput())
        }
      }
      else -> facts(defaultPhaseOutput(request))
    }
  }
}

internal fun convergingImplementLauncher(
  closeAllOnSegment: Int,
  agentBlockAfterSegments: Int? = null,
): RuntimeRecordingLauncher {
  var implementSegment = 0
  return RuntimeRecordingLauncher { request ->
    when (phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
      "plan" -> facts(planProseOutput())
      "implement" -> {
        implementSegment += 1
        if (agentBlockAfterSegments != null && implementSegment > agentBlockAfterSegments) {
          facts(terminalImplementOutput("blocked", disposition = "needs_user_action"))
        } else {
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
