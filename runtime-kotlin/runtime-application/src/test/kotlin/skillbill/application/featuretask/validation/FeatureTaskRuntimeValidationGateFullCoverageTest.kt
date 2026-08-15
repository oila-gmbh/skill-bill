package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateFullCoverageTest {
  private val declaration = validationGateTestDeclaration

  @Test
  fun `omitted receipt relaunches the same FULL repair pass without a new gate run`() {
    val finding = ValidationGateFinding("app", "compile", "broken", "A.kt")
    val gateCountsAtLaunch = mutableListOf<Int>()
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val runner = ScriptedGateRunner(listOf(failedWith(finding), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = fullCycle { findings, _ ->
        gateCountsAtLaunch += progress.last().gateRunCount
        if (gateCountsAtLaunch.size == 1) {
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        } else {
          completedRepair(findings.findings)
        }
      },
    )
    assertEquals(listOf(1, 1), gateCountsAtLaunch)
    assertEquals(2, runner.calls)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `grouped two-identity plan with one receipt is not confirmation eligible`() {
    val first = ValidationGateFinding("app", "one", "broken", "A.kt")
    val second = ValidationGateFinding("app", "two", "broken", "B.kt")
    val launches = AtomicInteger(0)
    val runner = ScriptedGateRunner(listOf(failedWith(first, second), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = fullCycle { findings, _ ->
        val n = launches.incrementAndGet()
        if (n == 1) {
          completedRepair(findings.findings, receiptsFor = listOf(first), grouped = true)
        } else {
          completedRepair(findings.findings, grouped = true)
        }
      },
    )
    assertEquals(2, launches.get())
    assertEquals(2, runner.calls)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `leftover discovery identity fails confirmation even when the stub reports PASSED`() {
    val leftover = ValidationGateFinding("app", "compile", "still broken", "A.kt")
    val repairIds = mutableListOf<List<String>>()
    val runner = ScriptedGateRunner(
      listOf(
        failedWith(leftover),
        passed(forced = true).copy(findings = listOf(leftover)),
        passed(forced = true),
      ),
    )
    val cycle = coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = fullCycle { findings, _ ->
        repairIds += findings.findings.map { it.identity() }
        completedRepair(findings.findings)
      },
    )
    assertEquals(listOf(listOf(leftover.identity()), listOf(leftover.identity())), repairIds)
    assertEquals(3, runner.calls)
    assertTrue(runner.requests.none { it.argv == declaration.fullGateCommand })
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `novel confirmation identities join the next collect-all repair set`() {
    val discovery = ValidationGateFinding("app", "compile", "first", "A.kt")
    val novel = ValidationGateFinding("later", "LaterTest", "new failure", "LaterTest.kt")
    val repairIds = mutableListOf<List<String>>()
    val runner = ScriptedGateRunner(
      listOf(
        failedWith(discovery),
        passed(forced = true).copy(findings = listOf(novel)),
        passed(forced = true),
      ),
    )
    val cycle = coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = fullCycle { findings, _ ->
        repairIds += findings.findings.map { it.identity() }
        completedRepair(findings.findings)
      },
    )
    assertEquals(listOf(listOf(discovery.identity()), listOf(novel.identity())), repairIds)
    assertEquals(3, runner.calls)
    assertTrue(runner.requests.all { "--tests" !in it.argv })
    assertTrue(runner.requests.none { it.argv == declaration.fullGateCommand })
    assertEquals(listOf("echo", "collect-all-full"), runner.requests[1].argv)
    assertEquals(listOf("echo", "collect-all-full"), runner.requests[2].argv)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
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
