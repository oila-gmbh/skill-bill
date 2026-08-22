package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskRuntimeBuildGateCoordinatorCycleTest {
  @Test
  fun `clean build gate settles after discovery and confirmation increments gate_run_count by two`() {
    val finding = ValidationGateFinding("m", "compile", "broken", "Foo.kt")
    val runner = ScriptedGateRunner(
      listOf(
        failedWith(finding),
        passed(forced = true),
      ),
    )
    val recorded = mutableListOf<skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress>()
    val progress = RecordingProgressStore(recorded, null)
    var repairLaunches = 0
    val cycle = buildCoordinator(declaredResolver(declarationWithBuild()), runner, progress).execute(
      ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = skillbill.workflow.model.ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
          repairLaunches++
          ValidationGateAgentRepairResult.Completed(
            skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("build", 1, "{}"),
          )
        },
      ),
    )
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertEquals(1, repairLaunches)
    assertEquals(2, runner.calls)
    assertEquals(2, recorded.last().gateRunCount)
    assertEquals(listOf("echo", "build"), runner.requests.first().argv)
    assertEquals(listOf("echo", "build-full"), runner.requests.last().argv)
  }

  @Test
  fun `build gate uses COLLECT_ALL parse mode so compiler diagnostics are captured`() {
    val runner = ScriptedGateRunner(listOf(passed()))
    val progress = RecordingProgressStore(mutableListOf(), null)
    buildCoordinator(declaredResolver(declarationWithBuild()), runner, progress).execute(
      ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = skillbill.workflow.model.ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
          error("repair must not launch on a clean discovery run")
        },
      ),
    )
    assertEquals(ValidationGateFindingParseMode.COLLECT_ALL, runner.requests.single().findingParseMode)
  }

  private fun declarationWithBuild() = validationGateTestDeclaration.copy(
    buildCommand = listOf("echo", "build"),
    cacheBypassingBuildCommand = listOf("echo", "build-full"),
  )

  private fun buildCoordinator(
    resolver: ValidationGateResolver,
    runner: ScriptedGateRunner,
    progressStore: RecordingProgressStore,
  ): FeatureTaskRuntimeBuildGateCoordinator = FeatureTaskRuntimeBuildGateCoordinator(
    resolver,
    runner,
    FeatureTaskRuntimeBuildGateProgressStore(
      persist = { _, progress, _ -> progressStore.persist("", progress, null) },
      load = { _, _ -> progressStore.load("", null) },
    ),
    repoLocalConfig(),
    skillbill.ports.diagnostics.NoopRuntimeDiagnostics,
  )
}
