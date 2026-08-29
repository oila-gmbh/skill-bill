package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.UNPARSEABLE_GATE_FAILURE_RULE_ID
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateTest {
  @Test
  fun `FAILED gate with empty findings launches triage then repair with synthetic finding`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val triageLaunches = AtomicInteger(0)
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
        agentTriageLauncher = ValidationGateAgentTriageLauncher { findings ->
          triageLaunches.incrementAndGet()
          assertEquals(1, findings.findings.size)
          assertEquals(UNPARSEABLE_GATE_FAILURE_RULE_ID, findings.findings.single().ruleOrTestId)
          ValidationGateTriageResult.Captured("module=m fix spotless")
        },
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _, triagePlan ->
          repairLaunches.incrementAndGet()
          assertEquals(1, findings.findings.size)
          assertEquals(UNPARSEABLE_GATE_FAILURE_RULE_ID, findings.findings.single().ruleOrTestId)
          assertTrue(findings.findings.single().message.contains("Execution failed for task :spotlessCheck."))
          assertEquals("module=m fix spotless", triagePlan)
          completedRepair()
        },
      ),
    )
    assertEquals(1, triageLaunches.get())
    assertEquals(1, repairLaunches.get())
    assertIs<ValidationGateCycleResult.Terminal>(cycle)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(cycle.outcome)
    assertEquals("module=m fix spotless", progress.last().capturedTriagePlan)
  }

  @Test
  fun `discrete findings skip triage and launch repair directly`() {
    val findingOne = ValidationGateFinding("m1", "r1", "msg1", "loc1")
    val findingTwo = ValidationGateFinding("m2", "r2", "msg2", "loc2")
    val triageLaunches = AtomicInteger(0)
    val repairLaunches = AtomicInteger(0)
    val runner = ScriptedGateRunner(listOf(failedWith(findingOne, findingTwo), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, mutableListOf()).execute(
      ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentTriageLauncher = ValidationGateAgentTriageLauncher { _ ->
          triageLaunches.incrementAndGet()
          ValidationGateTriageResult.Captured("should not run")
        },
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _, triagePlan ->
          repairLaunches.incrementAndGet()
          assertEquals(2, findings.findings.size)
          assertEquals(null, triagePlan)
          completedRepair()
        },
      ),
    )
    assertEquals(0, triageLaunches.get())
    assertEquals(1, repairLaunches.get())
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `empty triage still launches repair and verify gate`() {
    val triageLaunches = AtomicInteger(0)
    val repairLaunches = AtomicInteger(0)
    val runner = ScriptedGateRunner(
      listOf(failedEmptyFindings("unparseable blob"), passed(forced = true)),
    )
    coordinator(declaredResolver(), runner, mutableListOf()).execute(
      ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentTriageLauncher = ValidationGateAgentTriageLauncher { _ ->
          triageLaunches.incrementAndGet()
          ValidationGateTriageResult.Empty
        },
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _, triagePlan ->
          repairLaunches.incrementAndGet()
          assertEquals(null, triagePlan)
          completedRepair()
        },
      ),
    )
    assertEquals(1, triageLaunches.get())
    assertEquals(1, repairLaunches.get())
    assertEquals(2, runner.calls)
  }

  @Test
  fun `triage does not substitute gate verify as repair proof`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val runner = ScriptedGateRunner(
      listOf(failedEmptyFindings("blob"), passed(forced = true)),
    )
    coordinator(declaredResolver(), runner, progress).execute(
      ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentTriageLauncher = skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher {
          skillbill.application.featuretask.validation.model.ValidationGateTriageResult.Captured("plan prose")
        },
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _, _ ->
          completedRepair()
        },
      ),
    )
    assertEquals(2, runner.calls)
    assertEquals(2, progress.last().gateRunCount)
    assertEquals(ValidationGateCacheMode.FORCED_FULL, runner.requests.last().cacheMode)
    assertEquals(true, runner.requests.last().terminalVerifying)
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
