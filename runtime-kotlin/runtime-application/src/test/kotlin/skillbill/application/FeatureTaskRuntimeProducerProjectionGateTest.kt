@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
  fun `a plan output missing projection_kind blocks plan and never reaches implement`() {
    val outcome = runRejectedProducer("plan", PLAN_MISSING_PROJECTION_KIND)

    assertEquals(1, outcome.launchCount("plan"), "a one-attempt budget leaves no relaunch")
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "executable_plan", "projection_kind is missing")
    assertTrue(!outcome.launched("implement"), "a rejected producer must not reach its consumer")
  }

  @Test
  fun `a plan output on the wrong contract version blocks plan`() {
    val outcome = runRejectedProducer("plan", PLAN_WRONG_CONTRACT_VERSION)

    assertEquals(1, outcome.launchCount("plan"))
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "contract_version")
  }

  @Test
  fun `a plan output with an undeclared dependency reference blocks plan`() {
    val outcome = runRejectedProducer("plan", PLAN_UNDECLARED_DEPENDENCY)

    assertEquals(1, outcome.launchCount("plan"))
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "undeclared task")
  }

  @Test
  fun `a preplan digest whose rollout is an array instead of an object blocks preplan (RDN-29)`() {
    val outcome = runRejectedProducer("preplan", PREPLAN_ROLLOUT_AS_ARRAY)

    assertEquals(1, outcome.launchCount("preplan"))
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "preplanning_digest", "rollout")
    assertTrue(!outcome.launched("plan"))
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
  fun `a decompose-shaped preplan output faces the gate because preplan owns no decompose backstop`() {
    val outcome = runRejectedProducer("preplan", PREPLAN_DECOMPOSE_SHAPED)

    assertEquals(1, outcome.launchCount("preplan"))
    assertGateBlockNamesRule(outcome.blocked.blockedReason, "producer-projection")
    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "preplanning_digest", "is not a valid")
    assertEquals(0, outcome.launchCount("plan"), "a decompose-shaped preplan must not bypass the gate and wedge plan")
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
    val outcome = runRejectedProducer("plan", PLAN_MISSING_PROJECTION_KIND)

    assertDiagnosticNamesConstraint(outcome.diagnosticReason, "projection_kind is missing")
    assertTrue(
      !outcome.blocked.blockedReason.contains("projection_kind is missing"),
      "the operator surface points at the diagnostic instead of embedding the constraint",
    )
  }

  @Test
  fun `an oversized projection failure text is bounded by the existing schema-gate detail truncation`() {
    val longReason = "x".repeat(5_000)
    var preplanAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "preplan") preplanAttempts += 1
        facts(validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        planningProjectionValidator = OversizedReasonPlanningProjectionValidator(longReason),
      ),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals(1, preplanAttempts)
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
        facts(if (phaseId == "plan") PLAN_WITH_UNDECLARED_TOP_LEVEL_KEY else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
    )

    harness.runner.run(harness.request())

    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "plan" }, "an absorbed extra key must not cost a fix-loop attempt")
    assertTrue(launched.contains("implement"), "the absorbed plan must advance to its consumer")
    harness.launcher.requests.forEach { request ->
      assertNoRawResponseSpan(requireNotNull(request.skillRunRequest.promptOverride), "private body")
    }
  }

  @Test
  fun `a missing required field on a closed projection object names the required property`() {
    val reason = realValidatorRejectionReason("plan", PLAN_TASK_MISSING_TEST_OBLIGATIONS)

    assertDiagnosticNamesConstraint(reason, "required property 'test_obligations' not found")
  }

  @Test
  fun `a wrong-typed field on a closed projection object names the found and expected types`() {
    val reason = realValidatorRejectionReason("plan", PLAN_TASKS_AS_STRING)

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

private val PLAN_MISSING_PROJECTION_KIND: String = envelope(
  "plan",
  """{"contract_version":"0.1","mode":"direct","tasks":[{"task_id":"task-1","description":"t",""" +
    """"criterion_refs":["AC-001"],"test_obligations":["parity"]}],"validation_strategy":["v"]}""",
)

private val PLAN_WRONG_CONTRACT_VERSION: String = envelope(
  "plan",
  """{"projection_kind":"executable_plan","contract_version":"0.0","mode":"direct",""" +
    """"tasks":[{"task_id":"task-1","description":"t","criterion_refs":["AC-001"],""" +
    """"test_obligations":["parity"]}],"validation_strategy":["v"]}""",
)

private val PLAN_UNDECLARED_DEPENDENCY: String = envelope(
  "plan",
  """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":[{"task_id":"task-1","depends_on":["task-ghost"],"description":"t",""" +
    """"criterion_refs":["AC-001"],"test_obligations":["parity"]}],"validation_strategy":["v"]}""",
)

// A completed producing-phase output shaped like a decomposition package (mode=decompose, no
// projection_kind). Only `plan` has a decompose stopper backstop, so for any other producer this must
// still face the projection gate rather than the exemption. The implement variant carries
// reconciled_state so it clears the separate mutating-phase reconciliation gate and actually reaches
// the projection gate under test.
private val PREPLAN_DECOMPOSE_SHAPED: String = envelope(
  "preplan",
  """{"mode":"decompose","reason":"looks like a decompose but preplan owns no stopper"}""",
)

private val IMPLEMENT_DECOMPOSE_SHAPED: String = envelope(
  "implement",
  """{"mode":"decompose","reason":"looks like a decompose but implement owns no stopper",""" +
    """"reconciled_state":{"reconciled":true}}""",
)

private val PREPLAN_ROLLOUT_AS_ARRAY: String = envelope(
  "preplan",
  """{"projection_kind":"preplanning_digest","contract_version":"0.1","affected_boundaries":["b"],""" +
    """"risks":["r"],"rollout":[{"flag_required":false,"notes":"n"}],"validation_strategy":["v"]}""",
)

private val IMPLEMENT_DEVIATIONS_AS_STRINGS: String = envelope(
  "implement",
  """{"projection_kind":"implementation_receipt","contract_version":"0.1","completed_task_ids":["task-1"],""" +
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

private val PLAN_WITH_UNDECLARED_TOP_LEVEL_KEY: String = envelope(
  "plan",
  """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":[{"task_id":"task-1","description":"t","criterion_refs":["AC-001"],""" +
    """"test_obligations":["parity"]}],"validation_strategy":["v"],"smuggled_narration":"private body"}""",
)

private val PLAN_TASK_MISSING_TEST_OBLIGATIONS: String = envelope(
  "plan",
  """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":[{"task_id":"task-1","description":"t","criterion_refs":["AC-001"]}],""" +
    """"validation_strategy":["v"]}""",
)

private val PLAN_TASKS_AS_STRING: String = envelope(
  "plan",
  """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct",""" +
    """"tasks":"task-1 does the work","validation_strategy":["v"]}""",
)

// reconciled_state stays true so the separate mutating-phase reconciliation gate passes and the receipt
// actually reaches the projection gate, where `reconciled` is const:true and rejects.
private val RECEIPT_ASSERTING_UNRECONCILED: String = envelope(
  "implement",
  """{"projection_kind":"implementation_receipt","contract_version":"0.1","completed_task_ids":["task-1"],""" +
    """"changed_paths":["src/Foo.kt"],"tests_executed":[{"name":"FooTest","outcome":"passed"}],""" +
    """"reconciliation_evidence":{"reconciled":false,"evidence":"Work is unfinished."},""" +
    """"repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},"reconciled_state":{"reconciled":true}}""",
)
