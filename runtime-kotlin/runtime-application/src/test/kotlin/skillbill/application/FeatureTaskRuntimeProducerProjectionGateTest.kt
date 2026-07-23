@file:Suppress("MaxLineLength")

package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeFixLoopPolicy
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
 * one its consumer can parse; a completed-but-malformed digest/plan/receipt re-enters that phase's
 * own bounded fix loop and blocks only at the existing cap (AC-001, AC-002). The same
 * `featureTaskRuntimePlanningProjectionFromEnvelope` the launch seam uses decides acceptance
 * (AC-003), and the rejection reason names the phase, the expected projection kind, and the
 * validation failure (AC-006).
 */
class FeatureTaskRuntimeProducerProjectionGateTest {
  private val cap = FeatureTaskRuntimeFixLoopPolicy.MAX_FIX_LOOP_ITERATIONS

  // --- plan: the three AC-001 shapes ------------------------------------------------------------

  @Test
  fun `a plan output missing projection_kind re-enters the fix loop and blocks at the cap`() {
    val outcome = runBlockingProducer("plan", PLAN_MISSING_PROJECTION_KIND)

    assertEquals("plan", outcome.blocked.lastIncompletePhase)
    assertEquals(cap, outcome.launchCount("plan"), "the plan phase must retry to the cap, not settle completed")
    assertContains(outcome.blocked.blockedReason, "executable_plan")
    assertContains(outcome.blocked.blockedReason, "projection_kind is missing")
    assertTrue(outcome.launchedNever("implement"), "a malformed producer must not advance to its consumer")
  }

  @Test
  fun `a plan output on the wrong contract version re-enters the fix loop and blocks at the cap`() {
    val outcome = runBlockingProducer("plan", PLAN_WRONG_CONTRACT_VERSION)

    assertEquals("plan", outcome.blocked.lastIncompletePhase)
    assertEquals(cap, outcome.launchCount("plan"))
    assertContains(outcome.blocked.blockedReason, "contract_version")
  }

  @Test
  fun `a plan output with an undeclared dependency reference re-enters the fix loop and blocks at the cap`() {
    val outcome = runBlockingProducer("plan", PLAN_UNDECLARED_DEPENDENCY)

    assertEquals("plan", outcome.blocked.lastIncompletePhase)
    assertEquals(cap, outcome.launchCount("plan"))
    assertContains(outcome.blocked.blockedReason, "undeclared task")
  }

  // --- preplan and implement, incl. the two RDN-29 production shapes ----------------------------

  @Test
  fun `a preplan digest whose rollout is an array instead of an object blocks at the cap (RDN-29)`() {
    val outcome = runBlockingProducer("preplan", PREPLAN_ROLLOUT_AS_ARRAY)

    assertEquals("preplan", outcome.blocked.lastIncompletePhase)
    assertEquals(cap, outcome.launchCount("preplan"))
    assertContains(outcome.blocked.blockedReason, "preplanning_digest")
    assertContains(outcome.blocked.blockedReason, "rollout")
    assertTrue(outcome.launchedNever("plan"))
  }

  @Test
  fun `an implement receipt whose deviations are free-text strings blocks at the cap (RDN-29)`() {
    val outcome = runBlockingProducer("implement", IMPLEMENT_DEVIATIONS_AS_STRINGS)

    assertEquals("implement", outcome.blocked.lastIncompletePhase)
    assertEquals(cap, outcome.launchCount("implement"))
    assertContains(outcome.blocked.blockedReason, "implementation_receipt")
    assertContains(outcome.blocked.blockedReason, "deviations")
    assertTrue(outcome.launchedNever("audit"))
  }

  @Test
  fun `an implement re-entry under the implement phase id is gated by the same producer branch`() {
    // The remediation loop re-enters PHASE_IMPLEMENT itself; the re-run's output is the receipt audit
    // and review parse, so the producer gate must reject a malformed re-run exactly as it rejects a
    // first run — no phase-specific special casing beyond the shared mapping. The forward implement
    // conforms so the run reaches review; review drives a needs_fix re-entry; the re-entered implement
    // then emits a malformed receipt the gate rejects, blocking the run at implement.
    var implementLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "implement" -> {
            implementLaunches += 1
            facts(if (implementLaunches == 1) validJsonOutput("implement") else IMPLEMENT_DEVIATIONS_AS_STRINGS)
          }
          "review" -> facts(verdictReviewOutput("needs_fix"))
          else -> facts(validJsonOutput(phaseId))
        }
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request(IMPLEMENT_REENTRY_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    // One conforming forward launch, then a re-entry that retries the malformed receipt to the cap.
    assertEquals(1 + cap, implementLaunches, "the re-entered implement must retry the malformed receipt to the cap")
    assertContains(blocked.blockedReason, "implementation_receipt")
    assertContains(blocked.blockedReason, "deviations")
  }

  // --- conforming fixtures advance unchanged ----------------------------------------------------

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

  // --- AC-006 / AC-004: reason content, truncation, and the shared validator port ---------------

  @Test
  fun `the block reason names the phase the expected projection kind and the validation failure`() {
    val outcome = runBlockingProducer("plan", PLAN_MISSING_PROJECTION_KIND)

    val reason = outcome.blocked.blockedReason
    assertContains(reason, "Phase 'plan'")
    assertContains(reason, "executable_plan")
    assertContains(reason, "projection_kind is missing")
  }

  @Test
  fun `the retry prompt context carries the projection rejection so the agent can fix it`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") PLAN_MISSING_PROJECTION_KIND else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    // The second plan launch's prompt must carry the first rejection as retry context.
    val planPrompts = harness.launcher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { phaseIdFromPrompt(it) == "plan" }
    assertEquals(cap, planPrompts.size)
    assertContains(planPrompts[1], "executable_plan")
    assertContains(planPrompts[1], "projection_kind is missing")
  }

  @Test
  fun `an oversized projection failure text is bounded by the existing schema-gate detail truncation`() {
    val longReason = "x".repeat(5_000)
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        // Any completed plan output trips the gate; the injected validator supplies the oversized text.
        facts(validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        planningProjectionValidator = OversizedReasonPlanningProjectionValidator(longReason),
      ),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("preplan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "… [truncated]")
    assertTrue(
      !blocked.blockedReason.contains("x".repeat(1_000)),
      "the validator's oversized reason must be truncated, not embedded whole",
    )
  }

  // --- AC-005: blocked and failed outputs bypass the projection gate ----------------------------

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

  // --- helpers ----------------------------------------------------------------------------------

  private class ProducerBlockOutcome(
    val blocked: FeatureTaskRuntimeRunReport.Blocked,
    private val launchedOrder: List<String>,
  ) {
    fun launchCount(phaseId: String): Int = launchedOrder.count { it == phaseId }
    fun launchedNever(phaseId: String): Boolean = launchedOrder.none { it == phaseId }
  }

  private fun runBlockingProducer(targetPhase: String, malformedOutput: String): ProducerBlockOutcome {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == targetPhase) malformedOutput else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )
    val report = harness.runner.run(harness.request())
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    return ProducerBlockOutcome(blocked, harness.launchedPromptPhaseOrder())
  }
}

// A validator that rejects every payload with an oversized reason, so the truncation bound is the
// only thing that can keep the block reason within the schema-gate detail ceiling.
private class OversizedReasonPlanningProjectionValidator(
  private val reason: String,
) : FeatureTaskRuntimePlanningProjectionValidator {
  override fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String): Unit =
    throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(sourceLabel = sourceLabel, reason = reason)
}

// A cycle whose backward edge re-enters the MUTATING implement phase under its own phase id (the
// audit-gap remediation shape), so the producer gate is exercised on a re-run, not only a first run.
private val IMPLEMENT_REENTRY_CYCLE = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
  forwardPhaseIds = listOf("preplan", "plan", "implement", "audit", "review"),
  backwardEdges = listOf(
    skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "review",
      triggeringVerdict = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict("needs_fix"),
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
