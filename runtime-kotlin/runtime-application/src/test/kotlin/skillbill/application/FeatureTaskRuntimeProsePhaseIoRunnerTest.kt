package skillbill.application

import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeProsePhaseIoRunnerTest {
  @Test
  fun `leftover former keys on preplan plan implement and audit complete without schema rejection`() {
    val harness = proseHarness { phaseId ->
      when (phaseId) {
        "preplan" -> fatPreplan()
        "plan" -> fatPlan()
        "implement" -> fatImplement()
        "audit" -> fatAudit(verdict = "satisfied", value = SATISFIED_AUDIT_VALUE)
        else -> validJsonOutput(phaseId)
      }
    }

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(1, launched.count { it == "preplan" })
    assertEquals(1, launched.count { it == "plan" })
    assertEquals(1, launched.count { it == "implement" })
    assertEquals(1, launched.count { it == "audit" })
    assertTrue(harness.launchOrder().contains("review"))
    assertTrue(
      harness.io.database.rejectedDiagnostics().none { it.metadata.phaseId in PROSE_PHASES },
    )
  }

  @Test
  fun `audit satisfied still advances when value and leftover siblings look like gaps`() {
    val harness = seededThroughImplementHarness { phaseId ->
      if (phaseId == "audit") {
        fatAudit(verdict = "satisfied", value = GAPS_AUDIT_VALUE)
      } else {
        validJsonOutput(phaseId)
      }
    }

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launched = harness.launchedPromptPhaseOrder()
    assertFalse(launched.contains("implement"))
    assertEquals(1, launched.count { it == "audit" })
    assertTrue(harness.launchOrder().contains("review"))
    assertTrue(
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .none { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" },
    )
  }

  @Test
  fun `audit missing envelope verdict blocks before review and does not re-enter implement`() {
    var auditLaunches = 0
    val harness = seededThroughImplementHarness { phaseId ->
      if (phaseId == "audit") {
        auditLaunches += 1
        auditMissingVerdict()
      } else {
        validJsonOutput(phaseId)
      }
    }

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertEquals(1, auditLaunches)
    assertGateBlockNamesRule(blocked.blockedReason, "phase-output-schema")
    assertFalse(harness.launchOrder().contains("review"))
    assertFalse(harness.launchedPromptPhaseOrder().contains("implement"))
  }

  @Test
  fun `audit gaps_found with leftover sibling keys re-enters implement under the real validator`() {
    var auditLaunches = 0
    val harness = seededThroughImplementHarness { phaseId ->
      when (phaseId) {
        "audit" -> {
          auditLaunches += 1
          if (auditLaunches == 1) {
            fatAudit(verdict = "gaps_found", value = GAPS_AUDIT_VALUE)
          } else {
            auditSatisfiedOutput()
          }
        }
        "implement" -> fatImplement()
        else -> validJsonOutput(phaseId)
      }
    }

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(2, launched.count { it == "audit" })
    assertEquals(1, launched.count { it == "implement" })
    assertTrue(launched.indexOf("implement") > launched.indexOf("audit"))
    assertTrue(harness.launchOrder().contains("review"))
    assertTrue(
      harness.io.database.rejectedDiagnostics().none { it.metadata.phaseId == "audit" },
    )
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
  }

  private fun proseHarness(outputFor: (String) -> String): RunnerHarness = runnerHarness(
    launcher = RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      facts(outputFor(phaseId))
    },
    validator = realFeatureTaskRuntimePhaseOutputValidator,
    agentAssignment = phasePerAgentAssignment(),
    runtimeConfig = RuntimeHarnessConfig(planningProjectionValidator = realPlanningProjectionValidator),
  )

  private fun seededThroughImplementHarness(outputFor: (String) -> String): RunnerHarness {
    val harness = proseHarness(outputFor)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), validJsonOutput("preplan"))
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), validJsonOutput("plan"))
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), validJsonOutput("implement"))
    return harness
  }
}

private val PROSE_PHASES = setOf("preplan", "plan", "implement", "audit")

private const val SATISFIED_AUDIT_VALUE =
  """{\"gaps\":[],\"non_blocking_findings\":[]}"""

private const val GAPS_AUDIT_VALUE =
  """{\"gaps\":[{\"criterion\":\"AC-002\",\"note\":\"$AUDIT_GAP_MESSAGE\"}],\"non_blocking_findings\":[]}"""

private fun fatPreplan(): String = phaseProse(
  phaseId = "preplan",
  value = """{\"affected_boundaries\":[\"runtime-domain\"],\"verdict\":\"needs_fix\"}""",
  leftoverKeys = """
      "affected_boundaries": ["runtime-domain"],
      "risks": ["Fixture risk."],
      "rollout": {"flag_required": false, "flag_pattern": "none", "notes": "n"},
      "validation_strategy": ["Focused runtime tests."],
  """.trimIndent(),
)

private fun fatPlan(): String = phaseProse(
  phaseId = "plan",
  value = """{\"verdict\":\"needs_fix\",\"mode\":\"decompose\",\"tasks\":[]}""",
  leftoverKeys = """
      "projection_kind": "executable_plan",
      "mode": "direct",
      "tasks": [{"task_id": "task-1", "depends_on": [], "criterion_refs": ["AC-001"]}],
      "validation_strategy": ["unit"],
  """.trimIndent(),
)

private fun fatImplement(): String = phaseProse(
  phaseId = "implement",
  value = """{\"completed_task_ids\":[\"task-1\"],\"changed_paths\":[\"src/Foo.kt\"]}""",
  leftoverKeys = """
      "completed_task_ids": ["task-1"],
      "changed_paths": ["src/Foo.kt"],
      "reconciled_state": {"reconciled": true},
  """.trimIndent(),
)

private fun fatAudit(verdict: String, value: String): String = phaseProse(
  phaseId = "audit",
  value = value,
  leftoverKeys = """
      "gaps": [{"criterion": "AC-002", "note": "$AUDIT_GAP_MESSAGE"}],
      "non_blocking_findings": [],
      "unmet_criteria": ["AC-002"],
  """.trimIndent(),
  verdict = verdict,
)

private fun auditMissingVerdict(): String = phaseProse(
  phaseId = "audit",
  value = SATISFIED_AUDIT_VALUE,
  leftoverKeys = "",
)

private fun phaseProse(
  phaseId: String,
  value: String,
  leftoverKeys: String,
  verdict: String? = null,
): String {
  val verdictLine = verdict?.let { """    "verdict": "$it",""" + "\n" }.orEmpty()
  val leftoverBlock = leftoverKeys.trim().trimEnd(',').let { keys ->
    if (keys.isEmpty()) "" else "$keys,\n      "
  }
  return """
  {
    "contract_version": "0.6",
    "phase_id": "$phaseId",
    "status": "completed",
    "summary": "Phase produced a validated output.",
$verdictLine    "produced_outputs": {
      "prompt": "Forward this prose.",
      $leftoverBlock"value": "$value"
    }
  }
  """.trimIndent()
}
