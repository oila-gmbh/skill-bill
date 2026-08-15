package skillbill.application.featuretask.validation

import skillbill.application.RecordingDiagnostics
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateSelectionTest {
  @Test
  fun `BUILD_ONLY selects build_only_command argv`() {
    assertEquals(
      listOf("echo", "build-only"),
      validationGateArgv(
        validationGateTestDeclaration,
        ValidationDepth.BUILD_ONLY,
        ValidationGateCacheMode.CACHE_ELIGIBLE,
      ),
    )
  }

  @Test
  fun `BUILD_ONLY terminal verifying keeps build-only argv and appends cache-bypass extras`() {
    val gradleGate = validationGateTestDeclaration.copy(
      fullGateCommand = listOf("./gradlew", "check"),
      cacheBypassingFullGateCommand = listOf("./gradlew", "check", "--rerun-tasks", "--no-build-cache"),
      buildOnlyCommand = listOf("./gradlew", "classes", "testClasses"),
    )
    assertEquals(
      listOf("./gradlew", "classes", "testClasses", "--rerun-tasks", "--no-build-cache"),
      validationGateArgv(gradleGate, ValidationDepth.BUILD_ONLY, ValidationGateCacheMode.FORCED_FULL),
    )
  }

  @Test
  fun `FULL cache modes select pack collect-all argv and never full_gate_command`() {
    val cacheEligible = validationGateArgv(
      validationGateTestDeclaration,
      ValidationDepth.FULL,
      ValidationGateCacheMode.CACHE_ELIGIBLE,
    )
    val forcedFull = validationGateArgv(
      validationGateTestDeclaration,
      ValidationDepth.FULL,
      ValidationGateCacheMode.FORCED_FULL,
    )
    assertEquals(listOf("echo", "collect-all"), cacheEligible)
    assertEquals(listOf("echo", "collect-all-full"), forcedFull)
    assertTrue(cacheEligible != validationGateTestDeclaration.fullGateCommand)
    assertTrue(forcedFull != validationGateTestDeclaration.fullGateCommand)
    assertTrue(forcedFull != validationGateTestDeclaration.cacheBypassingFullGateCommand)
  }

  @Test
  fun `collect-all argv helper selects pack collect-all commands`() {
    assertEquals(
      listOf("echo", "collect-all"),
      validationGateCollectAllArgv(validationGateTestDeclaration, ValidationGateCacheMode.CACHE_ELIGIBLE),
    )
    assertEquals(
      listOf("echo", "collect-all-full"),
      validationGateCollectAllArgv(validationGateTestDeclaration, ValidationGateCacheMode.FORCED_FULL),
    )
  }

  @Test
  fun `repo-local gradle_wrapper rewrites pack gradlew argv before the gate runs`() {
    val captured = mutableListOf<List<String>>()
    val gradleGate = validationGateTestDeclaration.copy(
      fullGateCommand = listOf("./gradlew", "check"),
      collectAllFullGateCommand = listOf("./gradlew", "check", "--continue"),
      cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "check", "--continue", "--rerun-tasks"),
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
      listOf(
        listOf("runtime-kotlin/gradlew", "-p", "runtime-kotlin", "check", "--continue"),
        listOf("runtime-kotlin/gradlew", "-p", "runtime-kotlin", "check", "--continue", "--rerun-tasks"),
      ),
      captured,
    )
  }

  @Test
  fun `truncated projection reports dropped count and blocks success semantics`() {
    val findings = (1..100).map { index ->
      ValidationGateFinding("m$index", "t$index", "message-$index", "loc-$index")
    }
    val projection = ValidationFindingSetProjector.project(
      findings,
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 256, maxCollectionItems = 2),
    )
    assertTrue(projection.droppedCount > 0)
    assertTrue(projection.hasUnreportedRemainder)
  }

  @Test
  fun `zero work terminal outcome is rejected`() {
    val runner = object : ValidationGateRunner {
      override fun run(request: ValidationGateRunRequest): ValidationGateRunResult = ValidationGateRunResult(
        exitCode = 0,
        durationMs = 3,
        outcome = ValidationGateRunOutcome.REJECTED_ZERO_WORK,
        cacheMode = request.cacheMode,
        executedWorkUnits = 0,
        findings = emptyList(),
      )
    }
    val result = runner.run(
      ValidationGateRunRequest(
        repoRoot = validationGateTestRepoRoot,
        argv = listOf("true"),
        cacheMode = ValidationGateCacheMode.FORCED_FULL,
        declaration = validationGateTestDeclaration,
        terminalVerifying = true,
      ),
    )
    assertEquals(ValidationGateRunOutcome.REJECTED_ZERO_WORK, result.outcome)
    assertEquals(0, result.executedWorkUnits)
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
    val runner = ScriptedGateRunner(listOf(passed(), passed(forced = true)))
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
    assertEquals(2, runner.calls)
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
