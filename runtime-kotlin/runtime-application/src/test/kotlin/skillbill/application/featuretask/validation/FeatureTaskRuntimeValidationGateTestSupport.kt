package skillbill.application.featuretask.validation

import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateProgressStore
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.contracts.JsonSupport
import skillbill.error.ContractVersionMismatchError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode
import skillbill.ports.validation.model.ValidationGateFinding
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
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress
import java.nio.file.Path

internal val validationGateTestRepoRoot: Path = Path.of(".").toAbsolutePath().normalize()

internal val validationGateTestDeclaration: ValidationGateDeclaration = ValidationGateDeclaration(
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

internal fun outOfContractResolver(): ValidationGateResolver = ValidationGateResolver {
  throw ContractVersionMismatchError(
    "Platform pack 'fallback': declares contract_version '0.1' but the shell expects '1.5'.",
  )
}

internal fun neverRunsGate(): ValidationGateRunner = object : ValidationGateRunner {
  override fun run(request: ValidationGateRunRequest): ValidationGateRunResult =
    error("validation gate must not launch when platform packs are out of contract")
}

internal fun outOfContractCycle(): ValidationGateCycleRequest = ValidationGateCycleRequest(
  repoRoot = validationGateTestRepoRoot,
  request = minimalRequest(),
  validationDepth = ValidationDepth.DEFAULT,
  changedPaths = listOf("runtime-kotlin/foo.kt"),
  repositoryCheckpoint = "checkpoint",
  agentRepairLauncher = ValidationGateAgentRepairLauncher { _, _ ->
    error("repair must not launch when platform packs are out of contract")
  },
)

internal fun coordinator(
  resolver: ValidationGateResolver,
  runner: ValidationGateRunner,
  progress: MutableList<FeatureTaskRuntimeValidationGateProgress>,
  gradleWrapper: String? = null,
  diagnostics: RuntimeDiagnostics = skillbill.ports.diagnostics.NoopRuntimeDiagnostics,
): FeatureTaskRuntimeValidationGateCoordinator = FeatureTaskRuntimeValidationGateCoordinator(
  resolver,
  runner,
  ValidationGateProgressStore { _, p, _ -> progress += p },
  repoLocalConfig(gradleWrapper),
  diagnostics,
)

internal fun repoLocalConfig(gradleWrapper: String? = null): skillbill.ports.config.RepoLocalConfigPort =
  object : skillbill.ports.config.RepoLocalConfigPort {
    override fun readRepoLocalConfig(request: skillbill.ports.config.model.ReadRepoLocalConfigRequest) =
      skillbill.ports.config.model.ReadRepoLocalConfigResult(
        skillbill.config.model.RepoLocalConfig.defaults().copy(
          validationGate = skillbill.config.model.ValidationGateRepoConfig(gradleWrapper = gradleWrapper),
        ),
      )
  }

internal fun declaredResolver(
  declaration: ValidationGateDeclaration = validationGateTestDeclaration,
): ValidationGateResolver =
  ValidationGateResolver { listOf(kotlinPackWithoutGate().copy(validationGate = declaration)) }

internal fun minimalRequest(): FeatureTaskRuntimeRunRequest = FeatureTaskRuntimeRunRequest(
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
  repoRoot = validationGateTestRepoRoot,
)

internal fun passed(forced: Boolean = false): ValidationGateRunResult = ValidationGateRunResult(
  exitCode = 0,
  durationMs = 1,
  outcome = ValidationGateRunOutcome.PASSED,
  cacheMode = if (forced) ValidationGateCacheMode.FORCED_FULL else ValidationGateCacheMode.CACHE_ELIGIBLE,
  executedWorkUnits = 1,
  findings = emptyList(),
)

internal fun failedEmptyFindings(): ValidationGateRunResult = ValidationGateRunResult(
  exitCode = 1,
  durationMs = 1,
  outcome = ValidationGateRunOutcome.FAILED,
  cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
  executedWorkUnits = 1,
  findings = emptyList(),
)

internal fun failedWith(vararg findings: ValidationGateFinding): ValidationGateRunResult = ValidationGateRunResult(
  exitCode = 1,
  durationMs = 1,
  outcome = ValidationGateRunOutcome.FAILED,
  cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
  executedWorkUnits = 1,
  findings = findings.toList(),
)

internal fun completedRepair(
  findings: List<ValidationGateFinding>,
  receiptsFor: List<ValidationGateFinding> = findings,
  grouped: Boolean = false,
): ValidationGateAgentRepairResult {
  val plan = if (grouped) {
    listOf(mapOf("identities" to findings.map { it.identity() }))
  } else {
    findings.map { mapOf("identities" to listOf(it.identity())) }
  }
  val receipts = receiptsFor.map { finding ->
    mapOf(
      "identity" to finding.identity(),
      "root_cause" to "root ${finding.ruleOrTestId}",
      "changed_paths_or_symbols" to listOf(finding.location ?: finding.ruleOrTestId),
      "rationale" to "fixed ${finding.ruleOrTestId}",
    )
  }
  val payload = JsonSupport.mapToJsonString(
    mapOf(
      "produced_outputs" to mapOf(
        "validation_repair_plan" to plan,
        "substantiation_receipts" to receipts,
      ),
    ),
  )
  return ValidationGateAgentRepairResult.Completed(
    FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = payload),
  )
}

internal fun kotlinPackWithoutGate(): PlatformManifest = PlatformManifest(
  slug = "kotlin",
  packRoot = validationGateTestRepoRoot.resolve("platform-packs/kotlin"),
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

internal class ScriptedGateRunner(
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
