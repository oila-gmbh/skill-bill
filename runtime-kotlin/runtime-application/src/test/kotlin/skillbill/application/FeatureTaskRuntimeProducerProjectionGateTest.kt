@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.producerProjectionGateReason
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-140 Subtask 1: the producer gate. A phase that owns a bounded planning projection must emit
 * one its consumer can parse; a completed-but-malformed digest/plan/receipt settles the run at that
 * phase rather than reaching its consumer. The same
 * `featureTaskRuntimePlanningProjectionFromEnvelope` the launch seam uses decides acceptance
 * (AC-003), and the private diagnostic keeps the expected projection kind and the validation failure
 * (AC-006) that a producer would need to repair it.
 *
 * The output-gate budget is one attempt, so a rejection is terminal: what each test pins is that the
 * gate rejects its fixture, blocks at the producing phase, keeps the consumer unlaunched, and records
 * the constraint privately.
 */
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
  fun `an implement receipt whose deviations are free-text strings blocks implement (RDN-29)`() {
    val outcome = runRejectedProducer("implement", IMPLEMENT_DEVIATIONS_AS_STRINGS)

    assertEquals(1, outcome.launchCount("implement"))
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "implementation_receipt", "deviations")
    assertTrue(!outcome.launched("audit"))
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
  fun `a validation receipt with a string checkpoint blocks validate`() {
    val outcome = runRejectedProducer("validate", VALIDATION_CHECKPOINT_AS_STRING)

    assertEquals(1, outcome.launchCount("validate"))
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "consumer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "repository_checkpoint", "non-blank fingerprint")
    assertTrue(!outcome.launched("write_history"))
  }

  @Test
  fun `an implement re-entry under the implement phase id is gated by the same producer branch`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "implement" -> {
            implementLaunches += 1
            facts(if (implementLaunches == 1) validJsonOutput("implement") else IMPLEMENT_DEVIATIONS_AS_STRINGS)
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = reviewFixRuntimeConfig(2),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      harness.runner.run(harness.request(IMPLEMENT_REENTRY_CYCLE)),
    )

    assertEquals(
      2,
      implementLaunches,
      "the re-entered implement must reject the malformed receipt and stop, not relaunch",
    )
    assertEquals("implement", blocked.lastIncompletePhase)
    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(
      harness.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "implement" }.metadata.reason,
      "implementation_receipt",
      "deviations",
    )
  }

  @Test
  fun `a decompose-shaped implement receipt faces the gate because only plan owns the decompose backstop`() {
    val outcome = runRejectedProducer("implement", IMPLEMENT_DECOMPOSE_SHAPED)

    assertEquals(1, outcome.launchCount("implement"))
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "implementation_receipt", "is not a valid")
    assertEquals(
      0,
      outcome.launchCount("audit"),
      "a decompose-shaped implement must not bypass the gate and wedge audit",
    )
  }

  @Test
  fun `an implement receipt with an invented checkpoint advances without blocking`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") IMPLEMENT_INVENTED_CHECKPOINT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "implement" })
    assertTrue(harness.launchedPromptPhaseOrder().contains("audit"))
  }

  @Test
  fun `conforming preplan plan and implement projections each advance without a retry`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val order = harness.launchedPromptPhaseOrder()
    listOf("preplan", "plan", "implement").forEach { phaseId ->
      assertEquals(1, order.count { it == phaseId }, "conforming $phaseId must advance on its first launch")
    }
  }

  @Test
  fun `the private diagnostic carries the violated constraint the blocked reason withholds`() {
    val outcome = runRejectedProducer("implement", IMPLEMENT_DEVIATIONS_AS_STRINGS)

    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "deviations")
    assertTrue(
      !outcome.blocked.blockedReason.contains("projection_kind is missing"),
      "the operator surface points at the diagnostic instead of embedding the constraint",
    )
  }

  @Test
  fun `an oversized projection failure text is bounded by the existing schema-gate detail truncation`() {
    val longReason = "x".repeat(5_000)
    var implementAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement") implementAttempts += 1
        facts(validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        planningProjectionValidator = OversizedReasonPlanningProjectionValidator(longReason),
      ),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals(1, implementAttempts)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertGateBlockNamesRule(blocked.blockedReason, "producer-projection")
    assertTrue(
      !blocked.blockedReason.contains("x".repeat(1_000)),
      "the validator's oversized reason must be truncated, not embedded whole",
    )
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

  @Test
  fun `an extra key on a closed projection object is absorbed and consumes no fix-loop attempt`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "implement") IMPLEMENT_WITH_UNDECLARED_TOP_LEVEL_KEY else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    harness.runner.run(harness.request())

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "implement" }, "an absorbed extra key must not cost a fix-loop attempt")
    assertTrue(launched.contains("audit"), "the absorbed receipt must advance to its consumer")
    harness.launcher.requests.forEach { request ->
      assertNoRawResponseSpan(requireNotNull(request.skillRunRequest.promptOverride), "private body")
    }
  }

  @Test
  fun `a missing required field on a closed projection object names the required property`() {
    val reason = realValidatorRejectionReason("implement", IMPLEMENT_MISSING_RECONCILIATION)

    assertDiagnosticNamesConstraint(reason, "required property 'reconciliation_evidence' not found")
  }

  @Test
  fun `a wrong-typed field on a closed projection object names the found and expected types`() {
    val reason = realValidatorRejectionReason("implement", IMPLEMENT_CHANGED_PATHS_AS_STRING)

    assertDiagnosticNamesConstraint(reason, "string found, array expected")
  }

  @Test
  fun `a receipt asserting reconciled false is told which envelope carries unfinished work`() {
    val reason = realValidatorRejectionReason("implement", RECEIPT_ASSERTING_UNRECONCILED)

    assertDiagnosticNamesConstraint(reason, "must be the constant value")
  }

  private fun realValidatorRejectionReason(targetPhase: String, malformedOutput: String): String {
    val outcome = runRejectedProducer(
      targetPhase,
      malformedOutput,
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "producer-projection")
    return outcome.diagnosticReason
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

// A validator that rejects every payload with an oversized reason, so the truncation bound is the
// only thing that can keep the block reason within the schema-gate detail ceiling.
private class OversizedReasonPlanningProjectionValidator(
  private val reason: String,
) : FeatureTaskRuntimePlanningProjectionValidator {
  private var calls = 0

  override fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String) {
    calls += 1
    if (calls == 1) {
      throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(sourceLabel = sourceLabel, reason = reason)
    }
  }
}

// A cycle whose backward edge re-enters the MUTATING implement phase under its own phase id (the
// audit-gap remediation shape), so the producer gate is exercised on a re-run, not only a first run.
private val IMPLEMENT_REENTRY_CYCLE = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
  forwardPhaseIds = listOf("preplan", "plan", "implement", "audit", "review"),
  backwardEdges = listOf(
    skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "review",
      triggeringVerdict = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      destinationPhaseId = "implement",
      loopId = "implement-reentry",
      perEdgeCap = 2,
    ),
  ),
)

private fun envelope(phaseId: String, producedOutputs: String): String =
  """{"contract_version":"0.2","phase_id":"$phaseId","status":"completed","summary":"Producer output.",""" +
    """"produced_outputs":$producedOutputs}"""

private const val TERMINAL_BLOCKING_REASON = "Upstream dependency was unavailable."

// A blocked or failed producing-phase envelope whose produced_outputs would fail its projection
// contract (free-form body, no projection_kind). It must settle through the terminal path, never the
// producer projection gate. A non-retryable disposition makes the settlement deterministic. The
// mutating implement phase still owes a reconciliation report even when it blocks (that pre-existing
// gate is separate from the projection gate), so its terminal body carries reconciled_state.
private fun terminalProducerOutput(phaseId: String, status: String): String {
  val reconciled = if (phaseId == "implement") ""","reconciled_state":{"reconciled":true}""" else ""
  return """{"contract_version":"0.2","phase_id":"$phaseId","status":"$status",""" +
    """"failure_disposition":"non_retryable_policy_conflict","summary":"Producer could not finish.",""" +
    """"produced_outputs":{"blocking_reasons":["$TERMINAL_BLOCKING_REASON"],""" +
    """"free_form":"not a projection"$reconciled}}"""
}

private val IMPLEMENT_WITH_UNDECLARED_TOP_LEVEL_KEY: String = envelope(
  "implement",
  """{"projection_kind":"implementation_receipt","contract_version":"0.2","completed_task_ids":["task-1"],""" +
    """"changed_paths":["src/Foo.kt"],"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":true,"evidence":"Tree at target."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true},""" +
    """"smuggled_narration":"private body"}""",
)

private val IMPLEMENT_MISSING_RECONCILIATION: String = envelope(
  "implement",
  """{"projection_kind":"implementation_receipt","contract_version":"0.2","completed_task_ids":["task-1"],""" +
    """"changed_paths":["src/Foo.kt"],"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true}}""",
)

private val IMPLEMENT_CHANGED_PATHS_AS_STRING: String = envelope(
  "implement",
  """{"projection_kind":"implementation_receipt","contract_version":"0.2","completed_task_ids":["task-1"],""" +
    """"changed_paths":"src/Foo.kt","tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":true,"evidence":"Tree at target."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true}}""",
)

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

private val IMPLEMENT_DECOMPOSE_SHAPED: String = envelope(
  "implement",
  """{"mode":"decompose","reason":"looks like a decompose but implement owns no stopper",""" +
    """"reconciled_state":{"reconciled":true}}""",
)

private val IMPLEMENT_INVENTED_CHECKPOINT: String = envelope(
  "implement",
  """{"projection_kind":"implementation_receipt","contract_version":"0.2","completed_task_ids":["task-1"],""" +
    """"changed_paths":["src/Foo.kt"],"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":true,"evidence":"Tree at target."},""" +
    """"repository_checkpoint":{"fingerprint":"tracked_diff=deadbeef;new_service=deadbeef;status=deadbeef"},""" +
    """"reconciled_state":{"reconciled":true}}""",
)

private val IMPLEMENT_DEVIATIONS_AS_STRINGS: String = envelope(
  "implement",
  """{"projection_kind":"implementation_receipt","contract_version":"0.2","completed_task_ids":["task-1"],""" +
    """"changed_paths":["src/Foo.kt"],"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"deviations":["free-text deviation instead of a ref and note object"],""" +
    """"reconciliation_evidence":{"reconciled":true,"evidence":"Tree at target."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true}}""",
)

private val VALIDATION_CHECKPOINT_AS_STRING: String = envelope(
  "validate",
  """{"validation_result":{"validation_status":"passed","checks":[{"name":"check","status":"passed"}],""" +
    """"repository_checkpoint":"repository_checkpoint=fixture-checkpoint-1",""" +
    """"gate_run_count":1,"gate_runs":[{"duration_ms":1,"outcome":"passed",""" +
    """"cache_mode":"forced_full","executed_work_units":1}]}}""",
)

// SKILL-152 AC-009 fixtures: one per closed-object rejection class, each otherwise conforming so the
// asserted constraint is the only violation the schema reports.

// reconciled_state stays true so the separate mutating-phase reconciliation gate passes and the receipt
// actually reaches the projection gate, where `reconciled` is const:true and rejects.
private val RECEIPT_ASSERTING_UNRECONCILED: String = envelope(
  "implement",
  """{"projection_kind":"implementation_receipt","contract_version":"0.2","completed_task_ids":["task-1"],""" +
    """"changed_paths":["src/Foo.kt"],"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":false,"evidence":"Work is unfinished."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true}}""",
)
