package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateProgressStore
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.ports.scaffold.ScaffoldCatalogGateway
import skillbill.ports.scaffold.model.PilotedPlatformPackProjection
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.BaselineReviewCatalog
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
    buildOnlyCommand = listOf("echo", "build-only"),
    findings = ValidationGateFindingsLocator(
      format = ValidationGateFindingsFormat.JUNIT_XML,
      artifactGlobs = listOf("**/*.xml"),
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
  fun `terminal verifying selects cache bypass argv`() {
    assertEquals(
      listOf("echo", "full"),
      validationGateArgv(gateDeclaration, ValidationDepth.FULL, ValidationGateCacheMode.FORCED_FULL),
    )
  }

  @Test
  fun `intermediate repair runs stay on cache-eligible argv`() {
    assertEquals(
      listOf("echo", "cache"),
      validationGateArgv(gateDeclaration, ValidationDepth.FULL, ValidationGateCacheMode.CACHE_ELIGIBLE),
    )
  }

  @Test
  fun `repo-local gradle_wrapper rewrites pack gradlew argv before the gate runs`() {
    val captured = mutableListOf<List<String>>()
    val gradleGate = gateDeclaration.copy(
      fullGateCommand = listOf("./gradlew", "check"),
      cacheBypassingFullGateCommand = listOf("./gradlew", "check", "--rerun-tasks"),
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
        listOf("runtime-kotlin/gradlew", "check"),
        listOf("runtime-kotlin/gradlew", "check", "--rerun-tasks"),
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
  fun `repair cycle cap is distinct and explicit`() {
    assertEquals(3, MAX_VALIDATE_GATE_REPAIR_ITERATIONS)
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
    val resolver = ValidationGateResolver(
      ScaffoldCatalogService(
        object : ScaffoldCatalogGateway {
          override fun approvedCodeReviewAreas() = emptySet<String>()
          override fun preShellFamilies() = emptySet<String>()
          override fun shelledFamilies() = emptySet<String>()
          override fun platformPackPresets() = emptyMap<String, String>()
          override fun scaffoldPayloadVersion() = "test"
          override fun discoverPilotedPlatformPacks(packsRoot: Path): List<PilotedPlatformPackProjection> = emptyList()
          override fun discoverPlatformManifests(packsRoot: Path) = listOf(kotlinPackWithoutGate())
          override fun discoverBaselineReviewCatalog(packsRoot: Path) = BaselineReviewCatalog(emptyList(), emptyList())
        },
      ),
    )
    val resolution = resolver.resolve(repoRoot, listOf("runtime-kotlin/foo.kt"))
    assertTrue(resolution is ValidationGateResolution.Absent)
  }

  @Test
  fun `FAILED gate with empty findings launches repair with synthetic finding`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val runner = ScriptedGateRunner(
      listOf(
        failedEmptyFindings(),
        passed(),
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
  fun `gate is re-run after every repair and exhaust persists remaining findings`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val finding = ValidationGateFinding("m", "t", "still broken", "loc")
    val runner = ScriptedGateRunner(
      List(MAX_VALIDATE_GATE_REPAIR_ITERATIONS + 1) { failedWith(finding) },
    )
    val cycle = coordinator(declaredResolver(), runner, progress).execute(
      cycle = ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = ValidationDepth.DEFAULT,
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
    assertEquals(MAX_VALIDATE_GATE_REPAIR_ITERATIONS, repairLaunches.get())
    // One gate run before each repair + one verifying gate after the final repair before exhaust.
    assertEquals(MAX_VALIDATE_GATE_REPAIR_ITERATIONS + 1, runner.calls)
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(blocked.reason.contains("exhausted"))
    assertEquals(listOf(finding), blocked.remainingFindings?.findings)
    assertTrue(progress.last().remainingFindings.isNotEmpty())
    assertEquals("t", progress.last().remainingFindings.single()["rule_or_test_id"])
  }

  @Test
  fun `successful final repair is verified by a post-repair gate run before completion`() {
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val repairLaunches = AtomicInteger(0)
    val finding = ValidationGateFinding("m", "t", "broken", "loc")
    val runner = ScriptedGateRunner(
      listOf(
        failedWith(finding),
        failedWith(finding),
        failedWith(finding),
        passed(),
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
        agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
          repairLaunches.incrementAndGet()
          ValidationGateAgentRepairResult.Completed(
            FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = "{}"),
          )
        },
      ),
    )
    assertEquals(MAX_VALIDATE_GATE_REPAIR_ITERATIONS, repairLaunches.get())
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(progress.last().remainingFindings.isEmpty())
  }

  private fun coordinator(
    resolver: ValidationGateResolver,
    runner: ValidationGateRunner,
    progress: MutableList<FeatureTaskRuntimeValidationGateProgress>,
    gradleWrapper: String? = null,
  ): FeatureTaskRuntimeValidationGateCoordinator = FeatureTaskRuntimeValidationGateCoordinator(
    resolver,
    runner,
    ValidationGateProgressStore { _, p, _ -> progress += p },
    FeatureTaskRuntimeSuppressionDeltaService(
      object : skillbill.ports.workflow.WorkflowGitOperations by skillbill.ports.workflow.NoopWorkflowGitOperations {},
    ),
    repoLocalConfig(gradleWrapper),
  )

  private fun repoLocalConfig(
    gradleWrapper: String? = null,
  ): skillbill.ports.config.RepoLocalConfigPort =
    object : skillbill.ports.config.RepoLocalConfigPort {
      override fun readRepoLocalConfig(
        request: skillbill.ports.config.model.ReadRepoLocalConfigRequest,
      ) = skillbill.ports.config.model.ReadRepoLocalConfigResult(
        skillbill.config.model.RepoLocalConfig.defaults().copy(
          validationGate = skillbill.config.model.ValidationGateRepoConfig(gradleWrapper = gradleWrapper),
        ),
      )
    }

  private fun declaredResolver(
    declaration: ValidationGateDeclaration = gateDeclaration,
  ): ValidationGateResolver = ValidationGateResolver(
    ScaffoldCatalogService(
      object : ScaffoldCatalogGateway {
        override fun approvedCodeReviewAreas() = emptySet<String>()
        override fun preShellFamilies() = emptySet<String>()
        override fun shelledFamilies() = emptySet<String>()
        override fun platformPackPresets() = emptyMap<String, String>()
        override fun scaffoldPayloadVersion() = "test"
        override fun discoverPilotedPlatformPacks(packsRoot: Path): List<PilotedPlatformPackProjection> = emptyList()
        override fun discoverPlatformManifests(packsRoot: Path) = listOf(
          kotlinPackWithoutGate().copy(validationGate = declaration),
        )
        override fun discoverBaselineReviewCatalog(packsRoot: Path) = BaselineReviewCatalog(emptyList(), emptyList())
      },
    ),
  )

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

  private fun failedWith(finding: ValidationGateFinding): ValidationGateRunResult = ValidationGateRunResult(
    exitCode = 1,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.FAILED,
    cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    findings = listOf(finding),
  )

  private fun kotlinPackWithoutGate(): PlatformManifest = PlatformManifest(
    slug = "kotlin",
    packRoot = repoRoot.resolve("platform-packs/kotlin"),
    contractVersion = "1.4",
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

  private fun kotlinPackWithGate(): PlatformManifest = kotlinPackWithoutGate().copy(
    validationGate = gateDeclaration,
  )

  private class ScriptedGateRunner(
    private val results: List<ValidationGateRunResult>,
  ) : ValidationGateRunner {
    var calls: Int = 0
      private set

    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
      val index = calls
      calls++
      return results.getOrElse(index) {
        error("ScriptedGateRunner exhausted after ${results.size} results; call=$index")
      }
    }
  }
}
