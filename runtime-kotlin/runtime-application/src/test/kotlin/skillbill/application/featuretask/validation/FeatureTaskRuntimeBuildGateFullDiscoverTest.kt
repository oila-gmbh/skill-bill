package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics

class FeatureTaskRuntimeBuildGateFullDiscoverTest {
  @Test
  fun `COLLECT_ALL surfaces compiler diagnostics through typed findings to one repair`() {
    val compilerFinding = ValidationGateFinding(
      module = "module-a",
      ruleOrTestId = "kotlin_compiler",
      message = "Unresolved reference: missing",
      location = "module-a/Foo.kt:3:1",
    )
    val repairRuleIds = mutableListOf<List<String>>()
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val cycle = FeatureTaskRuntimeBuildGateCoordinator(
      declaredResolver(
        validationGateTestDeclaration.copy(
          buildCommand = listOf("echo", "build"),
          cacheBypassingBuildCommand = listOf("echo", "build-verify"),
        ),
      ),
      ScriptedGateRunner(listOf(failedWith(compilerFinding), passed(forced = true))),
      FeatureTaskRuntimeBuildGateProgressStore(
        persist = { _, progressSnapshot, _ -> progress += progressSnapshot },
        load = { _, _ -> progress.lastOrNull() },
      ),
      repoLocalConfig(),
      NoopRuntimeDiagnostics,
    ).execute(
      ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _, _ ->
          repairRuleIds += findings.findings.map { it.ruleOrTestId }
          completedRepair()
        },
      ),
    )
    assertEquals(listOf(listOf("kotlin_compiler")), repairRuleIds)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    val discoveryFinding = progress.first().completeFindings.single()
    assertEquals("Unresolved reference: missing", discoveryFinding["message"])
    assertEquals("module-a/Foo.kt:3:1", discoveryFinding["location"])
    assertEquals(2, progress.last().gateRunCount)
  }
}
