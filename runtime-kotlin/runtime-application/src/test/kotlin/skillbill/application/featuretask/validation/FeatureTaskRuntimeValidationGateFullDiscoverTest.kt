package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateFullDiscoverTest {
  private val declaration = validationGateTestDeclaration

  @Test
  fun `one check hands every finding to one repair and confirms with cache-bypassing verify`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val compiler = ValidationGateFinding("app", "e: Unresolved reference", "compile error", "Foo.kt")
    val laterTest = ValidationGateFinding("later-module", "LaterTest.fails", "assertion failed", "LaterTest.kt")
    val repairSets = mutableListOf<List<String>>()
    val runner = ScriptedGateRunner(listOf(failedWith(compiler, laterTest), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = fullCycle { findings, _, _ ->
        repairSets += findings.findings.map { it.ruleOrTestId }
        completedRepair(findings.findings)
      },
    )
    assertEquals(listOf(listOf("e: Unresolved reference", "LaterTest.fails")), repairSets)
    assertEquals(2, runner.calls)
    assertEquals(listOf("echo", "collect-all"), runner.requests[0].argv)
    assertEquals(ValidationGateCacheMode.CACHE_ELIGIBLE, runner.requests[0].cacheMode)
    assertEquals(false, runner.requests[0].terminalVerifying)
    assertEquals(listOf("echo", "collect-all-full"), runner.requests[1].argv)
    assertEquals(ValidationGateCacheMode.FORCED_FULL, runner.requests[1].cacheMode)
    assertEquals(true, runner.requests[1].terminalVerifying)
    assertTrue(runner.requests.none { it.argv == declaration.fullGateCommand })
    assertTrue(progress.any { it.completeFindings.size == 2 })
    assertTrue(
      progress.any {
        it.repairWindowPhase == FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN
      },
    )
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `a large finding set is never split across repair launches`() {
    val findings = (1..65).map { index ->
      ValidationGateFinding("m$index", "t$index", "message-$index", "loc-$index")
    }
    val launchSizes = mutableListOf<Int>()
    val runner = ScriptedGateRunner(listOf(failedWith(*findings.toTypedArray()), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = fullCycle { page, _, _ ->
        launchSizes += page.findings.size
        completedRepair(page.findings)
      },
    )
    assertEquals(listOf(65), launchSizes)
    assertEquals(2, runner.calls)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `failing verify keeps launching repair until the three-turn cap then records remaining findings`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val discovery = ValidationGateFinding("app", "compile", "first", "A.kt")
    val verifyFinding = ValidationGateFinding("later", "LaterTest", "still failing", "LaterTest.kt")
    val repairIds = mutableListOf<List<String>>()
    val maxTurns = FeatureTaskRuntimeValidationGateCoordinator.MAX_REPAIR_TURNS
    val runnerResults = mutableListOf(failedWith(discovery))
    repeat(maxTurns) { runnerResults += failedWith(verifyFinding) }
    val runner = ScriptedGateRunner(runnerResults)
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = fullCycle { findings, _, _ ->
        repairIds += findings.findings.map { it.ruleOrTestId }
        completedRepair(findings.findings)
      },
    )
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertEquals(maxTurns, repairIds.size)
    assertEquals(listOf("compile"), repairIds.first())
    assertTrue(repairIds.drop(1).all { it == listOf("LaterTest") })
    assertEquals(1 + maxTurns, runner.calls)
    assertTrue(blocked.reason.contains("after $maxTurns repair"))
    assertTrue(blocked.reason.contains("recorded for the operator"))
    assertEquals("LaterTest", blocked.remainingFindings?.findings?.single()?.ruleOrTestId)
    assertEquals(
      FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
      progress.last().repairWindowPhase,
    )
    assertEquals(1, progress.last().completeFindings.size)
    assertEquals(maxTurns, progress.last().repairsUsed)
  }

  private fun fullCycle(repair: ValidationGateAgentRepairLauncher): ValidationGateCycleRequest =
    ValidationGateCycleRequest(
      repoRoot = validationGateTestRepoRoot,
      request = minimalRequest(),
      validationDepth = ValidationDepth.FULL,
      changedPaths = listOf("runtime-kotlin/foo.kt"),
      repositoryCheckpoint = "checkpoint",
      agentRepairLauncher = repair,
    )
}
