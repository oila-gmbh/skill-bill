package skillbill.architecture

object PrincipleEnforcementInventory {
  val enforceableRules: List<String> = listOf(
    "Package clustering: loose files in a subpackaged area must not belong to a sibling area cluster.",
    "Production line ceiling: no production Kotlin file may exceed 500 lines without an explicit exemption.",
    "Production logical-type line ceiling: attribute extension files to receiver types and enforce combined totals.",
    "Application package acyclicity: mutual imports among skillbill.application areas must stay within baseline.",
    "Runtime-application ambient clock ban: Instant.now, LocalDateTime.now, and Clock.systemUTC require baseline.",
    "No @Inject constructor defaults: dependency bags and @Inject constructors must not carry default arguments.",
    "Failure wire codes: in-scope FailureWireCode hierarchies must map cases to codes totally and injectively.",
    "Typed parse boundaries: named untrusted-input decode sites must not report malformation via error, require, or" +
      "bare throw.",
    "Inline FQN ban: production and test Kotlin must not use inline fully-qualified references outside the keep-list.",
    "Convention ownership: module build files must not re-apply Test or toolchain settings " +
      "owned by configureKotlinJvm.",
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
    "runtime-kotlin/runtime-application/src/main/kotlin",
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
