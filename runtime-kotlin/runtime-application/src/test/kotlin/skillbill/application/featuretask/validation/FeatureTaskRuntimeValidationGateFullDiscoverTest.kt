package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateFullDiscoverTest {
  private val declaration = validationGateTestDeclaration

  @Test
  fun `FULL dirty discovery repair and green confirmation records two collect-all runs`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val compiler = ValidationGateFinding("app", "e: Unresolved reference", "compile error", "Foo.kt")
    val laterTest = ValidationGateFinding("later-module", "LaterTest.fails", "assertion failed", "LaterTest.kt")
    val firstRepairIds = mutableListOf<String>()
    val runner = ScriptedGateRunner(listOf(failedWith(compiler, laterTest), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = fullCycle { findings, _ ->
        firstRepairIds += findings.findings.map { it.ruleOrTestId }
        completedRepair(findings.findings)
      },
    )
    assertEquals(listOf("e: Unresolved reference", "LaterTest.fails"), firstRepairIds)
    assertEquals(2, runner.calls)
    assertEquals(listOf("echo", "collect-all"), runner.requests[0].argv)
    assertEquals(ValidationGateCacheMode.CACHE_ELIGIBLE, runner.requests[0].cacheMode)
    assertEquals(ValidationGateFindingParseMode.COLLECT_ALL, runner.requests[0].findingParseMode)
    assertEquals(listOf("echo", "collect-all-full"), runner.requests[1].argv)
    assertEquals(ValidationGateCacheMode.FORCED_FULL, runner.requests[1].cacheMode)
    assertEquals(ValidationGateFindingParseMode.COLLECT_ALL, runner.requests[1].findingParseMode)
    assertTrue(runner.requests.none { it.argv == declaration.fullGateCommand })
    assertTrue(runner.requests.none { it.argv == declaration.cacheBypassingFullGateCommand })
    assertTrue(runner.requests.none { "--tests" in it.argv })
    assertTrue(progress.any { it.discoveryIdentities.size == 2 })
    assertTrue(progress.any { it.substantiationReceipts.size == 2 })
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `FULL confirmation zero-work after repair is blocked and never passes`() {
    val runner = ScriptedGateRunner(
      listOf(
        failedWith(ValidationGateFinding("m", "t", "broken", "loc")),
        ValidationGateRunResult(
          exitCode = 0,
          durationMs = 3,
          outcome = ValidationGateRunOutcome.REJECTED_ZERO_WORK,
          cacheMode = ValidationGateCacheMode.FORCED_FULL,
          executedWorkUnits = 0,
          findings = emptyList(),
        ),
      ),
    )
    val cycle = coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = fullCycle { findings, _ -> completedRepair(findings.findings) },
    )
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(blocked.reason.contains("zero executed work"))
  }

  @Test
  fun `FULL 65-finding discovery pages two repairs then one confirmation without rerunning the gate`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val findings = (1..65).map { index ->
      ValidationGateFinding("m$index", "t$index", "message-$index", "loc-$index")
    }
    val pageSizes = mutableListOf<Int>()
    val ordinals = mutableListOf<Int>()
    val runner = ScriptedGateRunner(listOf(failedWith(*findings.toTypedArray()), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = fullCycle { page, repairIteration ->
        assertEquals(1, runner.calls)
        pageSizes += page.findings.size
        ordinals += repairIteration
        assertEquals(0, page.droppedCount)
        completedRepair(page.findings)
      },
    )
    assertEquals(listOf(64, 1), pageSizes)
    assertEquals(listOf(1, 2), ordinals)
    assertEquals(2, runner.calls)
    assertTrue(progress.all { it.remainingFindingsDroppedCount == 0 })
    assertTrue(progress.any { it.completeFindings.size == 65 })
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `failed confirmation findings become the next repair set without a third discovery`() {
    val discovery = ValidationGateFinding("app", "compile", "first", "A.kt")
    val confirmation = ValidationGateFinding("later", "LaterTest", "still failing", "LaterTest.kt")
    val repairIds = mutableListOf<List<String>>()
    val runner = ScriptedGateRunner(
      listOf(
        failedWith(discovery),
        failedWith(confirmation).copy(cacheMode = ValidationGateCacheMode.FORCED_FULL),
        passed(forced = true),
      ),
    )
    val cycle = coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = fullCycle { findings, _ ->
        repairIds += findings.findings.map { it.ruleOrTestId }
        completedRepair(findings.findings)
      },
    )
    assertEquals(listOf(listOf("compile"), listOf("LaterTest")), repairIds)
    assertEquals(1, runner.requests.count { it.cacheMode == ValidationGateCacheMode.CACHE_ELIGIBLE })
    assertEquals(2, runner.requests.count { it.cacheMode == ValidationGateCacheMode.FORCED_FULL })
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `confirmation retry cap exhaustion blocks with remaining findings persisted`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val finding = ValidationGateFinding("m", "t", "still broken", "loc")
    val runner = ScriptedGateRunner(
      listOf(failedWith(finding)) +
        List(3) { failedWith(finding).copy(cacheMode = ValidationGateCacheMode.FORCED_FULL) },
    )
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = fullCycle { findings, _ -> completedRepair(findings.findings) },
    )
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(blocked.reason.contains("confirmation retry cap"))
    assertEquals(1, blocked.remainingFindings?.findings?.size)
    assertTrue(progress.last().completeFindings.isNotEmpty())
    assertEquals(4, runner.calls)
    assertEquals(1, runner.requests.count { it.cacheMode == ValidationGateCacheMode.CACHE_ELIGIBLE })
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
