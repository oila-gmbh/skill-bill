package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskRuntimeValidationGateTest {
  @Test
  fun `FAILED gate with empty findings launches repair with synthetic finding`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val runner = ScriptedGateRunner(listOf(failedEmptyFindings(), passed()))
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
          completedRepair(findings.findings)
        },
      ),
    )
    assertEquals(1, repairLaunches.get())
    assertIs<ValidationGateCycleResult.Terminal>(cycle)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(cycle.outcome)
  }

  @Test
  fun `fromArtifactMap decodes legacy progress rows carrying retired coverage fields`() {
    val decoded = FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(
      mapOf(
        "contract_version" to skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
        "gate_run_count" to 1,
        "gate_runs" to listOf(
          mapOf(
            "duration_ms" to 1L,
            "outcome" to "passed",
            "cache_mode" to "cache_eligible",
            "executed_work_units" to 1,
          ),
        ),
        "remaining_findings" to emptyList<Map<String, String?>>(),
        "remaining_findings_dropped_count" to 0,
        "confirmation_retries_used" to 3,
        "substantiation_receipts" to listOf(mapOf("identity" to "legacy")),
      ),
    )
    assertEquals(1, decoded.gateRunCount)
    assertEquals(emptyList(), decoded.completeFindings)
  }
}
