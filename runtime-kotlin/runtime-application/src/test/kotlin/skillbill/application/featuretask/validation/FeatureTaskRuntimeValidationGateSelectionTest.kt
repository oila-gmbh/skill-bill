package skillbill.application.featuretask.validation

import skillbill.application.RecordingDiagnostics
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCyclePhase
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateSelectionTest {
  @Test
  fun `cycle phase selects collect-all discovery or verify argv and never full_gate_command`() {
    assertEquals(
      listOf("echo", "collect-all"),
      validationGateArgv(
        validationGateTestDeclaration,
        ValidationGateCyclePhase.INITIAL_DISCOVERY,
      ),
    )
    assertEquals(
      listOf("echo", "collect-all-full"),
      validationGateArgv(
        validationGateTestDeclaration,
        ValidationGateCyclePhase.POST_REPAIR_VERIFY,
      ),
    )
    for (phase in ValidationGateCyclePhase.entries) {
      val argv = validationGateArgv(validationGateTestDeclaration, phase)
      assertTrue(argv != validationGateTestDeclaration.fullGateCommand)
    }
  }

  @Test
  fun `a clean gate run completes on one execution and rewrites the repo-local gradle wrapper`() {
    val captured = mutableListOf<List<String>>()
    val gradleGate = validationGateTestDeclaration.copy(
      fullGateCommand = listOf("./gradlew", "check"),
      collectAllFullGateCommand = listOf("./gradlew", "check", "--continue"),
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
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _, _ ->
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
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _, _ ->
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

  @Test
  fun `empty path routing still selects a pack that declares validation_gate`() {
    val resolver = ValidationGateResolver {
      listOf(
        kotlinPackWithoutGate(),
        kotlinPackWithoutGate().copy(
          slug = "kotlin-gated",
          validationGate = validationGateTestDeclaration,
        ),
      )
    }
    val declared = assertIs<ValidationGateResolution.Declared>(resolver.resolve(emptyList()))
    assertEquals("kotlin-gated", declared.packSlug)
  }

  @Test
  fun `gated stack pack wins over co-routed no-gate fallback despite catalog order`() {
    // Mirrors SKILL-18 subtask 3: .kt paths route to kotlin; an unmatched .txt falls back to
    // generic. Catalog order lists generic first — the old selector blocked build on that.
    val generic = reviewFallbackPackWithoutGate()
    val kotlin = kotlinPackWithoutGate().copy(
      routingSignals = skillbill.scaffold.model.RoutingSignals(
        strong = listOf(".kt"),
        tieBreakers = emptyList(),
        path = listOf(".kt"),
      ),
      validationGate = validationGateTestDeclaration,
    )
    val resolver = ValidationGateResolver { listOf(generic, kotlin) }
    val declared = assertIs<ValidationGateResolution.Declared>(
      resolver.resolve(
        listOf(
          "application/src/main/kotlin/dev/skillbill/Foo.kt",
          ".feature-specs/SKILL-18/subtask_3_commit_message.txt",
        ),
      ),
    )
    assertEquals("kotlin", declared.packSlug)
  }

  @Test
  fun `findings_open resume skips discovery and hands back the persisted open set`() {
    val findingOne = ValidationGateFinding("m1", "r1", "msg1", "loc1")
    val findingTwo = ValidationGateFinding("m2", "r2", "msg2", "loc2")
    val recorded = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val progressStore = RecordingProgressStore(
      recorded,
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 1,
        gateRuns = listOf(
          FeatureTaskRuntimeValidationGateRunRecord(
            durationMs = 1,
            outcome = "failed",
            cacheMode = "cache_eligible",
            executedWorkUnits = 1,
          ),
        ),
        completeFindings = listOf(findingRow(findingOne), findingRow(findingTwo)),
        repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
      ),
    )
    val repairSizes = mutableListOf<Int>()
    val runner = ScriptedGateRunner(listOf(passed(forced = true)))
    coordinator(declaredResolver(), runner, progressStore).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.FULL,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _, _ ->
          repairSizes += findings.findings.size
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
    )
    assertEquals(listOf(2), repairSizes)
    assertEquals(1, runner.calls)
    assertEquals(listOf("echo", "collect-all-full"), runner.requests.single().argv)
  }

  @Test
  fun `operator resume after exhausted repair turns starts a new repair window`() {
    val maxTurns = FeatureTaskRuntimeValidationGateCoordinator.MAX_REPAIR_TURNS
    val finding = ValidationGateFinding("m", "t", "still broken", "loc")
    val recorded = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val progressStore = RecordingProgressStore(
      recorded,
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = maxTurns,
        gateRuns = List(maxTurns) {
          FeatureTaskRuntimeValidationGateRunRecord(
            durationMs = 1,
            outcome = "failed",
            cacheMode = "forced_full",
            executedWorkUnits = 1,
          )
        },
        completeFindings = listOf(findingRow(finding)),
        repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
        repairsUsed = maxTurns,
      ),
    )
    val repairSizes = mutableListOf<Int>()
    val runner = ScriptedGateRunner(listOf(passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, progressStore).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = validationGateTestRepoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.FULL,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _, _ ->
          repairSizes += findings.findings.size
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
    )
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertEquals(listOf(1), repairSizes)
    assertEquals(1, runner.calls)
    assertEquals(listOf("echo", "collect-all-full"), runner.requests.single().argv)
    assertEquals(1, recorded.last().repairsUsed)
  }
}
