package skillbill.application.featuretask.validation

import skillbill.application.RecordingDiagnostics
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateProgressStore
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.error.ContractVersionMismatchError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationGateTest {
  private val repoRoot: Path = Path.of(".").toAbsolutePath().normalize()

  private val gateDeclaration = ValidationGateDeclaration(
    fullGateCommand = listOf("echo", "cache"),
    cacheBypassingFullGateCommand = listOf("echo", "full"),
    collectAllFullGateCommand = listOf("echo", "collect-all"),
    cacheBypassingCollectAllFullGateCommand = listOf("echo", "collect-all-full"),
    buildOnlyCommand = listOf("echo", "build-only"),
    findings = ValidationGateFindingsLocator(
      format = ValidationGateFindingsFormat.JUNIT_XML,
      artifactGlobs = listOf("**/*.xml"),
      compilerDiagnostics = skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator(
        skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT,
      ),
      executedWork = ValidationGateExecutedWorkSignal(ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY),
    ),
  )

  @Test
  fun `BUILD_ONLY selects build_only_command argv`() {
    assertEquals(
      listOf("echo", "build-only"),
      validationGateArgv(gateDeclaration, ValidationDepth.BUILD_ONLY, ValidationGateCacheMode.CACHE_ELIGIBLE),
    )
  }

  @Test
  fun `BUILD_ONLY terminal verifying keeps build-only argv and appends cache-bypass extras`() {
    val gradleGate = gateDeclaration.copy(
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
      gateDeclaration,
      ValidationDepth.FULL,
      ValidationGateCacheMode.CACHE_ELIGIBLE,
    )
    val forcedFull = validationGateArgv(
      gateDeclaration,
      ValidationDepth.FULL,
      ValidationGateCacheMode.FORCED_FULL,
    )
    assertEquals(listOf("echo", "collect-all"), cacheEligible)
    assertEquals(listOf("echo", "collect-all-full"), forcedFull)
    assertTrue(cacheEligible != gateDeclaration.fullGateCommand)
    assertTrue(forcedFull != gateDeclaration.fullGateCommand)
    assertTrue(forcedFull != gateDeclaration.cacheBypassingFullGateCommand)
  }

  @Test
  fun `collect-all argv helper selects pack collect-all commands`() {
    assertEquals(
      listOf("echo", "collect-all"),
      validationGateCollectAllArgv(gateDeclaration, ValidationGateCacheMode.CACHE_ELIGIBLE),
    )
    assertEquals(
      listOf("echo", "collect-all-full"),
      validationGateCollectAllArgv(gateDeclaration, ValidationGateCacheMode.FORCED_FULL),
    )
  }

  @Test
  fun `repo-local gradle_wrapper rewrites pack gradlew argv before the gate runs`() {
    val captured = mutableListOf<List<String>>()
    val gradleGate = gateDeclaration.copy(
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
        repoRoot = repoRoot,
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
        repoRoot = repoRoot,
        argv = listOf("true"),
        cacheMode = ValidationGateCacheMode.FORCED_FULL,
        declaration = gateDeclaration,
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
    // Realistic bug: progressStore isolation exists, but ValidationGateProgress emit escapes and
    // aborts an otherwise passing gate cycle when a status/telemetry observer throws; or the
    // isolation swallows the fault with no payload-free diagnostic record (observability-policy).
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val diagnostics = RecordingDiagnostics()
    val throwingSink = FeatureTaskRuntimeRunEventSink {
      error("status/telemetry observer refused ValidationGateProgress")
    }
    val runner = ScriptedGateRunner(listOf(passed(), passed(forced = true)))
    val cycle = coordinator(declaredResolver(), runner, progress, diagnostics = diagnostics).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
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
    // Only the packs the user installed are offered; a pack that ships but was not selected is
    // simply absent from the catalog, so routing can never reach its gate.
    val selected = ValidationGateResolver { listOf(kotlinPackWithoutGate().copy(validationGate = gateDeclaration)) }
    val declared = assertIs<ValidationGateResolution.Declared>(selected.resolve(listOf("runtime-kotlin/foo.kt")))
    assertEquals("kotlin", declared.packSlug)

    val notSelected = ValidationGateResolver { emptyList() }
    assertIs<ValidationGateResolution.Absent>(notSelected.resolve(listOf("runtime-kotlin/foo.kt")))
  }

  @Test
  fun `FAILED gate with empty findings launches repair with synthetic finding`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val runner = ScriptedGateRunner(
      listOf(
        failedEmptyFindings(),
        passed(forced = true),
      ),
    )
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _ ->
          repairLaunches.incrementAndGet()
          assertEquals(1, findings.findings.size)
          assertEquals("unparseable_gate_failure", findings.findings.single().ruleOrTestId)
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
    )
    assertEquals(1, repairLaunches.get())
    assertIs<ValidationGateCycleResult.Terminal>(cycle)
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(cycle.outcome)
  }

  @Test
  fun `FULL dirty discovery repair and green confirmation records two collect-all runs`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val compiler = ValidationGateFinding("app", "e: Unresolved reference", "compile error", "Foo.kt")
    val laterTest = ValidationGateFinding("later-module", "LaterTest.fails", "assertion failed", "LaterTest.kt")
    val firstRepairIds = mutableListOf<String>()
    val runner = ScriptedGateRunner(
      listOf(
        failedWith(compiler, laterTest),
        passed(forced = true),
      ),
    )
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.FULL,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _ ->
          firstRepairIds += findings.findings.map { it.ruleOrTestId }
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
    )
    assertEquals(listOf("e: Unresolved reference", "LaterTest.fails"), firstRepairIds)
    assertEquals(2, runner.calls)
    assertEquals(
      listOf("echo", "collect-all"),
      runner.requests[0].argv,
    )
    assertEquals(ValidationGateCacheMode.CACHE_ELIGIBLE, runner.requests[0].cacheMode)
    assertEquals(ValidationGateFindingParseMode.COLLECT_ALL, runner.requests[0].findingParseMode)
    assertEquals(
      listOf("echo", "collect-all-full"),
      runner.requests[1].argv,
    )
    assertEquals(ValidationGateCacheMode.FORCED_FULL, runner.requests[1].cacheMode)
    assertEquals(ValidationGateFindingParseMode.COLLECT_ALL, runner.requests[1].findingParseMode)
    assertTrue(runner.requests.none { it.argv == gateDeclaration.fullGateCommand })
    assertTrue(runner.requests.none { it.argv == gateDeclaration.cacheBypassingFullGateCommand })
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
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.FULL,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
    )
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(blocked.reason.contains("zero executed work"))
  }

  @Test
  fun `BUILD_ONLY dirty then green uses only build_only_command and loops past three repairs`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val finding = ValidationGateFinding("m", "t", "still broken", "loc")
    val runner = ScriptedGateRunner(
      List(4) { failedWith(finding) } + listOf(passed(), passed(forced = true)),
    )
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.BUILD_ONLY,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
          repairLaunches.incrementAndGet()
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
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
    val finding = ValidationGateFinding("m", "t", "still broken", "loc")
    val ordinals = mutableListOf<Int>()
    val runner = ScriptedGateRunner(
      List(4) { failedWith(finding) } + listOf(passed(), passed(forced = true)),
    )

    coordinator(declaredResolver(), runner, mutableListOf()).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.BUILD_ONLY,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, repairIteration ->
          ordinals += repairIteration
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
    )

    assertEquals((1..4).toList(), ordinals)
  }

  @Test
  fun `FULL 65-finding discovery pages two repairs then one confirmation without rerunning the gate`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val findings = (1..65).map { index ->
      ValidationGateFinding("m$index", "t$index", "message-$index", "loc-$index")
    }
    val pageSizes = mutableListOf<Int>()
    val ordinals = mutableListOf<Int>()
    val runner = ScriptedGateRunner(
      listOf(
        failedWith(*findings.toTypedArray()),
        passed(forced = true),
      ),
    )
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.FULL,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { page, repairIteration ->
          assertEquals(1, runner.calls)
          pageSizes += page.findings.size
          ordinals += repairIteration
          assertEquals(0, page.droppedCount)
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
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
  fun `fromArtifactMap decodes persistence 0_2 progress omitting additive finding fields`() {
    val decoded = FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(
      mapOf(
        "contract_version" to skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
        "gate_run_count" to 1,
        "gate_runs" to listOf(
          mapOf(
            "duration_ms" to 1L,
            "outcome" to "passed",
            "cache_mode" to "cache_eligible",
            "executed_work_units" to 1,
          ),
        ),
        "remaining_findings" to emptyList<Map<String, String?>>(),
        "remaining_findings_dropped_count" to 0,
      ),
    )
    assertEquals(emptyList(), decoded.completeFindings)
    assertEquals(0, decoded.findingsPageOffset)
    assertEquals(0, decoded.confirmationRetriesUsed)
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
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.FULL,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, _ ->
          repairIds += findings.findings.map { it.ruleOrTestId }
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
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
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.FULL,
        changedPaths = listOf("runtime-kotlin/foo.kt"),
        repositoryCheckpoint = "checkpoint",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
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

  private fun outOfContractResolver(): ValidationGateResolver = ValidationGateResolver {
    throw ContractVersionMismatchError(
      "Platform pack 'fallback': declares contract_version '0.1' but the shell expects '1.5'.",
    )
  }

  private fun neverRunsGate(): ValidationGateRunner = object : ValidationGateRunner {
    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult =
      error("validation gate must not launch when platform packs are out of contract")
  }

  private fun outOfContractCycle(): ValidationGateCycleRequest = ValidationGateCycleRequest(
    repoRoot = repoRoot,
    request = minimalRequest(),
    validationDepth = ValidationDepth.DEFAULT,
    changedPaths = listOf("runtime-kotlin/foo.kt"),
    repositoryCheckpoint = "checkpoint",
    agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
      error("repair must not launch when platform packs are out of contract")
    },
  )

  private fun coordinator(
    resolver: ValidationGateResolver,
    runner: ValidationGateRunner,
    progress: MutableList<FeatureTaskRuntimeValidationGateProgress>,
    gradleWrapper: String? = null,
    diagnostics: RuntimeDiagnostics = skillbill.ports.diagnostics.NoopRuntimeDiagnostics,
  ): FeatureTaskRuntimeValidationGateCoordinator = FeatureTaskRuntimeValidationGateCoordinator(
    resolver,
    runner,
    ValidationGateProgressStore { _, p, _ -> progress += p },
    FeatureTaskRuntimeSuppressionDeltaService(
      object : skillbill.ports.workflow.WorkflowGitOperations by skillbill.ports.workflow.NoopWorkflowGitOperations {},
    ),
    repoLocalConfig(gradleWrapper),
    diagnostics,
  )

  private fun repoLocalConfig(gradleWrapper: String? = null): skillbill.ports.config.RepoLocalConfigPort =
    object : skillbill.ports.config.RepoLocalConfigPort {
      override fun readRepoLocalConfig(request: skillbill.ports.config.model.ReadRepoLocalConfigRequest) =
        skillbill.ports.config.model.ReadRepoLocalConfigResult(
          skillbill.config.model.RepoLocalConfig.defaults().copy(
            validationGate = skillbill.config.model.ValidationGateRepoConfig(gradleWrapper = gradleWrapper),
          ),
        )
    }

  private fun declaredResolver(declaration: ValidationGateDeclaration = gateDeclaration): ValidationGateResolver =
    ValidationGateResolver { listOf(kotlinPackWithoutGate().copy(validationGate = declaration)) }

  private fun minimalRequest(): FeatureTaskRuntimeRunRequest = FeatureTaskRuntimeRunRequest(
    issueKey = "SKILL-180",
    workflowId = "wf-skill-180",
    sessionId = "session",
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = "spec.md",
      featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
      acceptanceCriteria = listOf("AC-001"),
      mandatesAndOverrides = emptyList(),
    ),
    invokedAgentId = "claude",
    repoRoot = repoRoot,
  )

  private fun passed(forced: Boolean = false): ValidationGateRunResult = ValidationGateRunResult(
    exitCode = 0,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.PASSED,
    cacheMode = if (forced) ValidationGateCacheMode.FORCED_FULL else ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    findings = emptyList(),
  )

  private fun failedEmptyFindings(): ValidationGateRunResult = ValidationGateRunResult(
    exitCode = 1,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.FAILED,
    cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    findings = emptyList(),
  )

  private fun failedWith(vararg findings: ValidationGateFinding): ValidationGateRunResult = ValidationGateRunResult(
    exitCode = 1,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.FAILED,
    cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    findings = findings.toList(),
  )

  private fun kotlinPackWithoutGate(): PlatformManifest = PlatformManifest(
    slug = "kotlin",
    packRoot = repoRoot.resolve("platform-packs/kotlin"),
    contractVersion = "1.5",
    routingSignals = RoutingSignals(
      strong = listOf("runtime-kotlin"),
      tieBreakers = emptyList(),
      path = listOf("runtime-kotlin"),
    ),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = DeclaredFiles(null, emptyMap()),
    areaMetadata = emptyMap(),
    validationGate = null,
  )

  private class ScriptedGateRunner(
    private val results: List<ValidationGateRunResult>,
  ) : ValidationGateRunner {
    var calls: Int = 0
      private set
    val requests: MutableList<ValidationGateRunRequest> = mutableListOf()

    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
      val index = calls
      calls++
      requests += request
      return results.getOrElse(index) {
        error("ScriptedGateRunner exhausted after ${results.size} results; call=$index")
      }
    }
  }
}
