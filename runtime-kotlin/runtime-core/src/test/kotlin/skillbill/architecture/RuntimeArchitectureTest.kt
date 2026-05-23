package skillbill.architecture

import skillbill.RuntimeModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeArchitectureTest {
  private val readianMcpRuntime = "runtime-mcp/src/main/kotlin/skillbill/mcp/ReadianMcpRuntime.kt"
  private val mcpScaffoldRuntime = "runtime-mcp/src/main/kotlin/skillbill/mcp/McpScaffoldRuntime.kt"
  private val decompositionManifestFileWrites =
    "runtime-application/src/main/kotlin/skillbill/application/DecompositionManifestFileWrites.kt"
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
  fun `architecture document declares package ownership and dependency direction`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))

    assertContains(architecture, "cli / mcp")
    assertContains(architecture, "-> application use cases")
    assertContains(architecture, "Current Package Ownership")
    assertContains(architecture, "Boundary Rules")
    assertContains(architecture, "MCP workflow calls must use application services")
    assertContains(architecture, "learning application use cases return typed results")
    assertContains(architecture, "repository and unit-of-work ports")
    assertContains(architecture, "LearningRecord is owned by the learnings domain")
    assertContains(architecture, "review parsing and triage decision normalization are pure surfaces")
    assertContains(architecture, "SQL-backed review persistence")
    assertContains(architecture, "TelemetrySettingsProvider")
    assertContains(architecture, "TelemetryConfigStore")
    assertContains(architecture, "TelemetryClient")
    assertContains(architecture, "telemetry proxy payload mapping belongs with the HTTP adapter")
    assertContains(architecture, "schema_migrations")
    assertContains(architecture, "versioned database migrations")
    assertContains(architecture, "contract DTOs")
    assertContains(architecture, "runtime/schema parse-seam validators")
    assertContains(architecture, "Temporary SKILL-52 blocker")
    assertContains(architecture, "typed CLI presenter models")
    assertContains(architecture, "RuntimeSurfaceContract")
    assertContains(architecture, "RuntimeContext")
    assertContains(architecture, "skillbill.model")
    assertContains(architecture, "`model` packages")
    assertContains(architecture, "runtime-ports")
    assertContains(architecture, "gradle-module-split-evaluation.md")
  }

  @Test
  fun `runtime module declares current package boundaries`() {
    assertEquals(
      setOf(
        "skillbill.application",
        "skillbill.cli",
        "skillbill.contracts",
        "skillbill.db",
        "skillbill.desktop",
        "skillbill.di",
        "skillbill.error",
        "skillbill.install",
        "skillbill.infrastructure",
        "skillbill.launcher",
        "skillbill.learnings",
        "skillbill.mcp",
        "skillbill.model",
        "skillbill.nativeagent",
        "skillbill.ports",
        "skillbill.review",
        "skillbill.scaffold",
        "skillbill.telemetry",
        "skillbill.workflow.implement",
        "skillbill.workflow.verify",
      ),
      RuntimeModule.declaredSubsystemPackages.toSet(),
    )
  }

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
    val applicationPersistenceBannedImports =
      listOf(
        "java.sql",
        "java.nio.file.Files",
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
      files =
      sourceFiles()
        .filter { it.packageName.startsWith("skillbill.application") }
        .filterNot { it.relativePath == decompositionManifestFileWrites },
      bannedImports = applicationPersistenceBannedImports,
    )
    assertNoBannedImports(
      files = listOf(sourceFile(runtimeRoot.resolve(decompositionManifestFileWrites))),
      bannedImports = applicationPersistenceBannedImports - "java.nio.file.Files",
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
            .filter { ".model" !in file.packageName }
            .map { match -> "${file.relativePath} declares ${match.groupValues[2]} outside a model package" }
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
  fun `decomposition manifest application filesystem access has explicit temporary blocker coverage`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))
    val projectionIo = Files.readString(sourcePath("skillbill/application/DecompositionManifestFileWrites.kt"))

    assertContains(architecture, "Temporary SKILL-52 blocker: decomposition manifest projection")
    assertContains(architecture, "injected manifest storage port")
    assertContains(projectionIo, "Temporary SKILL-52 blocker")
    assertContains(projectionIo, "manifest storage port")
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
  fun `cli text rendering consumes typed presenter models instead of raw maps`() {
    val cliOutput = Files.readString(sourcePath("skillbill/cli/CliOutput.kt"))
    val cliPresenters = Files.readString(sourcePath("skillbill/cli/CliPresenters.kt"))

    assertTrue("List<Map<String, Any?>>" !in cliOutput)
    assertContains(cliOutput, "CliNumberedFindingsPresentation")
    assertContains(cliOutput, "CliResolvedLearningsPresentation")
    assertContains(cliPresenters, "data class CliTriagePresentation")
    assertContains(cliPresenters, "data class CliLearningListPresentation")
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
            .filter(line::contains)
            .map { reference ->
              "${file.relativePath}:${index + 1} contains $description $reference"
            }
        }
      }
    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
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
    val packagePattern: Regex = Regex("^package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
    val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
    val publicModelDeclarationPattern: Regex =
      Regex(
        "^\\s*(data\\s+class|enum\\s+class|sealed\\s+(?:class|interface))\\s+([A-Za-z0-9_]+)",
        RegexOption.MULTILINE,
      )
  }
}
