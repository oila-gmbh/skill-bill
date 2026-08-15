package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateBuildOnlyTest {
  private val finding = ValidationGateFinding("m", "t", "still broken", "loc")

  @Test
  fun `BUILD_ONLY dirty then green uses only build_only_command and loops past three repairs`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val runner = ScriptedGateRunner(
      List(4) { failedWith(finding) } + listOf(passed(), passed(forced = true)),
    )
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = buildOnlyCycle { _, _ ->
        repairLaunches.incrementAndGet()
        ValidationGateAgentRepairResult.Completed(
          FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
        )
      },
    )
    assertEquals(4, repairLaunches.get())
    assertEquals(6, runner.calls)
    assertTrue(runner.requests.all { it.argv.first() == "echo" && it.argv.contains("build-only") })
    assertTrue(runner.requests.none { "collect-all" in it.argv })
    assertTrue(runner.requests.all { it.findingParseMode == ValidationGateFindingParseMode.ARTIFACTS_ONLY })
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(progress.last().remainingFindings.isEmpty())
  }

  @Test
  fun `each repair turn is launched under its own ordinal so turns never share an evidence key`() {
    val ordinals = mutableListOf<Int>()
    val runner = ScriptedGateRunner(
      List(4) { failedWith(finding) } + listOf(passed(), passed(forced = true)),
    )
    coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = buildOnlyCycle { _, repairIteration ->
        ordinals += repairIteration
        ValidationGateAgentRepairResult.Completed(
          FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
        )
      },
    )
    assertEquals((1..4).toList(), ordinals)
  }

  @Test
  fun `BUILD_ONLY completed repair with empty produced_outputs skips FULL coverage`() {
    val runner = ScriptedGateRunner(listOf(failedWith(finding), passed(), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = buildOnlyCycle { _, _ ->
        ValidationGateAgentRepairResult.Completed(
          FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
        )
      },
    )
    assertEquals(3, runner.calls)
    assertEquals(ValidationGateCacheMode.CACHE_ELIGIBLE, runner.requests[0].cacheMode)
    assertEquals(ValidationGateCacheMode.CACHE_ELIGIBLE, runner.requests[1].cacheMode)
    assertEquals(ValidationGateCacheMode.FORCED_FULL, runner.requests[2].cacheMode)
    assertTrue(runner.requests.all { it.argv.contains("build-only") })
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  private fun buildOnlyCycle(repair: ValidationGateAgentRepairLauncher): ValidationGateCycleRequest =
    ValidationGateCycleRequest(
      repoRoot = validationGateTestRepoRoot,
      request = minimalRequest(),
      validationDepth = ValidationDepth.BUILD_ONLY,
      changedPaths = listOf("runtime-kotlin/foo.kt"),
      repositoryCheckpoint = "checkpoint",
      agentRepairLauncher = repair,
    )
}
