package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateProgressStore
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.contracts.JsonSupport
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.SuppressionEvidenceGitOperations
import skillbill.ports.workflow.SuppressionEvidenceGitOperationsProvider
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowScopedPathContent
import skillbill.ports.workflow.model.WorkflowScopedPathContentsResult
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AC-013 regression matrix for the validate-boundary suppression gate.
 * Measurement is stubbed through the git evidence port; assertions stay on
 * coordinator terminal outcomes and durable validation_result shape.
 */
class FeatureTaskRuntimeSuppressionGateTest {
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
    ),
    suppressionMarkers = listOf("@Suppress"),
  )

  @Test
  fun `clean zero-delta pass omits suppression_justifications`() {
    val cycle = execute(
      SuppressionGateScenario(
        markers = listOf("@Suppress"),
        headContent = "fun ok() = 1\n",
        baseContent = "fun ok() = 1\n",
      ),
    )
    val completed = assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    val validationResult = validationResultMap(completed.output.payload)
    assertEquals("passed", validationResult["validation_status"])
    assertTrue("suppression_justifications" !in validationResult)
  }

  @Test
  fun `unjustified non-zero delta blocks naming path and marker`() {
    val cycle = execute(
      SuppressionGateScenario(
        markers = listOf("@Suppress"),
        headContent = "@Suppress(\"X\")\nfun ok() = 1\n",
        baseContent = "fun ok() = 1\n",
      ),
    )
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(blocked.reason.contains("src/Foo.kt"))
    assertTrue(blocked.reason.contains("@Suppress"))
  }

  @Test
  fun `first-pass fully accounted justification completes without prior gate failure`() {
    val launchCount = AtomicInteger(0)
    val cycle = execute(
      SuppressionGateScenario(
        markers = listOf("@Suppress"),
        headContent = "@Suppress(\"X\")\nfun ok() = 1\n",
        baseContent = "fun ok() = 1\n",
        options = SuppressionGateScenario.Options(
          repairJustifications = listOf(
            mapOf(
              "path" to "src/Foo.kt",
              "silenced_rule_or_check" to "X",
              "rationale" to "Third-party callback signature forces the silence.",
            ),
          ),
          onRepairLaunch = { findings, _ ->
            launchCount.incrementAndGet()
            assertTrue(
              findings.findings.all {
                it.ruleOrTestId ==
                  FeatureTaskRuntimeValidationGateCoordinator.SUPPRESSION_JUSTIFICATION_RULE_ID
              },
            )
          },
        ),
      ),
    )
    assertEquals(1, launchCount.get())
    val completed = assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    val validationResult = validationResultMap(completed.output.payload)
    val justifications = validationResult["suppression_justifications"] as List<*>
    assertEquals(1, justifications.size)
    val entry = justifications.single() as Map<*, *>
    assertEquals("src/Foo.kt", entry["path"])
    assertEquals("X", entry["silenced_rule_or_check"])
  }

  @Test
  fun `under-reported justification blocks`() {
    val cycle = execute(
      SuppressionGateScenario(
        markers = listOf("@Suppress"),
        headContent = "@Suppress(\"X\")\n@Suppress(\"Y\")\nfun ok() = 1\n",
        baseContent = "fun ok() = 1\n",
        options = SuppressionGateScenario.Options(
          repairJustifications = listOf(
            mapOf(
              "path" to "src/Foo.kt",
              "silenced_rule_or_check" to "X",
              "rationale" to "One silence only; second remains unaccounted.",
            ),
          ),
          forceRepairHarvest = true,
        ),
      ),
    )
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(blocked.reason.contains("under-reports"))
  }

  @Test
  fun `fully accounted justification completes and persists on validation_result`() {
    val cycle = execute(
      SuppressionGateScenario(
        markers = listOf("@Suppress"),
        headContent = "@Suppress(\"X\")\nfun ok() = 1\n",
        baseContent = "fun ok() = 1\n",
        options = SuppressionGateScenario.Options(
          repairJustifications = listOf(
            mapOf(
              "path" to "src/Foo.kt",
              "silenced_rule_or_check" to "X",
              "rationale" to "Third-party callback signature forces the silence.",
            ),
          ),
          forceRepairHarvest = true,
        ),
      ),
    )
    val completed = assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    val validationResult = validationResultMap(completed.output.payload)
    val justifications = validationResult["suppression_justifications"] as List<*>
    assertEquals(1, justifications.size)
    val entry = justifications.single() as Map<*, *>
    assertEquals("src/Foo.kt", entry["path"])
    assertEquals("X", entry["silenced_rule_or_check"])
  }

  @Test
  fun `no declared markers leaves the pack ungated`() {
    val cycle = execute(
      SuppressionGateScenario(
        markers = emptyList(),
        headContent = "@Suppress(\"X\")\nfun ok() = 1\n",
        baseContent = "fun ok() = 1\n",
      ),
    )
    assertIs<ValidationGateCycleTerminalOutcome.Completed>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
  }

  @Test
  fun `BUILD_ONLY still applies the suppression gate`() {
    val cycle = execute(
      SuppressionGateScenario(
        markers = listOf("@Suppress"),
        headContent = "@Suppress(\"X\")\nfun ok() = 1\n",
        baseContent = "fun ok() = 1\n",
        options = SuppressionGateScenario.Options(validationDepth = ValidationDepth.BUILD_ONLY),
      ),
    )
    val blocked = assertIs<ValidationGateCycleTerminalOutcome.Blocked>(
      assertIs<ValidationGateCycleResult.Terminal>(cycle).outcome,
    )
    assertTrue(blocked.reason.contains("@Suppress"))
  }

  private data class SuppressionGateScenario(
    val markers: List<String>,
    val headContent: String,
    val baseContent: String?,
    val options: Options = Options(),
  ) {
    data class Options(
      val repairJustifications: List<Map<String, String>> = emptyList(),
      val forceRepairHarvest: Boolean = false,
      val validationDepth: ValidationDepth = ValidationDepth.FULL,
      val onRepairLaunch: (ValidationFindingSetProjection, Int) -> Unit = { _, _ -> },
    )
  }

  private fun execute(scenario: SuppressionGateScenario): ValidationGateCycleResult {
    val declaration = gateDeclaration.copy(suppressionMarkers = scenario.markers)
    val progress = mutableListOf<FeatureTaskRuntimeValidationGateProgress>()
    val runner = if (scenario.options.forceRepairHarvest) {
      ScriptedGateRunner(listOf(failed(), passed(forced = true)))
    } else {
      ScriptedGateRunner(listOf(passed(), passed(forced = true)))
    }
    val coordinator = FeatureTaskRuntimeValidationGateCoordinator(
      resolver = declaredResolver(declaration),
      runner = runner,
      progressStore = ValidationGateProgressStore { _, p, _ -> progress += p },
      suppressionDeltaService = FeatureTaskRuntimeSuppressionDeltaService(suppressionEvidenceGit(scenario)),
      repoLocalConfig = defaultRepoLocalConfigPort(),
    )
    return coordinator.execute(
      ValidationGateCycleRequest(
        repoRoot = repoRoot,
        request = minimalRequest(),
        validationDepth = scenario.options.validationDepth,
        changedPaths = listOf("src/Foo.kt"),
        repositoryCheckpoint = "checkpoint",
        baseRef = "base",
        agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, iteration ->
          scenario.options.onRepairLaunch(findings, iteration)
          completedSuppressionRepair(findings, scenario)
        },
      ),
    )
  }

  private fun suppressionEvidenceGit(
    scenario: SuppressionGateScenario,
  ): skillbill.ports.workflow.WorkflowGitOperations =
    object : WorkflowGitOperations by NoopWorkflowGitOperations, SuppressionEvidenceGitOperationsProvider {
      override val suppressionEvidenceOperations = object : SuppressionEvidenceGitOperations {
        override fun scopedPathContentsAgainstBase(
          repoRoot: Path,
          baseRef: String,
          headPaths: List<String>,
        ): WorkflowScopedPathContentsResult = WorkflowScopedPathContentsResult(
          status = "ok",
          pairs = headPaths.map { path ->
            WorkflowScopedPathContent(
              headPath = path,
              basePath = path,
              headContent = scenario.headContent,
              baseContent = scenario.baseContent,
            )
          },
        )
      }
    }

  private fun completedSuppressionRepair(
    findings: ValidationFindingSetProjection,
    scenario: SuppressionGateScenario,
  ): ValidationGateAgentRepairResult {
    val justificationOnly = findings.findings.isNotEmpty() &&
      findings.findings.all {
        it.ruleOrTestId == FeatureTaskRuntimeValidationGateCoordinator.SUPPRESSION_JUSTIFICATION_RULE_ID
      }
    val produced = mutableMapOf<String, Any?>()
    if (!justificationOnly && findings.findings.isNotEmpty()) {
      produced["validation_repair_plan"] = findings.findings.map { finding ->
        mapOf("identities" to listOf(finding.identity()))
      }
      produced["substantiation_receipts"] = findings.findings.map { finding ->
        mapOf(
          "identity" to finding.identity(),
          "root_cause" to "root ${finding.ruleOrTestId}",
          "changed_paths_or_symbols" to listOf(finding.location ?: finding.ruleOrTestId),
          "rationale" to "fixed ${finding.ruleOrTestId}",
        )
      }
    }
    if (scenario.options.repairJustifications.isNotEmpty()) {
      produced["validation_result"] = mapOf(
        "suppression_justifications" to scenario.options.repairJustifications,
      )
    }
    val payload = JsonSupport.mapToJsonString(
      mapOf(
        "contract_version" to "0.3",
        "phase_id" to "validate",
        "status" to "completed",
        "summary" to "repair",
        "produced_outputs" to produced,
      ),
    )
    return ValidationGateAgentRepairResult.Completed(
      FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = payload),
    )
  }

  private fun declaredResolver(declaration: ValidationGateDeclaration): ValidationGateResolver =
    ValidationGateResolver {
      listOf(
        PlatformManifest(
          slug = "kotlin",
          packRoot = repoRoot.resolve("platform-packs/kotlin"),
          contractVersion = "1.5",
          routingSignals = RoutingSignals(strong = listOf("src"), tieBreakers = emptyList(), path = listOf("src")),
          declaredCodeReviewAreas = emptyList(),
          declaredFiles = DeclaredFiles(null, emptyMap()),
          areaMetadata = emptyMap(),
          validationGate = declaration,
        ),
      )
    }

  private fun defaultRepoLocalConfigPort(): skillbill.ports.config.RepoLocalConfigPort =
    object : skillbill.ports.config.RepoLocalConfigPort {
      override fun readRepoLocalConfig(request: skillbill.ports.config.model.ReadRepoLocalConfigRequest) =
        skillbill.ports.config.model.ReadRepoLocalConfigResult(skillbill.config.model.RepoLocalConfig.defaults())
    }

  private fun minimalRequest(): FeatureTaskRuntimeRunRequest = FeatureTaskRuntimeRunRequest(
    issueKey = "SKILL-180",
    workflowId = "wf-skill-180-suppression",
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

  private fun validationResultMap(payload: String): Map<String, Any?> {
    val envelope = JsonSupport.anyToStringAnyMap(
      JsonSupport.jsonElementToValue(requireNotNull(JsonSupport.parseObjectOrNull(payload))),
    ).orEmpty()
    return JsonSupport.anyToStringAnyMap(
      JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]).orEmpty()["validation_result"],
    ).orEmpty()
  }

  private fun passed(forced: Boolean = false): ValidationGateRunResult = ValidationGateRunResult(
    exitCode = 0,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.PASSED,
    cacheMode = if (forced) ValidationGateCacheMode.FORCED_FULL else ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    findings = emptyList(),
  )

  private fun failed(): ValidationGateRunResult = ValidationGateRunResult(
    exitCode = 1,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.FAILED,
    cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    findings = listOf(ValidationGateFinding("m", "t", "broken", "loc")),
  )

  private class ScriptedGateRunner(
    private val results: List<ValidationGateRunResult>,
  ) : ValidationGateRunner {
    private val calls = AtomicInteger(0)

    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
      val index = calls.getAndIncrement()
      return results.getOrElse(index) {
        error("ScriptedGateRunner exhausted after ${results.size} results; call=$index")
      }
    }
  }
}
