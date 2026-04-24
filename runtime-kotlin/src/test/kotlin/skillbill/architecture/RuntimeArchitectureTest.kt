package skillbill.architecture

import skillbill.RuntimeModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeArchitectureTest {
  private val projectRoot: Path = Path.of("").toAbsolutePath().normalize()
  private val sourceRoot: Path = projectRoot.resolve("src/main/kotlin")

  @Test
  fun `architecture document declares package ownership and dependency direction`() {
    val architecture = Files.readString(projectRoot.resolve("ARCHITECTURE.md"))

    assertContains(architecture, "cli / mcp")
    assertContains(architecture, "-> application use cases")
    assertContains(architecture, "Current Package Ownership")
    assertContains(architecture, "Boundary Rules")
    assertContains(architecture, "MCP workflow calls must use application services")
    assertContains(architecture, "learning application use cases return typed results")
    assertContains(architecture, "repository and unit-of-work ports")
    assertContains(architecture, "LearningRecord is owned by the learnings domain")
    assertContains(architecture, "review parsing and triage decision normalization are pure surfaces")
    assertContains(architecture, "TelemetrySettingsProvider")
    assertContains(architecture, "TelemetryConfigStore")
    assertContains(architecture, "TelemetryClient")
    assertContains(architecture, "telemetry proxy DTO/payload mappers")
    assertContains(architecture, "schema_migrations")
    assertContains(architecture, "versioned database migrations")
  }

  @Test
  fun `runtime module declares current package boundaries`() {
    assertEquals(
      setOf(
        "skillbill.application",
        "skillbill.cli",
        "skillbill.contracts",
        "skillbill.db",
        "skillbill.di",
        "skillbill.error",
        "skillbill.install",
        "skillbill.infrastructure",
        "skillbill.launcher",
        "skillbill.learnings",
        "skillbill.mcp",
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
  fun `application layer stays independent of entrypoint frameworks`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.application") },
      bannedImports =
      listOf(
        "com.github.ajalt.clikt",
        "skillbill.cli",
        "skillbill.mcp",
      ),
    )
  }

  @Test
  fun `application services use persistence ports instead of sqlite infrastructure`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.application") },
      bannedImports =
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
      ),
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

    val reviewModels = Files.readString(sourceRoot.resolve("skillbill/review/ReviewModels.kt"))
    val learningRecord = Files.readString(sourceRoot.resolve("skillbill/learnings/LearningRecord.kt"))
    assertTrue("data class LearningRecord" !in reviewModels)
    assertContains(learningRecord, "data class LearningRecord")
  }

  @Test
  fun `review parsing and triage normalization are persistence free`() {
    val pureReviewFiles =
      listOf(
        sourceRoot.resolve("skillbill/review/ReviewParser.kt"),
        sourceRoot.resolve("skillbill/review/TriageDecisionParser.kt"),
      )

    assertNoBannedImports(
      files = pureReviewFiles.map(::sourceFile),
      bannedImports =
      listOf(
        "java.sql",
        "skillbill.db",
        "skillbill.infrastructure",
      ),
    )
  }

  @Test
  fun `cli workflow commands delegate to application instead of low level runtimes`() {
    assertNoBannedImports(
      files =
      sourceFiles().filter { file ->
        file.relativePath.startsWith("skillbill/cli/") &&
          !file.relativePath.startsWith("skillbill/cli/models/")
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
      files = sourceFiles().filter { file -> file.relativePath.startsWith("skillbill/mcp/") },
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
  fun `learning service exposes typed results instead of map payloads`() {
    val serviceSource = Files.readString(sourceRoot.resolve("skillbill/application/LearningService.kt"))
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
  fun `telemetry ports and adapters are explicit package surfaces`() {
    val portFiles =
      listOf(
        sourceRoot.resolve("skillbill/ports/telemetry/TelemetrySettingsProvider.kt"),
        sourceRoot.resolve("skillbill/ports/telemetry/TelemetryConfigStore.kt"),
        sourceRoot.resolve("skillbill/ports/telemetry/TelemetryClient.kt"),
        sourceRoot.resolve("skillbill/ports/persistence/TelemetryOutboxRepository.kt"),
      )
    portFiles.forEach { path ->
      assertTrue(Files.exists(path), "Missing telemetry port: ${sourceRoot.relativize(path)}")
    }

    assertContains(
      Files.readString(sourceRoot.resolve("skillbill/infrastructure/http/HttpTelemetryClient.kt")),
      "java.net.http.HttpClient",
    )
    assertContains(
      Files.readString(sourceRoot.resolve("skillbill/infrastructure/fs/FileTelemetryConfigStore.kt")),
      "java.nio.file.Files",
    )
    assertContains(
      Files.readString(sourceRoot.resolve("skillbill/contracts/telemetry/TelemetryProxyContracts.kt")),
      "data class TelemetryProxyBatchEvent",
    )
  }

  @Test
  fun `telemetry sync orchestration avoids concrete db filesystem and http APIs`() {
    assertNoBannedImports(
      files =
      listOf(
        sourceRoot.resolve("skillbill/telemetry/TelemetrySyncRuntime.kt"),
        sourceRoot.resolve("skillbill/telemetry/TelemetryConfigMutations.kt"),
        sourceRoot.resolve("skillbill/telemetry/DefaultTelemetrySettingsProvider.kt"),
        sourceRoot.resolve("skillbill/telemetry/TelemetryRemoteStatsRuntime.kt"),
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

  private fun assertNoBannedImports(files: List<SourceFile>, bannedImports: List<String>) {
    val violations =
      files.flatMap { file ->
        file.imports
          .filter { importedName -> bannedImports.any(importedName::startsWith) }
          .map { importedName -> "${file.relativePath} imports $importedName" }
      }
    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  private fun sourceFiles(): List<SourceFile> = Files.walk(sourceRoot).use { stream ->
    stream
      .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
      .map(::sourceFile)
      .toList()
  }

  private fun sourceFile(path: Path): SourceFile {
    val source = Files.readString(path)
    return SourceFile(
      relativePath = sourceRoot.relativize(path).toString().replace('\\', '/'),
      packageName = packagePattern.find(source)?.groupValues?.get(1).orEmpty(),
      imports = importPattern.findAll(source).map { it.groupValues[1].substringBefore(" as ") }.toList(),
    )
  }

  private data class SourceFile(
    val relativePath: String,
    val packageName: String,
    val imports: List<String>,
  )

  private companion object {
    val packagePattern: Regex = Regex("^package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
    val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
  }
}
