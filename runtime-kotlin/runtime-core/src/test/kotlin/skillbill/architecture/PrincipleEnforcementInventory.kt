package skillbill.architecture

object PrincipleEnforcementInventory {
  const val RUNTIME_APPLICATION_MAIN: String = "runtime-kotlin/runtime-application/src/main/kotlin"
  const val RUNTIME_CLI_MAIN: String = "runtime-kotlin/runtime-cli/src/main/kotlin"
  const val APPLICATION_PACKAGE_PREFIX: String = "skillbill.application."
  const val CLI_PACKAGE_PREFIX: String = "skillbill.cli."
  const val RUNTIME_CLI_SRC: String = "runtime-kotlin/runtime-cli/src"

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

  val enforceableRules: List<String> = listOf(
    "Package clustering: loose files in a subpackaged area must not belong to a sibling area cluster.",
    "Production line ceiling: no production Kotlin file may exceed 500 lines without an explicit exemption.",
    "Production logical-type line ceiling: attribute extension files to receiver types and enforce combined totals.",
    "Package acyclicity: mutual imports among skillbill.application and skillbill.cli areas must stay " +
      "within baseline.",
    "Ambient clock ban: Instant.now, LocalDateTime.now, LocalDate.now, and Clock.systemUTC require baseline " +
      "in runtime-application and runtime-cli main source.",
    "No @Inject constructor defaults: dependency bags and @Inject constructors must not carry default arguments.",
    "Failure wire codes: in-scope FailureWireCode hierarchies must map cases to codes totally and injectively.",
    "Typed parse boundaries: named untrusted-input decode sites must not report malformation via error, require, or" +
      "bare throw.",
    "Inline FQN ban: production and test Kotlin must not use inline fully-qualified references outside the keep-list.",
    "Convention ownership: module build files must not re-apply Test or toolchain settings " +
      "owned by configureKotlinJvm.",
    "Ambient environment ban: System.getenv, System.getProperty, and empty-string Path.of or Paths.get " +
      "require baseline in runtime-cli main source.",
    "Command-area isolation: every runtime-cli command area's transitive skillbill.cli import closure must " +
      "contain only the shared kernel and model leaves, never a sibling area or the composition root.",
    "Spillover-filename ban: no runtime-cli file may carry the *Extras, *Extras2, or *Extras3 suffix " +
      "signature outside a named exemption.",
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
