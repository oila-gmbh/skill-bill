package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateTest {
  @Test
  fun `FAILED gate with empty findings launches repair with synthetic finding`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val runner = ScriptedGateRunner(
      listOf(failedEmptyFindings("Execution failed for task :spotlessCheck."), passed(forced = true)),
    )
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _ ->
          repairLaunches.incrementAndGet()
          assertEquals(1, findings.findings.size)
          assertEquals("unparseable_gate_failure", findings.findings.single().ruleOrTestId)
          assertTrue(findings.findings.single().message.contains("Execution failed for task :spotlessCheck."))
          completedRepair(findings.findings)
        },
      ),
    )
    assertEquals(1, repairLaunches.get())
    assertIs<ValidationGateCycleResult.Terminal>(cycle)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(cycle.outcome)
  }

  @Test
  fun `fromArtifactMap decodes findings_open with complete findings and legacy rows without repair_window_phase`() {
    val findingOne = linkedMapOf(
      "module" to "m1",
      "rule_or_test_id" to "r1",
      "message" to "msg1",
      "location" to "loc1",
    )
    val findingTwo = linkedMapOf(
      "module" to "m2",
      "rule_or_test_id" to "r2",
      "message" to "msg2",
      "location" to "loc2",
    )
    val decodedOpen = FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(
      progressArtifact(
        gateRunCount = 1,
        outcome = "failed",
        cacheMode = "cache_eligible",
        extra = mapOf(
          "complete_findings" to listOf(findingOne, findingTwo),
          "repair_window_phase" to "findings_open",
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN, decodedOpen.repairWindowPhase)
    assertEquals(2, decodedOpen.completeFindings.size)
    assertEquals(0, decodedOpen.repairsUsed)

    val decodedLegacy = FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(
      progressArtifact(
        gateRunCount = 1,
        outcome = "passed",
        cacheMode = "cache_eligible",
        extra = mapOf(
          "remaining_findings_dropped_count" to 0,
          "confirmation_retries_used" to 3,
          "substantiation_receipts" to listOf(mapOf("identity" to "legacy")),
        ),
      ),
    )
    assertEquals(FeatureTaskRuntimeValidationGateRepairWindowPhase.NONE, decodedLegacy.repairWindowPhase)
    assertEquals(1, decodedLegacy.gateRunCount)
    assertEquals(emptyList(), decodedLegacy.completeFindings)
    assertEquals(0, decodedLegacy.repairsUsed)

    val decodedWithRepairsUsed = FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(
      progressArtifact(
        gateRunCount = 2,
        outcome = "failed",
        cacheMode = "forced_full",
        extra = mapOf(
          "complete_findings" to listOf(findingOne),
          "repair_window_phase" to "findings_open",
          "repairs_used" to 3,
        ),
      ),
    )
    assertEquals(3, decodedWithRepairsUsed.repairsUsed)
  }

  private fun progressArtifact(
    gateRunCount: Int,
    outcome: String,
    cacheMode: String,
    extra: Map<String, Any?>,
  ): Map<String, Any?> = mapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "gate_run_count" to gateRunCount,
    "gate_runs" to listOf(
      mapOf(
        "duration_ms" to 1L,
        "outcome" to outcome,
        "cache_mode" to cacheMode,
        "executed_work_units" to 1,
      ),
    ),
    "remaining_findings" to emptyList<Map<String, String?>>(),
  ) + extra
}
