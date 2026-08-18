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
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateBuildOnlyTest {
  private val finding = ValidationGateFinding("m", "t", "still broken", "loc")

  @Test
  fun `BUILD_ONLY uses build_only discovery then cache-bypassing verify outside findings_open`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val runner = ScriptedGateRunner(List(4) { failedWith(finding) } + listOf(passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = buildOnlyCycle { _, _ ->
        repairLaunches.incrementAndGet()
        ValidationGateAgentRepairResult.Completed(
          FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
        )
      },
    )
    assertEquals(4, repairLaunches.get())
    assertEquals(5, runner.calls)
    assertEquals(listOf("echo", "build-only"), runner.requests[0].argv)
    assertEquals(ValidationGateCacheMode.CACHE_ELIGIBLE, runner.requests[0].cacheMode)
    assertTrue(runner.requests.drop(1).all { it.argv == listOf("echo", "full") })
    assertTrue(runner.requests.drop(1).all { it.cacheMode == ValidationGateCacheMode.FORCED_FULL })
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
    val runner = ScriptedGateRunner(List(4) { failedWith(finding) } + listOf(passed(forced = true)))
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
