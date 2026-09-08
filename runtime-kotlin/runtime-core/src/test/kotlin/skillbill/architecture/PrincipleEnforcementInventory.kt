package skillbill.architecture

object PrincipleEnforcementInventory {
  const val RUNTIME_APPLICATION_MAIN: String = "runtime-kotlin/runtime-application/src/main/kotlin"
  const val RUNTIME_CLI_MAIN: String = "runtime-kotlin/runtime-cli/src/main/kotlin"
  const val APPLICATION_PACKAGE_PREFIX: String = "skillbill.application."
  const val CLI_PACKAGE_PREFIX: String = "skillbill.cli."
  const val RUNTIME_CLI_SRC: String = "runtime-kotlin/runtime-cli/src"
  const val SPILLOVER_FILE_NAME_BASELINE: String = "spillover-file-name-baseline.txt"

  data class ModuleArchitectureScanCase(
    val moduleName: String,
    val mainScanRoot: String,
    val moduleSourceRoot: String,
    val packagePrefix: String,
    val packageCycleBaseline: String,
    val ambientClockBaseline: String,
    val ambientEnvironmentBaseline: String,
    val injectDefaultsBaseline: String?,
  )

  val moduleArchitectureScanCases: List<ModuleArchitectureScanCase> =
    RuntimeModuleCatalog.declaredGradleModules.map(::moduleArchitectureScanCase)

  private fun moduleArchitectureScanCase(moduleName: String): ModuleArchitectureScanCase = ModuleArchitectureScanCase(
    moduleName = moduleName,
    mainScanRoot = "runtime-kotlin/$moduleName/src/main/kotlin",
    moduleSourceRoot = "runtime-kotlin/$moduleName/src",
    packagePrefix = packagePrefixForModule(moduleName),
    packageCycleBaseline = packageCycleBaselineForModule(moduleName),
    ambientClockBaseline = ambientClockBaselineForModule(moduleName),
    ambientEnvironmentBaseline = ambientEnvironmentBaselineForModule(moduleName),
    injectDefaultsBaseline = injectDefaultsBaselineForModule(moduleName),
  )

  private fun packagePrefixForModule(moduleName: String): String = when (moduleName) {
    "runtime-application" -> APPLICATION_PACKAGE_PREFIX
    "runtime-cli" -> CLI_PACKAGE_PREFIX
    "runtime-ports" -> "skillbill.ports."
    "runtime-mcp" -> "skillbill.mcp."
    "runtime-core" -> "skillbill.di."
    "runtime-contracts" -> "skillbill.contracts."
    else -> "skillbill."
  }

  private fun packageCycleBaselineForModule(moduleName: String): String = when (moduleName) {
    "runtime-application" -> "application-package-cycle-baseline.txt"
    "runtime-cli" -> "runtime-cli-package-cycle-baseline.txt"
    else -> "$moduleName-package-cycle-baseline.txt"
  }

  private fun ambientClockBaselineForModule(moduleName: String): String = when (moduleName) {
    "runtime-application" -> "runtime-application-ambient-clock-baseline.txt"
    "runtime-cli" -> "runtime-cli-ambient-clock-baseline.txt"
    else -> "$moduleName-ambient-clock-baseline.txt"
  }

  private fun ambientEnvironmentBaselineForModule(moduleName: String): String = when (moduleName) {
    "runtime-cli" -> "runtime-cli-ambient-environment-baseline.txt"
    else -> "$moduleName-ambient-environment-baseline.txt"
  }

  private fun injectDefaultsBaselineForModule(moduleName: String): String? = when (moduleName) {
    "runtime-application" -> "inject-constructor-defaults-baseline.txt"
    "runtime-cli" -> "runtime-cli-inject-constructor-defaults-baseline.txt"
    "runtime-ports",
    "runtime-infra-fs",
    "runtime-infra-http",
    "runtime-infra-sqlite",
    "runtime-mcp",
    -> "$moduleName-inject-constructor-defaults-baseline.txt"
    else -> null
  }

  /**
   * Leaf packages every command area may import: the shared CLI kernel and the CLI model. Anything
   * else in an area's transitive closure means the area cannot be built or tested on its own.
   */
  val cliSharedLeafAreas: Set<String> = setOf("kernel", "model")

  /**
   * The only `skillbill.cli` area allowed to import a command area. Every other area is probed for
   * isolation, so a one-directional hub edge between siblings cannot hide behind an empty cycle
   * baseline.
   */
  const val CLI_COMPOSITION_ROOT_AREA: String = "core"

  /** Named exemptions from the spillover-filename ban. Empty by rule, never by census. */
  val spilloverFileNameExemptions: Set<String> = emptySet()

  val sanctionedCompositionEntrypoints: Set<String> = setOf(
    "runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/ScaffoldStandaloneEntrypoint.kt",
  )

  /**
   * Repository-relative main-source paths exempt from ambient-environment baseline recording.
   * Exempt sites may still read the process environment at a named process boundary.
   */
  val ambientEnvironmentExemptions: Set<String> = setOf(
    "runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/Main.kt",
    "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/RuntimeBootstrapBindings.kt",
  )

  val enforceableRules: List<String> = listOf(
    "Package clustering: loose files in a subpackaged area must not belong to a sibling area cluster.",
    "Production line ceiling: no production Kotlin file may exceed 500 lines without an explicit exemption.",
    "Production logical-type line ceiling: attribute extension files to receiver types and enforce combined totals.",
    "Package acyclicity: mutual imports among areas under each module package prefix must stay within " +
      "that module baseline across all ten Gradle modules.",
    "Ambient clock ban: Instant.now, LocalDateTime.now, LocalDate.now, and Clock.systemUTC require baseline " +
      "in every module main source root.",
    "No @Inject constructor defaults: dependency bags and @Inject constructors must not carry default arguments.",
    "Failure wire codes: in-scope FailureWireCode hierarchies must map cases to codes totally and injectively.",
    "Typed parse boundaries: named untrusted-input decode sites must not report malformation via error, require, or" +
      "bare throw.",
    "Inline FQN ban: production and test Kotlin must not use inline fully-qualified references outside the keep-list.",
    "Convention ownership: module build files must not re-apply Test or toolchain settings " +
      "owned by configureKotlinJvm.",
    "Ambient environment ban: System.getenv, System.getProperty, and empty-string Path.of or Paths.get " +
      "require baseline in every module main source root.",
    "Command-area isolation: every runtime-cli command area's transitive skillbill.cli import closure must " +
      "contain only the shared kernel and model leaves, never a sibling area or the composition root.",
    "Spillover-filename ban: no source file in any runtime module may carry the spillover filename signature " +
      "(Extras, Continued, Helpers, Fns, Support, letter-plus-digit, or bare trailing-digit siblings) " +
      "outside a named exemption.",
    "Gradle module edges: every module api(project(...)) and implementation(project(...)) set is pinned " +
      "to today's edges.",
    "Port null-object classification: every Unavailable, Noop, Empty, or Unconfigured object under " +
      "runtime-ports, runtime-domain, and runtime-application main source must appear in " +
      "PortNullObjectClassification; recording null objects must emit through " +
      "RecordingNullObjectDiagnostics when bound.",
    "Composition-only construction: no main-source site outside skillbill.di may construct a " +
      "concrete class the RuntimeComponent binds; sanctioned second entrypoints are named explicitly.",
  )

  val parseBoundarySites: List<ArchitectureScanSupport.ParseBoundarySite> = listOf(
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/workflow/" +
        "GoalRunnerControlStore.kt",
      functionNames = setOf(
        "decodeControlState",
        "decodeReviewPolicy",
        "decodeAcceptances",
        "decodeExecutionLease",
        "legacyPausedAt",
        "booleanOrDefault",
        "nullableString",
        "requiredString",
        "toPositiveLong",
        "toPositiveIntOrNull",
        "nonNegativeLongOrDefault",
      ),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/" +
        "FeatureTaskRuntimePhaseOutputValidationModels.kt",
      functionNames = setOf(
        "fromWire",
        "fromArtifactMap",
        "requireRepairEvidenceString",
        "requireRepairEvidenceInt",
        "phaseOutputRepairEvidenceSchemaError",
      ),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/" +
        "FeatureTaskRuntimeHandoffSourceRef.kt",
      functionNames = setOf("fromWire"),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/" +
        "FeatureTaskRuntimeHandoffProjectionValue.kt",
      functionNames = setOf("fromWire"),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/" +
        "FeatureTaskRuntimeHandoffModels.kt",
      functionNames = setOf("fromWire"),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/" +
        "FeatureTaskRuntimeRunInvariantPromptFields.kt",
      functionNames = setOf("fromWire"),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/" +
        "FeatureTaskRuntimeRepositoryCheckpoint.kt",
      functionNames = setOf("fromWire"),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/goalplanning/" +
        "GoalVerificationBoundaryCaps.kt",
      functionNames = setOf("parse", "requiredPositiveInt", "requireKnownKeysOnly", "requireSupportedVersion"),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/goalplanning/" +
        "GoalPlanningDiscoveryExclusions.kt",
      functionNames = setOf(
        "parse",
        "requiredStringList",
        "requireKnownKeysOnly",
        "requireSupportedVersion",
        "requireBareDirectoryName",
        "requireNormalizedRoot",
      ),
    ),
    ArchitectureScanSupport.ParseBoundarySite(
      relativePath =
      "runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/platformpack/" +
        "ShellContentLoaderValidationGate.kt",
      functionNames = setOf(
        "parseValidationGate",
        "parseValidationGateFindings",
        "parseCompilerDiagnosticsLocator",
        "parseExecutedWorkSignal",
        "requireGateArgv",
        "optionalGateArgv",
        "parseSuppressionMarkers",
      ),
    ),
  )

  val inlineFqnPrefixes: List<String> = listOf(
    "java.",
    "javax.",
    "jakarta.",
    "kotlin.",
    "kotlinx.",
    "org.",
    "com.",
    "dev.",
    "skillbill.",
  )

  val inlineFqnScanRoots: List<String> = listOf(
    "runtime-kotlin",
    "intellij-plugin",
    "runtime-kotlin/build-logic",
  )

  val reviewOnlyRules: List<String> = listOf(
    "Comment quality and density — subjective editorial standards resist deterministic source scans.",
    "Naming taste beyond noun-family clustering — mechanical naming rules false-positive on " +
      "intentional domain vocabulary.",
    "Deeper noun-family relatedness inside a single area cluster — only cross-area loose-file " +
      "buckets are mechanically provable.",
    "Open harness and capability vocabulary keys when subtask 3 left them open — recorded in " +
      "agent/decisions.md instead of an enum gate.",
  )

  const val PRODUCTION_LINE_CEILING: Int = 500

  val productionLineCeilingExemptions: Map<String, String> = emptyMap()

  val packageClusteringGenericSegments: Set<String> = setOf(
    "model",
    "validation",
    "runner",
    "planning",
    "findings",
    "engine",
    "decomposition",
    "goal",
    "taskruntime",
    "idestatus",
    "specsource",
    "platformpack",
    "scaffold",
    "context",
    "plan",
    "spec",
    "diagnostics",
    "evidence",
    "config",
    "di",
    "telemetry",
    "learning",
    "workflow",
    "work",
    "updatecheck",
    "agentrun",
    "review",
    "persistence",
    "session",
    "db",
    "verification",
  )

  val packageClusteringSourceRoots: List<String> = listOf(
    RUNTIME_APPLICATION_MAIN,
    "runtime-kotlin/runtime-domain/src/main/kotlin",
    "runtime-kotlin/runtime-ports/src/main/kotlin",
  )

  val conventionOwnedTestPatterns: List<Pair<String, String>> = listOf(
    """if\s*\(\s*project\.hasProperty\(\s*"update-snapshots"\s*\)\s*\)""" to "update-snapshots Test systemProperty",
    """systemProperty\(\s*"update-snapshots"""" to "update-snapshots Test systemProperty",
    """useJUnitPlatform\s*\(\s*\)""" to "Test.useJUnitPlatform",
    """maxParallelForks\s*=""" to "Test.maxParallelForks",
    """maxHeapSize\s*=""" to "Test.maxHeapSize",
    """testLogging\s*\{""" to "Test.testLogging",
    """jvmToolchain\s*\(""" to "KotlinJvmProjectExtension.jvmToolchain",
    """languageVersion\.set\(JavaLanguageVersion""" to "JavaPluginExtension.toolchain.languageVersion",
  )
}
