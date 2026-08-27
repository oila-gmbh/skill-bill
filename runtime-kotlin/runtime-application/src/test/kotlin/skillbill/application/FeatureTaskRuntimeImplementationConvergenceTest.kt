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
  fun `a partial implement prose advances without plan-task closure enforcement`() {
    val harness = runnerHarness(launcher = partialImplementLauncher())

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertTrue(harness.launchedPromptPhaseOrder().contains("audit"))
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
  val paths = (1..closedTaskCount).joinToString(",") { "src/Foo$it.kt" }
  return """
  {
    "contract_version": "0.4",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Implementation segment produced a validated output.",
    "produced_outputs": {
      "value": "Segment closed paths [$paths] at target state."
    }
  }
  """.trimIndent()
}

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
          facts(partialImplementOutput())
        }
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
