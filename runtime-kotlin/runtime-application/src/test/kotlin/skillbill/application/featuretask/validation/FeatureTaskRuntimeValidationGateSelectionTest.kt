package skillbill.application.featuretask.validation

import skillbill.application.RecordingDiagnostics
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateSelectionTest {
  @Test
  fun `depth selects build-only or collect-all argv and never full_gate_command`() {
    assertEquals(
      listOf("echo", "build-only"),
      validationGateArgv(validationGateTestDeclaration, ValidationDepth.BUILD_ONLY),
    )
    val full = validationGateArgv(validationGateTestDeclaration, ValidationDepth.FULL)
    assertEquals(listOf("echo", "collect-all"), full)
    assertTrue(full != validationGateTestDeclaration.fullGateCommand)
  }

  @Test
  fun `a clean gate run completes on one execution and rewrites the repo-local gradle wrapper`() {
    val captured = mutableListOf<List<String>>()
    val gradleGate = validationGateTestDeclaration.copy(
      fullGateCommand = listOf("./gradlew", "check"),
      collectAllFullGateCommand = listOf("./gradlew", "check", "--continue"),
      buildOnlyCommand = listOf("./gradlew", "classes"),
    )
    val runner = object : ValidationGateRunner {
      override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
        captured += request.argv
        return ValidationGateRunResult(
          exitCode = 0,
          durationMs = 1,
          outcome = ValidationGateRunOutcome.PASSED,
          cacheMode = request.cacheMode,
          executedWorkUnits = 1,
          findings = emptyList(),
        )
      }
    }
    val cycle = coordinator(
      declaredResolver(gradleGate),
      runner,
      mutableListOf(),
      gradleWrapper = "runtime-kotlin/gradlew",
    ).execute(
      ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
          error("repair should not run for a clean gate")
        },
      ),
    )
    assertIs<ValidationGateCycleResult.Terminal>(cycle)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(cycle.outcome)
    assertEquals(
      listOf(listOf("runtime-kotlin/gradlew", "-p", "runtime-kotlin", "check", "--continue")),
      captured,
    )
  }

  @Test
  fun `absent gate resolution returns absent for pack without declaration`() {
    val resolver = ValidationGateResolver { listOf(kotlinPackWithoutGate()) }
    val resolution = resolver.resolve(listOf("runtime-kotlin/foo.kt"))
    assertTrue(resolution is ValidationGateResolution.Absent)
  }

  @Test
  fun `out of contract packs block terminally instead of degrading or crashing`() {
    val cycle = coordinator(outOfContractResolver(), neverRunsGate(), mutableListOf())
      .execute(cycle = outOfContractCycle())
    assertIs<ValidationGateCycleResult.Terminal>(cycle)
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(cycle.outcome)
    assertTrue(blocked.reason.contains("contract_version '0.1'"))
    assertTrue(blocked.reason.contains("Repair the installed platform packs"))
  }

  @Test
  fun `throwing progress event sink does not change the gate cycle outcome`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val diagnostics = RecordingDiagnostics()
    val throwingSink = FeatureTaskRuntimeRunEventSink {
      error("status/telemetry observer refused ValidationGateProgress")
    }
    val runner = ScriptedGateRunner(listOf(passed()))
    val cycle = coordinator(declaredResolver(), runner, progress, diagnostics = diagnostics).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest().copy(eventSink = throwingSink),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
          error("repair must not launch on a clean gate")
        },
      ),
    )
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(progress.isNotEmpty())
    assertEquals(1, runner.calls)
    assertTrue(
      diagnostics.warnings.any { it.contains("ValidationGateProgress event-sink emission failed") },
      "observer failure must leave an independent payload-free diagnostic record",
    )
  }

  @Test
  fun `gate declarations come from the installed pack selection`() {
    val selected = ValidationGateResolver {
      listOf(kotlinPackWithoutGate().copy(validationGate = validationGateTestDeclaration))
    }
    val declared = assertIs<ValidationGateResolution.Declared>(selected.resolve(listOf("runtime-kotlin/foo.kt")))
    assertEquals("kotlin", declared.packSlug)

    val notSelected = ValidationGateResolver { emptyList() }
    assertIs<ValidationGateResolution.Absent>(notSelected.resolve(listOf("runtime-kotlin/foo.kt")))
  }
}
