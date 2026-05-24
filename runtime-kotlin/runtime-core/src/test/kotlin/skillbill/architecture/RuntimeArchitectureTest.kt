package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("LargeClass") // central architecture-test suite; splitting would dilute coverage discovery
class RuntimeArchitectureTest {
  private val readianMcpRuntime = "runtime-mcp/src/main/kotlin/skillbill/mcp/ReadianMcpRuntime.kt"
  private val mcpScaffoldRuntime = "runtime-mcp/src/main/kotlin/skillbill/mcp/McpScaffoldRuntime.kt"
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }
  private val sourceRoots: List<Path> =
    listOf(
      runtimeRoot.resolve("runtime-application/src/main/kotlin"),
      runtimeRoot.resolve("runtime-contracts/src/main/kotlin"),
      runtimeRoot.resolve("runtime-core/src/main/kotlin"),
      runtimeRoot.resolve("runtime-domain/src/main/kotlin"),
      runtimeRoot.resolve("runtime-infra-fs/src/main/kotlin"),
      runtimeRoot.resolve("runtime-infra-http/src/main/kotlin"),
      runtimeRoot.resolve("runtime-infra-sqlite/src/main/kotlin"),
      runtimeRoot.resolve("runtime-cli/src/main/kotlin"),
      runtimeRoot.resolve("runtime-desktop/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/src/jvmMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/common/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/data/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/database/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/database/src/jvmMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/datastore/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/datastore/src/jvmMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/designsystem/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/domain/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/navigation/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/testing/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/core/ui/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/feature/skillbill/src/commonMain/kotlin"),
      runtimeRoot.resolve("runtime-mcp/src/main/kotlin"),
      runtimeRoot.resolve("runtime-ports/src/main/kotlin"),
    )

  @Test
  fun `runtime cli check task depends on validate agent configs`() {
    val buildFile = Files.readString(runtimeRoot.resolve("runtime-cli/build.gradle.kts"))
    assertContains(buildFile, "val validateAgentConfigs by tasks.registering(JavaExec::class)")
    val validateAgentConfigsBlock =
      Regex(
        """val validateAgentConfigs by tasks\.registering\(JavaExec::class\) \{(?<body>.*?)\}""",
        RegexOption.DOT_MATCHES_ALL,
      )
        .find(buildFile)
    assertTrue(validateAgentConfigsBlock != null, "validateAgentConfigs task configuration is missing")
    val validateAgentConfigsBody = validateAgentConfigsBlock.groups["body"]?.value.orEmpty()
    assertContains(validateAgentConfigsBody, "mainClass.set(application.mainClass)")
    assertContains(
      validateAgentConfigsBody,
      "args(\"validate-agent-configs\", \"--repo-root\", rootProject.projectDir.parentFile.absolutePath)",
    )
    val checkBlock = Regex("""tasks\.named\("check"\)\s*\{(?<body>.*?)\}""", RegexOption.DOT_MATCHES_ALL)
      .find(buildFile)
    assertTrue(checkBlock != null, "runtime-cli check task configuration is missing")
    assertContains(checkBlock.groups["body"]?.value.orEmpty(), "dependsOn(validateAgentConfigs)")
  }

  @Test
  fun `application layer stays independent of entrypoint frameworks`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.application") },
      bannedImports =
      listOf(
        "androidx.compose",
        "com.github.ajalt.clikt",
        "org.jetbrains.compose",
        "skillbill.cli",
        "skillbill.desktop",
        "skillbill.mcp",
      ),
    )
  }

  @Test
  fun `application services use persistence ports instead of sqlite infrastructure`() {
    val applicationFiles = sourceFiles()
      .filter { it.packageName.startsWith("skillbill.application") }
    val applicationPersistenceBannedImports =
      listOf(
        "java.sql",
        "skillbill.db",
        "skillbill.infrastructure",
        "skillbill.review.ReviewRuntime",
        "skillbill.review.TriageRuntime",
        "skillbill.telemetry.TelemetryConfigRuntime",
        "skillbill.telemetry.TelemetryConfigMutationRuntime",
        "skillbill.telemetry.TelemetryHttpRuntime",
        "skillbill.telemetry.TelemetryRemoteStatsRuntime",
      )
    assertNoBannedImports(
      files = applicationFiles,
      bannedImports = applicationPersistenceBannedImports,
    )
  }

  @Test
  fun `application domain and ports avoid direct file IO`() {
    val boundaryFiles =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-application/src/main/kotlin/") ||
            file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
            file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
        }

    assertNoBannedImports(
      files = boundaryFiles,
      bannedImports = directFileIoImports,
    )
    assertNoBannedSourceReferences(
      files = boundaryFiles,
      bannedReferences = directFileIoSourceReferences,
      description = "direct file IO dependency",
    )
  }

  @Test
  fun `learnings domain owns learning records without persistence dependencies`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.learnings") },
      bannedImports =
      listOf(
        "java.sql",
        "skillbill.db",
        "skillbill.infrastructure",
        "skillbill.review",
      ),
    )

    val reviewModels = Files.readString(sourcePath("skillbill/review/model/ReviewModels.kt"))
    val learningRecord = Files.readString(sourcePath("skillbill/learnings/model/LearningRecord.kt"))
    assertTrue("data class LearningRecord" !in reviewModels)
    assertContains(learningRecord, "data class LearningRecord")
  }

  @Test
  fun `public model declarations live in model packages`() {
    val violations =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-application/") ||
            file.relativePath.startsWith("runtime-domain/") ||
            file.relativePath.startsWith("runtime-ports/")
        }
        .flatMap { file ->
          val source = Files.readString(runtimeRoot.resolve(file.relativePath))
          publicModelDeclarationPattern
            .findAll(source)
            .filter { !file.packageName.split('.').contains("model") }
            .filter { !file.packageName.startsWith("skillbill.boundary") }
            .map { match ->
              val lineNumber = source.substring(0, match.range.first).count { it == '\n' } + 1
              "${file.relativePath}:$lineNumber declares ${match.groupValues.last()} outside a model package"
            }
        }
        .toList()

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `review package is separated from sqlite runtime support`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName == "skillbill.review" },
      bannedImports =
      listOf(
        "java.sql",
        "skillbill.db",
        "skillbill.infrastructure",
        "skillbill.ports",
        "skillbill.telemetry",
      ),
    )

    val sqliteReviewRuntime = sourcePath("skillbill/infrastructure/sqlite/review/ReviewRuntime.kt")
    val sqliteTriageRuntime = sourcePath("skillbill/infrastructure/sqlite/review/TriageRuntime.kt")
    val sqliteStatsRuntime = sourcePath("skillbill/infrastructure/sqlite/review/ReviewStatsRuntime.kt")
    listOf(sqliteReviewRuntime, sqliteTriageRuntime, sqliteStatsRuntime).forEach { path ->
      assertContains(Files.readString(path), "package skillbill.infrastructure.sqlite.review")
    }
  }

  @Test
  fun `cli workflow commands delegate to application instead of low level runtimes`() {
    assertNoBannedImports(
      files =
      sourceFiles().filter { file ->
        file.packageName.startsWith("skillbill.cli") &&
          !file.packageName.startsWith("skillbill.cli.models")
      },
      bannedImports =
      listOf(
        "skillbill.db",
        "skillbill.review",
        "skillbill.telemetry.TelemetryConfigRuntime",
        "skillbill.telemetry.TelemetryHttpRuntime",
        "skillbill.telemetry.TelemetryRemoteStatsRuntime",
        "skillbill.telemetry.TelemetrySyncRuntime",
        "skillbill.learnings.LearningStore",
        "skillbill.learnings.LearningsRuntime",
      ),
    )
  }

  @Test
  fun `mcp workflow calls delegate to application instead of low level runtimes`() {
    assertNoBannedImports(
      files = sourceFiles().filter { file -> file.packageName.startsWith("skillbill.mcp") },
      bannedImports =
      listOf(
        "skillbill.db",
        "skillbill.review",
        "skillbill.learnings.LearningStore",
        "skillbill.learnings.LearningsRuntime",
        "skillbill.telemetry.TelemetryConfigRuntime",
        "skillbill.telemetry.TelemetryRemoteStatsRuntime",
      ),
    )
  }

  @Test
  fun `mcp adapter avoids direct filesystem http sql dependencies except scaffold root discovery`() {
    val mcpFiles =
      sourceFiles()
        .filter { file -> file.relativePath.startsWith("runtime-mcp/src/main/kotlin/") }
    val cliFiles =
      sourceFiles()
        .filter { file -> file.relativePath.startsWith("runtime-cli/src/main/kotlin/") }

    assertNoBannedSourceReferences(
      files = mcpFiles,
      bannedReferences = listOf("java.net.http", "java.sql"),
      description = "direct HTTP or SQL dependency",
    )
    assertNoBannedSourceReferences(
      files = cliFiles,
      bannedReferences = listOf("java.net.http", "java.sql", "java.nio.file.Files", "Files."),
      description = "direct filesystem, HTTP, or SQL dependency",
    )

    // McpScaffoldRuntime keeps a temporary Files-based repo-root lookup for new_skill_scaffold.
    assertNoBannedSourceReferences(
      files =
      mcpFiles.filterNot { file ->
        file.relativePath in setOf(mcpScaffoldRuntime, readianMcpRuntime)
      },
      bannedReferences = listOf("java.nio.file.Files", "Files."),
      description = "direct filesystem dependency",
    )
    assertMcpScaffoldRuntimeOnlyUsesFilesForRepoRootDiscovery(mcpFiles)
    assertReadianMcpRuntimeOnlyUsesFilesForCommandDiscovery(mcpFiles)
  }

  @Test
  fun `learning service exposes typed results instead of map payloads`() {
    val serviceSource = Files.readString(sourcePath("skillbill/application/LearningService.kt"))
    val mapReturningLearningFunctions =
      Regex("""fun\s+(list|show|resolve|add|edit|setStatus|delete)\s*\([^)]*\)\s*:\s*Map<""")
        .findAll(serviceSource)
        .map { match -> match.groupValues[1] }
        .toList()

    assertTrue(
      mapReturningLearningFunctions.isEmpty(),
      "LearningService functions still return Map payloads: ${mapReturningLearningFunctions.joinToString()}",
    )
    assertContains(serviceSource, "LearningListResult")
    assertContains(serviceSource, "LearningResolveResult")
  }

  @Test
  fun `future domain packages stay infrastructure free`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.domain") },
      bannedImports =
      listOf(
        "com.github.ajalt.clikt",
        "java.net.http",
        "java.sql",
        "java.nio.file.Files",
        "skillbill.cli",
        "skillbill.db",
        "skillbill.mcp",
      ),
    )
  }

  @Test
  fun `touched domain contract foundation stays free of concrete adapters`() {
    assertNoBannedImports(
      files =
      sourceFiles().filter { file ->
        file.relativePath.startsWith("runtime-domain/src/main/kotlin/skillbill/workflow/") ||
          file.relativePath.startsWith("runtime-domain/src/main/kotlin/skillbill/install/model/")
      },
      bannedImports =
      listOf(
        "com.github.ajalt.clikt",
        "java.io",
        "java.net.http",
        "java.sql",
        "java.nio.file.Files",
        "kotlin.io.path",
        "skillbill.cli",
        "skillbill.db",
        "skillbill.desktop",
        "skillbill.infrastructure",
        "skillbill.mcp",
      ),
    )
  }

  @Test
  fun `runtime schema validators and schema resources are owned by runtime contracts`() {
    val contractFiles =
      listOf(
        "runtime-contracts/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaPaths.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaPaths.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaPaths.kt",
      )
    contractFiles.forEach { relative ->
      assertTrue(Files.isRegularFile(runtimeRoot.resolve(relative)), "Missing contract-owned file: $relative")
    }
    val legacyDomainContractFiles =
      listOf(
        "runtime-domain/src/main/kotlin/skillbill/workflow/DecompositionManifestSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/DecompositionManifestSchemaPaths.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowStateSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowStateSchemaPaths.kt",
        "runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanSchemaPaths.kt",
      )
    legacyDomainContractFiles.forEach { relative ->
      assertTrue(
        !Files.exists(runtimeRoot.resolve(relative)),
        "Legacy domain contract shim must stay absent: $relative",
      )
    }

    val runtimeContractsBuild = Files.readString(runtimeRoot.resolve("runtime-contracts/build.gradle.kts"))
    assertContains(runtimeContractsBuild, "copyWorkflowStateSchema")
    assertContains(runtimeContractsBuild, "copyInstallPlanSchema")
    assertContains(runtimeContractsBuild, "copyDecompositionManifestSchema")

    val runtimeDomainBuild = Files.readString(runtimeRoot.resolve("runtime-domain/build.gradle.kts"))
    assertTrue(
      "copyWorkflowStateSchema" !in runtimeDomainBuild &&
        "copyInstallPlanSchema" !in runtimeDomainBuild &&
        "copyDecompositionManifestSchema" !in runtimeDomainBuild,
      "runtime-domain must not own runtime contract schema copy tasks.",
    )
  }

  @Test
  fun `decomposition manifest application projection declares final parse seam ownership`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))
    val projectionIo = Files.readString(sourcePath("skillbill/application/DecompositionManifestFileWrites.kt"))

    assertContains(architecture, "Decomposition-manifest schema validation is owned by")
    assertContains(architecture, "skillbill.application.DecompositionManifestFileWrites")
    assertContains(architecture, "skillbill.ports.workflow.DecompositionManifestFileStore")
    assertContains(architecture, "FileSystemDecompositionManifestFileStore")
    assertContains(projectionIo, "Decomposition manifest parse/emission seam")
    assertContains(projectionIo, "DecompositionManifestSchemaValidator.validateYamlText")
    assertContains(projectionIo, "DecompositionManifestFileStore")
  }

  @Test
  fun `telemetry ports and adapters are explicit package surfaces`() {
    val portFiles =
      listOf(
        sourcePath("skillbill/ports/telemetry/TelemetrySettingsProvider.kt"),
        sourcePath("skillbill/ports/telemetry/TelemetryConfigStore.kt"),
        sourcePath("skillbill/ports/telemetry/TelemetryClient.kt"),
        sourcePath("skillbill/ports/persistence/TelemetryOutboxRepository.kt"),
      )
    portFiles.forEach { path ->
      assertTrue(Files.exists(path), "Missing telemetry port: ${runtimeRoot.relativize(path)}")
    }

    assertContains(
      Files.readString(sourcePath("skillbill/infrastructure/http/HttpTelemetryClient.kt")),
      "java.net.http.HttpClient",
    )
    assertContains(
      Files.readString(sourcePath("skillbill/infrastructure/fs/FileTelemetryConfigStore.kt")),
      "java.nio.file.Files",
    )
    assertContains(
      Files.readString(sourcePath("skillbill/contracts/telemetry/TelemetryProxyContracts.kt")),
      "data class TelemetryProxyBatchEvent",
    )
    assertContains(
      Files.readString(sourcePath("skillbill/infrastructure/http/TelemetryProxyPayloadMappers.kt")),
      "TelemetryProxyBatchPayload",
    )
  }

  @Test
  fun `contract package stays dto only without upward runtime dependencies`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.contracts") },
      bannedImports =
      listOf(
        "skillbill.application",
        "skillbill.cli",
        "skillbill.db",
        "skillbill.infrastructure",
        "skillbill.learnings",
        "skillbill.mcp",
        "skillbill.ports",
        "skillbill.review",
        "skillbill.telemetry",
      ),
    )
  }

  @Test
  fun `telemetry sync orchestration avoids concrete db filesystem and http APIs`() {
    assertNoBannedImports(
      files =
      listOf(
        sourcePath("skillbill/telemetry/TelemetryConfigRuntime.kt"),
        sourcePath("skillbill/telemetry/TelemetryConfigMutationRuntime.kt"),
        sourcePath("skillbill/telemetry/TelemetryHttpRuntime.kt"),
        sourcePath("skillbill/telemetry/TelemetrySyncRuntime.kt"),
        sourcePath("skillbill/telemetry/TelemetryConfigMutations.kt"),
        sourcePath("skillbill/telemetry/DefaultTelemetrySettingsProvider.kt"),
        sourcePath("skillbill/telemetry/TelemetryRemoteStatsRuntime.kt"),
      ).map(::sourceFile),
      bannedImports =
      listOf(
        "java.net.http",
        "java.sql",
        "java.nio.file.Files",
        "skillbill.db",
        "skillbill.infrastructure",
      ),
    )
  }

  @Test
  fun `cli and mcp learning payloads use contract DTO mappers`() {
    val cliPayloads = Files.readString(sourcePath("skillbill/cli/LearningCliPayloads.kt"))
    val mcpPayloads = Files.readString(sourcePath("skillbill/mcp/McpLearningPayloads.kt"))
    val learningMappers = Files.readString(sourcePath("skillbill/application/LearningContractMappers.kt"))
    val learningContracts = sourcePath("skillbill/contracts/learning/LearningContracts.kt")
    val systemContracts = sourcePath("skillbill/contracts/system/SystemContracts.kt")

    assertTrue(Files.exists(learningContracts), "Missing learning contract DTOs")
    assertTrue(Files.exists(systemContracts), "Missing system contract DTOs")
    assertContains(cliPayloads, "skillbill.application.toLearning")
    assertContains(mcpPayloads, "skillbill.application.toLearningResolveContract")
    assertContains(learningMappers, "skillbill.contracts.learning")
    assertTrue("learningEntryPayload" !in cliPayloads)
    assertTrue("learningEntryPayload" !in mcpPayloads)
  }

  @Test
  fun `runtime context does not depend on infrastructure defaults`() {
    assertNoBannedImports(
      files = listOf(sourceFile(sourcePath("skillbill/model/RuntimeContext.kt"))),
      bannedImports = listOf("skillbill.infrastructure"),
    )
    assertContains(
      Files.readString(sourcePath("skillbill/ports/telemetry/HttpRequester.kt")),
      "object UnconfiguredHttpRequester",
    )
  }

  @Test
  fun `gradle module split has an explicit evaluation decision`() {
    val evaluation = Files.readString(runtimeRoot.resolve("docs/architecture/gradle-module-split-evaluation.md"))

    assertContains(evaluation, "Status: Deeper Split Implemented")
    assertContains(evaluation, "physical Gradle split")
    assertContains(evaluation, "runtime-contracts")
    assertContains(evaluation, "runtime-domain")
    assertContains(evaluation, "runtime-application")
    assertContains(evaluation, "runtime-ports")
    assertContains(evaluation, "runtime-infra-fs")
    assertContains(evaluation, "runtime-infra-sqlite")
    assertContains(evaluation, "runtime-infra-http")
    assertContains(evaluation, "runtime-cli")
    assertContains(evaluation, "runtime-desktop")
    assertContains(evaluation, "runtime-mcp")
    assertContains(evaluation, "RuntimeContext")
    assertContains(evaluation, "Resolved Split Blockers")
    assertContains(evaluation, "No known package-level upward dependencies remain")
    assertContains(evaluation, "Deeper Split Readiness Criteria")
  }

  @Test
  fun `runtime architecture forbids raw map shapes outside the open-boundary allowlist`() {
    val boundaryFiles = sourceFiles().filter { file ->
      file.relativePath.startsWith("runtime-application/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
    }
    val violations = boundaryFiles.flatMap { file ->
      findRawMapViolations(file)
    }
    assertTrue(
      violations.isEmpty(),
      "Public application/domain/port declarations must not use raw Map<String, Any?> " +
        "shapes outside the open-boundary allow-list. Either annotate the declaration with " +
        "@OpenBoundaryMap or add it to RAW_MAP_OPEN_BOUNDARY_ALLOWLIST in " +
        "RuntimeArchitectureTest.kt.\nViolations:\n" + violations.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `open-boundary allow-list documents required exceptions`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))
    val documentedEntries = parseArchitectureAllowList(architecture)
    assertTrue(
      documentedEntries.isNotEmpty(),
      "ARCHITECTURE.md must declare an Open-Boundary Allow-List section parseable by the architecture test.",
    )
    val allowListEntries = RAW_MAP_OPEN_BOUNDARY_ALLOWLIST.toSet()
    val missingFromAllowlist = documentedEntries - allowListEntries
    val missingFromDoc = allowListEntries - documentedEntries
    assertTrue(
      missingFromAllowlist.isEmpty() && missingFromDoc.isEmpty(),
      "ARCHITECTURE.md and RAW_MAP_OPEN_BOUNDARY_ALLOWLIST must agree on the set of " +
        "open-boundary entries.\nMissing from constant: $missingFromAllowlist\n" +
        "Missing from doc: $missingFromDoc",
    )
    // The architecture document must mention the legacy raw-map
    // grandfather clause so future readers know why the allow-list is
    // larger than the required workflow entries.
    assertContains(architecture, "legacy raw-map")
    assertContains(architecture, "grandfathers")
  }

  @Test
  fun `every OpenBoundaryMap annotated declaration is documented in the architecture allow-list`() {
    val boundaryFiles = sourceFiles().filter { file ->
      file.relativePath.startsWith("runtime-application/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
    }
    val annotated = boundaryFiles.flatMap(::findAnnotatedOpenBoundaryDeclarations)
    val documentedEntries = parseArchitectureAllowList(
      Files.readString(runtimeRoot.resolve("ARCHITECTURE.md")),
    )
    val undocumented = annotated.filterNot { fqn -> fqn in documentedEntries }
    assertTrue(
      undocumented.isEmpty(),
      "Every @OpenBoundaryMap-annotated public declaration must appear by FQN in the " +
        "ARCHITECTURE.md Open-Boundary Allow-List section so the annotation cannot " +
        "act as a silent escape valve.\nUndocumented: $undocumented",
    )
  }

  @Test
  fun `raw map violation scanner fires on known violation fixtures`() {
    val fixture = SourceFile(
      relativePath = "test-fixture/Fake.kt",
      packageName = "skillbill.application",
      imports = emptyList(),
      source =
      """
      package skillbill.application

      class Fake {
        public fun foo(): Map<String, Any?> = emptyMap()

        fun bar(): Map<String, *> = emptyMap<String, Any?>()

        fun baz(input: MutableMap<String, Any?>) { input.clear() }

        fun multiLine(
          first: String,
        ): Map<String, Any?> = emptyMap()
      }
      """.trimIndent(),
    )
    val violations = findRawMapViolations(fixture)
    val violatingNames = violations.map { it.substringAfter("public `").substringBefore('`') }
    assertEquals(
      listOf("foo", "bar", "baz", "multiLine").sorted(),
      violatingNames.sorted(),
    )
  }

  @Test
  fun `cli text rendering consumes typed presenter models instead of raw maps`() {
    val cliOutput = Files.readString(sourcePath("skillbill/cli/CliOutput.kt"))
    val cliPresenters = Files.readString(sourcePath("skillbill/cli/CliPresenters.kt"))

    assertTrue("List<Map<String, Any?>>" !in cliOutput)
    assertContains(cliOutput, "CliNumberedFindingsPresentation")
    assertContains(cliOutput, "CliResolvedLearningsPresentation")
    assertContains(cliPresenters, "data class CliTriagePresentation")
    assertContains(cliPresenters, "data class CliLearningListPresentation")
  }

  /**
   * SKILL-52.1 — best-effort source scan for raw-map violations.
   * Detects public function and property declarations whose return type
   * or any parameter type contains one of the banned raw-map shapes.
   * Tracks the enclosing object/class lexically so allow-list lookups
   * use the fully-qualified name (package + class + member).
   *
   * Skips:
   *  - declarations annotated with `@OpenBoundaryMap`,
   *  - declarations whose computed FQN is listed in
   *    [RAW_MAP_OPEN_BOUNDARY_ALLOWLIST],
   *  - declarations marked `private` or `internal`.
   */
  @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth", "LongMethod")
  private fun findRawMapViolations(file: SourceFile): List<String> {
    val bannedShapes =
      listOf("Map<String, Any?>", "Map<String, *>", "MutableMap<String, Any?>")
    val lines = file.source.lines()
    val violations = mutableListOf<String>()
    val tracker = ScopeTracker()
    val allowlistSet = RAW_MAP_OPEN_BOUNDARY_ALLOWLIST.toSet()
    lines.forEachIndexed { index, line ->
      tracker.consume(line)
      val enclosingStack = tracker.enclosingStack

      val trimmed = line.trim()
      val funMatch = Regex("""^(?:public\s+)?fun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+\.)?([A-Za-z0-9_]+)\s*\(""")
        .find(trimmed)
      val valMatch = Regex("""^(?:public\s+)?(?:val|var)\s+([A-Za-z0-9_]+)\s*:""")
        .find(trimmed)
      val declName = funMatch?.groupValues?.get(2) ?: valMatch?.groupValues?.get(1) ?: return@forEachIndexed
      // Extract the FULL signature: accumulate lines until parens
      // balance closes, then accumulate one more line that contains
      // the return type or body marker.
      val signature = StringBuilder()
      var j = index
      var openParens = 0
      var sawParen = false
      var awaitingReturn = false
      while (j < lines.size) {
        val current = lines[j]
        signature.append(current).append('\n')
        current.forEach { ch ->
          when (ch) {
            '(' -> {
              openParens += 1
              sawParen = true
            }
            ')' -> openParens -= 1
          }
        }
        val closed = sawParen && openParens == 0
        if (closed) {
          val containsReturnMarker = current.contains("):") || current.contains(") :") ||
            current.endsWith(":") || current.contains(" {") || current.endsWith("{") ||
            current.contains(" =") || current.endsWith("= ") || current.endsWith(") = null")
          if (containsReturnMarker) break
          if (awaitingReturn) break
          awaitingReturn = true
        }
        if (!sawParen && valMatch != null && current.contains(": ")) break
        j += 1
        if (j - index > 30) break
      }
      val sigText = signature.toString()
      val containsBanned = bannedShapes.any { shape -> shape in sigText }
      if (!containsBanned) return@forEachIndexed
      val precedingLines = lines.subList(maxOf(0, index - 4), index)
      // Only consider preceding lines that look like annotation continuations
      // (start with `@`) so unrelated `private` declarations above the
      // current line do not silently mask a public violation.
      val annotationPrecedingLines = precedingLines
        .map(String::trim)
        .takeLastWhile { it.startsWith("@") || it.isEmpty() }
      val annotated = "@OpenBoundaryMap" in sigText ||
        annotationPrecedingLines.any { "@OpenBoundaryMap" in it }
      val nonPublicMarker = Regex("""^(?:private|internal)\s+""").containsMatchIn(trimmed) ||
        tracker.insideNonPublicScope
      // Build the FQN: package + enclosing scope chain + declName.
      val enclosingPrefix = enclosingStack.joinToString(".").let { if (it.isEmpty()) "" else "$it." }
      val fqn = listOf(file.packageName, "$enclosingPrefix$declName")
        .filter(String::isNotBlank)
        .joinToString(".")
      val allowed = fqn in allowlistSet
      if (!annotated && !allowed && !nonPublicMarker) {
        violations += "${file.relativePath}:${index + 1} public `$declName` exposes raw map shape (fqn=$fqn)"
      }
    }
    return violations
  }

  /**
   * SKILL-52.1 (F-003) — walks a source file and returns every FQN whose
   * declaration carries an `@OpenBoundaryMap` annotation. Used to ensure
   * the annotation does not act as a silent escape valve.
   */
  @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
  private fun findAnnotatedOpenBoundaryDeclarations(file: SourceFile): List<String> {
    val lines = file.source.lines()
    val results = mutableListOf<String>()
    val tracker = ScopeTracker()
    lines.forEachIndexed { index, line ->
      tracker.consume(line)
      val enclosingStack = tracker.enclosingStack
      val trimmed = line.trim()
      if (!trimmed.startsWith("@OpenBoundaryMap")) return@forEachIndexed
      // Walk forward to the next non-blank, non-annotation line carrying a fun/val/var/data class declaration.
      val candidate = lines.drop(index + 1)
        .map(String::trim)
        .firstOrNull { it.isNotBlank() && !it.startsWith("@") }
        ?: return@forEachIndexed
      val funMatch = Regex("""^(?:public\s+)?fun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+\.)?([A-Za-z0-9_]+)\s*\(""")
        .find(candidate)
      val valMatch = Regex("""^(?:public\s+)?(?:val|var)\s+([A-Za-z0-9_]+)\s*:""")
        .find(candidate)
      val classMatch = scopeDeclarationPattern.find(candidate)
      val declName = funMatch?.groupValues?.get(2)
        ?: valMatch?.groupValues?.get(1)
        ?: classMatch?.groupValues?.get(1)
        ?: return@forEachIndexed
      val enclosingPrefix = enclosingStack.joinToString(".").let { if (it.isEmpty()) "" else "$it." }
      val fqn = listOf(file.packageName, "$enclosingPrefix$declName")
        .filter(String::isNotBlank)
        .joinToString(".")
      results += fqn
    }
    return results
  }

  /**
   * SKILL-52.1 (F-006) — parses the curated Open-Boundary Allow-List
   * bullet section in `ARCHITECTURE.md` into a set of FQN strings.
   * The parsed bullets must use a leading `- ` then begin with the
   * FQN as backticked monospace text (the canonical doc format
   * established by this subtask).
   */
  private fun parseArchitectureAllowList(architecture: String): Set<String> {
    val sectionStart = architecture.indexOf("<!-- open-boundary-allowlist:start -->")
    val sectionEnd = architecture.indexOf("<!-- open-boundary-allowlist:end -->")
    require(sectionStart >= 0 && sectionEnd > sectionStart) {
      "ARCHITECTURE.md must declare an Open-Boundary Allow-List section bracketed by " +
        "'<!-- open-boundary-allowlist:start -->' / '<!-- open-boundary-allowlist:end -->' " +
        "machine-readable markers."
    }
    val body = architecture.substring(sectionStart, sectionEnd)
    return Regex("""^\s*-\s+`([A-Za-z0-9_.]+)`""", RegexOption.MULTILINE)
      .findAll(body)
      .map { it.groupValues[1] }
      .toSet()
  }

  /**
   * SKILL-52.1 — line-by-line lexical scope tracker for the architecture
   * scanner. Recognises `class`, `data class`, `object`, and `interface`
   * declarations and tracks the enclosing scope chain for downstream
   * FQN composition. Handles both brace-bodied scopes
   * (`object X { ... }`) and bodyless data classes
   * (`data class X(...)`) — the latter exit when the constructor
   * paren balance drops back to zero with no `{` ever opened.
   */
  private class ScopeTracker {
    val enclosingStack: ArrayDeque<String> = ArrayDeque()
    val scopeNonPublic: ArrayDeque<Boolean> = ArrayDeque()
    private val scopeKind: ArrayDeque<Kind> = ArrayDeque()

    // For BRACE scopes: track the brace depth at scope start.
    // For PAREN scopes: track the paren depth at scope start.
    private val scopeDepth: ArrayDeque<Int> = ArrayDeque()
    private var braceDepth = 0
    private var parenDepth = 0
    private var pendingScopeName: String? = null
    private var pendingScopeIsData = false
    private var pendingScopeNonPublic = false

    enum class Kind { BRACE, PAREN }

    val insideNonPublicScope: Boolean get() = scopeNonPublic.any { it }

    // For data classes, the constructor `(...)` and optional body `{...}`
    // both belong to the SAME class scope. When the ctor closes we may
    // need to re-push the class name once the body's `{` opens.
    private var resumeClassName: String? = null
    private var resumeClassNonPublic = false

    fun consume(lineText: String) {
      noteScopeDeclaration(lineText)
      lineText.forEach { ch ->
        when (ch) {
          '{' -> onOpenBrace()
          '}' -> onCloseBrace()
          '(' -> onOpenParen()
          ')' -> onCloseParen()
        }
      }
    }

    private fun noteScopeDeclaration(lineText: String) {
      val scopeMatch = scopeDeclarationPattern.find(lineText) ?: return
      pendingScopeName = scopeMatch.groupValues[1]
      pendingScopeIsData = lineText.contains(Regex("""\bdata\s+class\b"""))
      pendingScopeNonPublic = Regex("""^\s*(?:private|internal)\s+""").containsMatchIn(lineText)
      resumeClassName = null
      resumeClassNonPublic = false
    }

    private fun onOpenBrace() {
      val pendingName = pendingScopeName
      val resumeName = resumeClassName
      when {
        pendingName != null -> {
          pushScope(pendingName, Kind.BRACE, braceDepth, pendingScopeNonPublic)
          pendingScopeName = null
          pendingScopeIsData = false
          pendingScopeNonPublic = false
        }
        resumeName != null && parenDepth == 0 -> {
          pushScope(resumeName, Kind.BRACE, braceDepth, resumeClassNonPublic)
          resumeClassName = null
          resumeClassNonPublic = false
        }
      }
      braceDepth += 1
    }

    private fun onCloseBrace() {
      braceDepth -= 1
      popScopesWhile(Kind.BRACE) { braceDepth <= it }
    }

    private fun onOpenParen() {
      val pendingName = pendingScopeName
      if (pendingName != null && pendingScopeIsData) {
        pushScope(pendingName, Kind.PAREN, parenDepth, pendingScopeNonPublic)
        // Remember the class name in case the constructor is
        // followed by a body `{...}` belonging to the same class.
        resumeClassName = pendingName
        resumeClassNonPublic = pendingScopeNonPublic
        pendingScopeName = null
        pendingScopeIsData = false
        pendingScopeNonPublic = false
      }
      parenDepth += 1
    }

    private fun onCloseParen() {
      parenDepth -= 1
      popScopesWhile(Kind.PAREN) { parenDepth <= it }
    }

    private fun pushScope(name: String, kind: Kind, depth: Int, nonPublic: Boolean) {
      enclosingStack.addLast(name)
      scopeKind.addLast(kind)
      scopeDepth.addLast(depth)
      scopeNonPublic.addLast(nonPublic)
    }

    private inline fun popScopesWhile(kind: Kind, condition: (Int) -> Boolean) {
      while (scopeKind.isNotEmpty() && scopeKind.last() == kind && condition(scopeDepth.last())) {
        scopeKind.removeLast()
        scopeDepth.removeLast()
        enclosingStack.removeLast()
        scopeNonPublic.removeLast()
      }
    }
  }

  private fun assertNoBannedImports(files: List<SourceFile>, bannedImports: List<String>) {
    val violations =
      files.flatMap { file ->
        file.imports
          .filter { importedName -> bannedImports.any(importedName::startsWith) }
          .map { importedName -> "${file.relativePath} imports $importedName" }
      }
    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  private fun assertNoBannedSourceReferences(
    files: List<SourceFile>,
    bannedReferences: List<String>,
    description: String,
  ) {
    val violations =
      files.flatMap { file ->
        file.source.lines().flatMapIndexed { index, line ->
          bannedReferences
            .filter { reference -> line.containsBannedReference(reference) }
            .map { reference ->
              "${file.relativePath}:${index + 1} contains $description $reference"
            }
        }
      }
    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  private fun String.containsBannedReference(reference: String): Boolean = if (reference == "Files.") {
    Regex("""\bFiles\.""").containsMatchIn(this)
  } else {
    reference in this
  }

  private fun assertMcpScaffoldRuntimeOnlyUsesFilesForRepoRootDiscovery(mcpFiles: List<SourceFile>) {
    val scaffoldFile =
      mcpFiles.first { file ->
        file.relativePath == mcpScaffoldRuntime
      }
    val filesReferenceLines =
      scaffoldFile.source.lines()
        .filter { line -> "java.nio.file.Files" in line || "Files." in line }
        .map(String::trim)

    assertEquals(
      listOf(
        "import java.nio.file.Files",
        "val hasSettings = Files.isRegularFile(current.resolve(\"runtime-kotlin/settings.gradle.kts\"))",
        "val hasSkills = Files.isDirectory(current.resolve(\"skills\"))",
      ),
      filesReferenceLines,
    )
  }

  private fun assertReadianMcpRuntimeOnlyUsesFilesForCommandDiscovery(mcpFiles: List<SourceFile>) {
    val readianFile =
      mcpFiles.first { file ->
        file.relativePath == readianMcpRuntime
      }
    val filesReferenceLines =
      readianFile.source.lines()
        .filter { line -> "java.nio.file.Files" in line || "Files." in line }
        .map(String::trim)

    assertEquals(
      listOf(
        "import java.nio.file.Files",
        "if (!Files.isDirectory(versions)) return null",
        "return Files.list(versions).use { paths ->",
      ),
      filesReferenceLines,
    )
  }

  private fun sourceFiles(): List<SourceFile> = sourceRoots.flatMap { sourceRoot ->
    Files.walk(sourceRoot).use { stream ->
      stream
        .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
        .map(::sourceFile)
        .toList()
    }
  }

  private fun sourceFile(path: Path): SourceFile {
    val source = Files.readString(path)
    return SourceFile(
      relativePath = runtimeRoot.relativize(path).toString().replace('\\', '/'),
      packageName = packagePattern.find(source)?.groupValues?.get(1).orEmpty(),
      imports = importPattern.findAll(source).map { it.groupValues[1].substringBefore(" as ") }.toList(),
      source = source,
    )
  }

  private fun sourcePath(relativePath: String): Path = sourceRoots
    .map { sourceRoot -> sourceRoot.resolve(relativePath) }
    .firstOrNull(Files::exists)
    ?: error("Missing source file: $relativePath")

  private data class SourceFile(
    val relativePath: String,
    val packageName: String,
    val imports: List<String>,
    val source: String,
  )

  private companion object {
    /**
     * SKILL-52.1 — curated open-boundary allow-list for the raw-map
     * boundary rule. New entries MUST also be documented by FQN in
     * `runtime-kotlin/ARCHITECTURE.md` within the
     * `<!-- open-boundary-allowlist:start --> ... <!-- open-boundary-allowlist:end -->`
     * delimited section. The parity test `open-boundary allow-list
     * documents required exceptions` enforces a strict set equality
     * between this constant and the document.
     */
    val RAW_MAP_OPEN_BOUNDARY_ALLOWLIST: List<String> = listOf(
      // SKILL-52.1 documented workflow-scope open boundaries.
      "skillbill.workflow.WorkflowEngine.snapshotMap",
      "skillbill.workflow.WorkflowEngine.summaryMap",
      "skillbill.workflow.WorkflowEngine.resumeMap",
      "skillbill.workflow.WorkflowEngine.continueMap",
      "skillbill.workflow.DecompositionManifestCodec.decodeMap",
      "skillbill.workflow.toWireMap",
      "skillbill.application.decodeDecompositionManifestMap",
      "skillbill.application.encodeDecompositionManifestMap",
      "skillbill.application.DecompositionManifestWriter.writeFromWorkflowUpdate",
      "skillbill.application.DecompositionManifestWriter.manifestFromWorkflowUpdate",
      "skillbill.application.DecompositionManifestWriter.maybeWriteFromWorkflowUpdate",
      "skillbill.application.WorkflowFamily.sessionSummary",
      // Subtask 2 will remove (scaffold policy extraction):
      "skillbill.application.ScaffoldService.list",
      "skillbill.application.ScaffoldService.show",
      "skillbill.application.ScaffoldService.explain",
      "skillbill.application.ScaffoldService.validate",
      "skillbill.application.ScaffoldService.upgrade",
      "skillbill.application.ScaffoldService.fill",
      "skillbill.application.ScaffoldService.saveExactContent",
      "skillbill.application.ScaffoldService.editWithBodyFile",
      "skillbill.application.ScaffoldService.scaffold",
      "skillbill.ports.scaffold.ScaffoldGateway.list",
      "skillbill.ports.scaffold.ScaffoldGateway.show",
      "skillbill.ports.scaffold.ScaffoldGateway.explain",
      "skillbill.ports.scaffold.ScaffoldGateway.validate",
      "skillbill.ports.scaffold.ScaffoldGateway.upgrade",
      "skillbill.ports.scaffold.ScaffoldGateway.fill",
      "skillbill.ports.scaffold.ScaffoldGateway.saveExactContent",
      "skillbill.ports.scaffold.ScaffoldGateway.editWithBodyFile",
      "skillbill.ports.scaffold.ScaffoldGateway.scaffold",
      // SKILL-52.1 subtask 2 documented seams (pure-policy entrypoints that accept the wire
      // payload Map<String, Any?>). Retired together with the legacy ScaffoldGateway raw-map
      // surface above by subtask 3, which introduces a typed scaffold payload DTO.
      "skillbill.scaffold.policy.requireString",
      "skillbill.scaffold.policy.requireStringOrDefault",
      "skillbill.scaffold.policy.validatePayloadVersion",
      "skillbill.scaffold.policy.detectKind",
      "skillbill.scaffold.policy.optionalSpecialistSubagents",
      "skillbill.scaffold.policy.rejectLeafSubagentSpecialists",
      "skillbill.scaffold.policy.rejectBaselineLayersForNonPlatformPack",
      "skillbill.scaffold.policy.resolvePlatformPackSelection",
      "skillbill.scaffold.policy.resolvePlatformPackDefaults",
      // Subtask 3 will remove (install policy extraction):
      "skillbill.application.SystemService.doctor",
      "skillbill.application.SystemService.version",
      // Subtask 4 will remove (telemetry/review typed-DTO pass):
      "skillbill.application.lifecycleOkPayload",
      "skillbill.application.lifecycleSkippedPayload",
      "skillbill.application.lifecycleErrorPayload",
      "skillbill.application.orchestratedStartedSkippedPayload",
      "skillbill.application.orchestratedPayload",
      "skillbill.application.LifecycleTelemetryService.featureImplementStarted",
      "skillbill.application.LifecycleTelemetryService.featureImplementFinished",
      "skillbill.application.LifecycleTelemetryService.qualityCheckStarted",
      "skillbill.application.LifecycleTelemetryService.qualityCheckFinished",
      "skillbill.application.LifecycleTelemetryService.featureVerifyStarted",
      "skillbill.application.LifecycleTelemetryService.featureVerifyFinished",
      "skillbill.application.LifecycleTelemetryService.prDescriptionGenerated",
      "skillbill.application.ReviewService.previewImport",
      "skillbill.application.ReviewService.importReview",
      "skillbill.application.ReviewService.reviewFinishedTelemetryPayload",
      "skillbill.application.ReviewService.recordFeedback",
      "skillbill.application.ReviewService.telemetryPayload",
      "skillbill.application.ReviewService.reviewStats",
      "skillbill.application.ReviewService.featureImplementStats",
      "skillbill.application.ReviewService.featureVerifyStats",
      "skillbill.application.telemetryPayload",
      "skillbill.application.TelemetryService.status",
      "skillbill.application.TelemetryService.setLevel",
      "skillbill.application.TelemetryService.capabilities",
      "skillbill.application.TelemetryService.remoteStats",
      "skillbill.telemetry.defaultLocalTelemetryConfig",
      "skillbill.telemetry.validateRemoteStatsCapabilities",
      "skillbill.telemetry.TelemetryConfigRuntime.defaultLocalConfig",
      "skillbill.telemetry.TelemetryConfigRuntime.readLocalConfig",
      "skillbill.telemetry.TelemetryConfigRuntime.ensureLocalConfig",
      "skillbill.telemetry.TelemetryHttpRuntime.fetchProxyCapabilities",
      "skillbill.telemetry.TelemetryHttpRuntime.fetchRemoteStats",
      "skillbill.telemetry.TelemetrySyncRuntime.syncResultPayload",
      "skillbill.telemetry.TelemetrySyncRuntime.telemetryStatusPayload",
      "skillbill.workflow.WorkflowEngine.continueDecision",
      "skillbill.ports.persistence.ReviewRepository.updateReviewFinishedTelemetryState",
      "skillbill.ports.persistence.ReviewRepository.recordFeedback",
      "skillbill.ports.persistence.ReviewRepository.reviewStatsPayload",
      "skillbill.ports.persistence.ReviewRepository.featureImplementStatsPayload",
      "skillbill.ports.persistence.ReviewRepository.featureVerifyStatsPayload",
      "skillbill.ports.telemetry.TelemetryClient.fetchProxyCapabilities",
      "skillbill.ports.telemetry.TelemetryClient.fetchRemoteStats",
      "skillbill.ports.telemetry.TelemetryConfigStore.read",
      "skillbill.ports.telemetry.TelemetryConfigStore.ensure",
      "skillbill.ports.telemetry.TelemetryConfigStore.write",
      "skillbill.learnings.learningPayload",
      "skillbill.learnings.learningSummaryPayload",
      "skillbill.learnings.scopeCounts",
      "skillbill.learnings.learningSessionJson",
      "skillbill.learnings.summarizeLearningReferences",
      "skillbill.learnings.learningEntryPayload",
      // @OpenBoundaryMap-annotated typed-DTO open boundaries.
      "skillbill.application.model.WorkflowUpdateRequest.stepUpdates",
      "skillbill.application.model.WorkflowUpdateRequest.artifactsPatch",
      "skillbill.application.model.FeatureImplementFinishedRequest.childSteps",
      "skillbill.application.model.TelemetrySyncPayload.payload",
      "skillbill.application.model.TriageResult.payload",
      "skillbill.application.model.TriageResult.telemetryPayload",
      "skillbill.application.model.DecompositionManifestWriteRequest.planningResult",
      "skillbill.application.model.DecompositionManifestRuntimeUpdate.stepUpdates",
      "skillbill.application.model.DecompositionManifestRuntimeUpdate.artifactsPatch",
      "skillbill.application.model.DecompositionManifestRuntimeUpdate.existingArtifacts",
      "skillbill.install.model.buildInstallPlanWireMap",
      "skillbill.scaffold.model.PlatformManifest.customFields",
      "skillbill.telemetry.model.FeatureImplementFinishedRecord.childSteps",
      "skillbill.workflow.model.WorkflowSnapshotView.artifacts",
      "skillbill.workflow.model.WorkflowContinueView.stepArtifacts",
      "skillbill.workflow.model.WorkflowContinueView.extraFields",
      "skillbill.workflow.model.WorkflowContinueView.sessionSummary",
      "skillbill.workflow.model.WorkflowUpdateInput.stepUpdates",
      "skillbill.workflow.model.WorkflowUpdateInput.artifactsPatch",
      "skillbill.workflow.model.DecompositionSubtask.reviewResult",
      "skillbill.workflow.model.DecompositionSubtask.auditResult",
      "skillbill.workflow.model.DecompositionSubtask.validationResult",
      "skillbill.ports.validation.model.RepoValidationReport.toPayload",
      "skillbill.ports.validation.model.ReleaseRefMetadata.toPayload",
    )

    val directFileIoImports: List<String> =
      listOf(
        "java.io.File",
        "java.nio.file.Files",
        "kotlin.io.path",
        "kotlin.io.path.readText",
        "kotlin.io.path.writeText",
        "kotlin.io.path.inputStream",
        "kotlin.io.path.outputStream",
        "kotlin.io.path.bufferedReader",
        "kotlin.io.path.bufferedWriter",
      )
    val directFileIoSourceReferences: List<String> =
      listOf(
        "java.io.File",
        "java.nio.file.Files",
        "Files.",
        ".toFile()",
        ".readText()",
        ".writeText()",
        ".inputStream()",
        ".outputStream()",
        ".bufferedReader()",
        ".bufferedWriter()",
        "kotlin.io.path.readText",
        "kotlin.io.path.writeText",
        "kotlin.io.path.inputStream",
        "kotlin.io.path.outputStream",
        "kotlin.io.path.bufferedReader",
        "kotlin.io.path.bufferedWriter",
      )
    val packagePattern: Regex = Regex("^package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
    val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
    val publicModelDeclarationPattern: Regex =
      Regex(
        "^\\s*(?:public\\s+)?(data\\s+class|enum\\s+class|sealed\\s+(?:class|interface))\\s+([A-Za-z0-9_]+)",
        RegexOption.MULTILINE,
      )
    val scopeDeclarationPattern: Regex =
      Regex(
        """^\s*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+""" +
          """|data\s+|inner\s+|enum\s+|annotation\s+|value\s+)*""" +
          """(?:class|object|interface)\s+([A-Za-z0-9_]+)""",
      )
  }
}
