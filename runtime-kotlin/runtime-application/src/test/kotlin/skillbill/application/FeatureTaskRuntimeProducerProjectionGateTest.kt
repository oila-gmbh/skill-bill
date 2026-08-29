@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.producerProjectionGateReason
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.contracts.JsonSupport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeProducerProjectionGateTest {
  @Test
  fun `a preplan output missing value blocks preplan and never reaches plan`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "preplan") PREPLAN_MISSING_VALUE else validJsonOutput(phaseId))
      },
      validator = realFeatureTaskRuntimePhaseOutputValidator,
      agentAssignment = phasePerAgentAssignment(),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals("preplan", blocked.lastIncompletePhase)
    assertTrue(
      blocked.blockedReason.contains("value", ignoreCase = true) ||
        harness.io.database.rejectedDiagnostics().any {
          it.metadata.phaseId == "preplan" && it.metadata.reason.contains("value", ignoreCase = true)
        },
    )
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "plan" })
  }

  @Test
  fun `leftover digest keys plus value complete preplan without producer-projection re-entry`() {
    val envelope = JsonSupport.anyToStringAnyMap(
      JsonSupport.jsonElementToValue(JsonSupport.parseObjectOrNull(PREPLAN_LEFTOVER_DIGEST_KEYS)!!),
    )!!
    assertNull(
      producerProjectionGateReason(
        phaseId = "preplan",
        outputMap = envelope,
        planningProjectionValidator = realPlanningProjectionValidator,
      ),
    )
  }

  @Test
  fun `leftover receipt keys plus value complete implement without producer-projection re-entry`() {
    val envelope = JsonSupport.anyToStringAnyMap(
      JsonSupport.jsonElementToValue(JsonSupport.parseObjectOrNull(IMPLEMENT_LEFTOVER_RECEIPT_KEYS)!!),
    )!!
    assertNull(
      producerProjectionGateReason(
        phaseId = "implement",
        outputMap = envelope,
        planningProjectionValidator = realPlanningProjectionValidator,
      ),
    )

    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") IMPLEMENT_LEFTOVER_RECEIPT_BODY else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertTrue(harness.launchedPromptPhaseOrder().contains("audit"))
  }

  @Test
  fun `a validation receipt with a string checkpoint blocks validate`() {
    val outcome = runRejectedProducer("validate", VALIDATION_CHECKPOINT_AS_STRING)

    assertEquals(1, outcome.launchCount("validate"))
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "consumer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "repository_checkpoint", "non-blank fingerprint")
    assertTrue(!outcome.launched("write_history"))
  }

  @Test
  fun `conforming preplan plan and implement outputs each advance without a retry`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val order = harness.launchedPromptPhaseOrder()
    listOf("preplan", "plan", "implement").forEach { phaseId ->
      assertEquals(1, order.count { it == phaseId }, "conforming $phaseId must advance on its first launch")
    }
  }

  @Test
  fun `a blocked producing-phase output with a projection-invalid body settles terminally, not through the gate`() {
    listOf("preplan", "plan", "implement").forEach { targetPhase ->
      val outcome = runTerminalProducer(targetPhase, terminalProducerOutput(targetPhase, status = "blocked"))

      assertEquals(targetPhase, outcome.blocked.lastIncompletePhase, "$targetPhase must settle at its own phase")
      assertContains(outcome.blocked.blockedReason, "status 'blocked'")
      assertContains(outcome.blocked.blockedReason, TERMINAL_BLOCKING_REASON)
      assertTrue(
        !outcome.blocked.blockedReason.contains("is not a valid"),
        "a blocked envelope must bypass the producer projection gate",
      )
    }
  }

  @Test
  fun `a failed producing-phase output with a projection-invalid body settles terminally, not through the gate`() {
    listOf("preplan", "plan", "implement").forEach { targetPhase ->
      val outcome = runTerminalProducer(targetPhase, terminalProducerOutput(targetPhase, status = "failed"))

      assertEquals(targetPhase, outcome.blocked.lastIncompletePhase)
      assertContains(outcome.blocked.blockedReason, "status 'failed'")
      assertTrue(
        !outcome.blocked.blockedReason.contains("is not a valid"),
        "a failed envelope must bypass the producer projection gate",
      )
    }
  }

  private fun runTerminalProducer(targetPhase: String, terminalOutput: String): ProducerBlockOutcome {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == targetPhase) terminalOutput else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    val report = harness.runner.run(harness.request())
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    return ProducerBlockOutcome(blocked, harness.launchedPromptPhaseOrder())
  }

  private class ProducerBlockOutcome(
    val blocked: FeatureTaskRuntimeRunReport.Blocked,
    private val launchedOrder: List<String>,
  )

  private class ProducerRejectionOutcome(
    val blocked: FeatureTaskRuntimeRunReport.Blocked,
    val diagnosticReason: String,
    private val launchedOrder: List<String>,
  ) {
    fun launchCount(phaseId: String): Int = launchedOrder.count { it == phaseId }
    fun launched(phaseId: String): Boolean = launchedOrder.contains(phaseId)
  }

  private fun runRejectedProducer(
    targetPhase: String,
    malformedOutput: String,
    runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
  ): ProducerRejectionOutcome {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == targetPhase) malformedOutput else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = runtimeConfig,
    )
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals(targetPhase, blocked.lastIncompletePhase, "the run must settle at the rejected producer")
    val diagnostic = harness.io.database.rejectedDiagnostics().first { it.metadata.phaseId == targetPhase }
    return ProducerRejectionOutcome(blocked, diagnostic.metadata.reason, harness.launchedPromptPhaseOrder())
  }
}

private fun envelope(phaseId: String, producedOutputs: String): String =
  """{"contract_version":"0.2","phase_id":"$phaseId","status":"completed","summary":"Producer output.",""" +
    """"produced_outputs":$producedOutputs}"""

private const val TERMINAL_BLOCKING_REASON = "Upstream dependency was unavailable."

private fun terminalProducerOutput(phaseId: String, status: String): String {
  val reconciled = if (phaseId == "implement") ""","reconciled_state":{"reconciled":true}""" else ""
  return """{"contract_version":"0.2","phase_id":"$phaseId","status":"$status",""" +
    """"failure_disposition":"non_retryable_policy_conflict","summary":"Producer could not finish.",""" +
    """"produced_outputs":{"blocking_reasons":["$TERMINAL_BLOCKING_REASON"],""" +
    """"free_form":"not a projection"$reconciled}}"""
}

private val PREPLAN_MISSING_VALUE: String = envelope(
  "preplan",
  """{"prompt":"optional only"}""",
)

private val PREPLAN_LEFTOVER_DIGEST_KEYS: String = envelope(
  "preplan",
  """{"value":"prose preplan with leftover digest keys","affected_boundaries":["runtime-domain"],""" +
    """"risks":["Fixture risk."],"rollout":{"flag_required":false,"flag_pattern":"none","notes":"n"},""" +
    """"validation_strategy":["Focused runtime tests."]}""",
)

private val IMPLEMENT_LEFTOVER_RECEIPT_KEYS: String = envelope(
  "implement",
  """{"value":"Dense implement prose with leftover receipt keys beside value",""" +
    """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
    """"reconciled_state":{"reconciled":true}}""",
)

private val IMPLEMENT_LEFTOVER_RECEIPT_BODY: String = envelope(
  "implement",
  """{"value":"Dense implement prose with leftover receipt keys beside value",""" +
    """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
    """"reconciled_state":{"reconciled":true}}""",
)

private val VALIDATION_CHECKPOINT_AS_STRING: String = envelope(
  "validate",
  """{"validation_result":{"validation_status":"passed","checks":[{"name":"check","status":"passed"}],""" +
    """"repository_checkpoint":"repository_checkpoint=fixture-checkpoint-1",""" +
    """"gate_run_count":1,"gate_runs":[{"duration_ms":1,"outcome":"passed",""" +
    """"cache_mode":"forced_full","executed_work_units":1}]}}""",
)
