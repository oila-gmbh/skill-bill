package skillbill.architecture

import skillbill.RuntimeModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("LargeClass") // central architecture-test suite; splitting would dilute coverage discovery
class RuntimeArchitectureTest {
  private val readianMcpRuntime = "runtime-mcp/src/main/kotlin/skillbill/mcp/core/ReadianMcpRuntime.kt"
  private val mcpScaffoldRuntime = "runtime-mcp/src/main/kotlin/skillbill/mcp/scaffold/McpScaffoldRuntime.kt"
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
      // SKILL-52.3 subtask 5 (AC2): the desktop data gateway jvmMain source set
      // is where the desktop adapter's runtime `skillbill.*` imports actually
      // live. Adding it puts the central import/raw-map scanners over the
      // gateway source instead of relying solely on the Gradle allow-list test.
      // Verified clean: jvmMain imports only skillbill.*.model[.command],
      // application services, ports, error, di, and model types.
      runtimeRoot.resolve("runtime-desktop/core/data/src/jvmMain/kotlin"),
      runtimeRoot.resolve("runtime-desktop/feature/skillbill/src/jvmMain/kotlin"),
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
        "skillbill.telemetry.config.TelemetryConfigRuntime",
        "skillbill.telemetry.config.TelemetryConfigMutationRuntime",
        "skillbill.telemetry.http.TelemetryHttpRuntime",
        "skillbill.telemetry.http.TelemetryRemoteStatsRuntime",
      )
    assertNoBannedImports(
      files = applicationFiles,
      bannedImports = applicationPersistenceBannedImports,
    )
  }

  @Test
  fun `runtime application owns no direct timing logging or threading environment APIs`() {
    val applicationMainFiles = sourceFilesIn(runtimeRoot.resolve("runtime-application/src/main/kotlin"))
    assertTrue(applicationMainFiles.isNotEmpty(), "runtime-application main source scan must be non-vacuous.")
    assertNoBannedImports(
      files = applicationMainFiles,
      bannedImports = listOf(
        "java.util.logging",
        "java.util.concurrent",
      ),
    )
    assertNoBannedSourceReferences(
      files = applicationMainFiles,
      bannedReferences = listOf(
        "Thread.sleep",
        "Thread.currentThread",
        "Thread(",
        ".interrupt()",
        ".getLogger(",
        "java.util.logging",
        "java.util.concurrent",
        "Executors",
        "Executor",
        "Future",
        "Callable",
        "TimeUnit",
      ),
      description = "environment API reference",
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
  fun `domain and ports avoid JDBC HTTP and entrypoint frameworks`() {
    val domainAndPortFiles =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
            file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
        }

    assertNoBannedImports(
      files = domainAndPortFiles,
      bannedImports = boundaryFrameworkImports,
    )
    assertNoBannedSourceReferences(
      files = domainAndPortFiles,
      bannedReferences = boundaryFrameworkSourceReferences,
      description = "JDBC, HTTP, or entrypoint framework dependency",
    )
  }

  @Test
  fun `domain avoids random ids clock reads and java util logging`() {
    val domainFiles =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-domain/src/main/kotlin/")
        }

    assertNoBannedSourceReferences(
      files = domainFiles,
      bannedReferences = domainEffectPuritySourceReferences,
      description = "runtime-domain effect-purity violation",
    )
  }

  @Test
  fun `application domain and ports use Path only as an inert value type`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))
    assertContains(architecture, "`java.nio.file.Path` is allowed")
    assertContains(architecture, "only as an inert value type")
    assertContains(architecture, "home-directory expansion")
    assertContains(architecture, "`System.getenv`")
    assertContains(architecture, "`System.getProperty`")

    val boundaryFiles =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-application/src/main/kotlin/") ||
            file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
            file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
        }
    val pathImportingFiles = boundaryFiles.filter { file -> "java.nio.file.Path" in file.imports }
    assertTrue(
      pathImportingFiles.isNotEmpty(),
      "The architecture intentionally allows java.nio.file.Path as a value type; the test must " +
        "exercise at least one current application/domain/port Path model or contract.",
    )
    assertNoBannedSourceReferences(
      files = boundaryFiles,
      bannedReferences = processAccessSourceReferences,
      description = "process or home-directory lookup",
    )
    assertNoBannedSourceReferences(
      files = boundaryFiles,
      bannedReferences = homeExpansionSourceReferences,
      description = "home-directory path expansion",
    )

    val reviewParsingPatterns = Files.readString(sourcePath("skillbill/review/ReviewParsingPatterns.kt"))
    assertTrue(
      "expandAndNormalizePath" !in reviewParsingPatterns,
      "ReviewParsingPatterns must stay pure string/regex parsing; filesystem path normalization belongs " +
        "to the adapter input seam.",
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
          !file.packageName.startsWith("skillbill.cli.model")
      },
      bannedImports =
      listOf(
        "skillbill.db",
        "skillbill.review",
        "skillbill.telemetry.config.TelemetryConfigRuntime",
        "skillbill.telemetry.http.TelemetryHttpRuntime",
        "skillbill.telemetry.http.TelemetryRemoteStatsRuntime",
        "skillbill.telemetry.sync.TelemetrySyncRuntime",
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
        "skillbill.telemetry.config.TelemetryConfigRuntime",
        "skillbill.telemetry.http.TelemetryRemoteStatsRuntime",
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
    val serviceSource = Files.readString(sourcePath("skillbill/application/learning/LearningService.kt"))
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
  fun `runtime schema validators and schema resources are owned by runtime infra-fs`() {
    // SKILL-52.3 subtask 1: the three schema validators + the coherence
    // validator moved from `runtime-contracts` to `runtime-infra-fs`
    // (the module that already owns `PlatformPackSchemaValidator` and
    // `NativeAgentCompositionSchemaValidator`). Only the pure `*SchemaPaths`
    // + contract-version constants stay in `runtime-contracts`. Validator
    // ownership now lives in `runtime-infra-fs`; the validators are reached
    // only through domain-owned ports.
    val infraValidatorFiles =
      listOf(
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaValidator.kt",
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaValidator.kt",
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaValidator.kt",
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestCoherenceValidator.kt",
      )
    infraValidatorFiles.forEach { relative ->
      assertTrue(Files.isRegularFile(runtimeRoot.resolve(relative)), "Missing infra-fs-owned validator: $relative")
    }
    val contractsPathFiles =
      listOf(
        "runtime-contracts/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaPaths.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaPaths.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaPaths.kt",
      )
    contractsPathFiles.forEach { relative ->
      assertTrue(Files.isRegularFile(runtimeRoot.resolve(relative)), "Missing contract-owned paths file: $relative")
    }
    val absentLegacyValidatorFiles =
      listOf(
        // Legacy contracts-owned validators (now in infra-fs).
        "runtime-contracts/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestCoherenceValidator.kt",
        // Legacy domain shims (must stay absent).
        "runtime-domain/src/main/kotlin/skillbill/workflow/DecompositionManifestSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/DecompositionManifestSchemaPaths.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowStateSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowStateSchemaPaths.kt",
        "runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanSchemaPaths.kt",
      )
    absentLegacyValidatorFiles.forEach { relative ->
      assertTrue(
        !Files.exists(runtimeRoot.resolve(relative)),
        "Legacy contract/domain validator shim must stay absent: $relative",
      )
    }

    val runtimeInfraFsBuild = Files.readString(runtimeRoot.resolve("runtime-infra-fs/build.gradle.kts"))
    assertContains(runtimeInfraFsBuild, "copyWorkflowStateSchema")
    assertContains(runtimeInfraFsBuild, "copyInstallPlanSchema")
    assertContains(runtimeInfraFsBuild, "copyDecompositionManifestSchema")

    val runtimeContractsBuild = Files.readString(runtimeRoot.resolve("runtime-contracts/build.gradle.kts"))
    assertTrue(
      "copyWorkflowStateSchema" !in runtimeContractsBuild &&
        "copyInstallPlanSchema" !in runtimeContractsBuild &&
        "copyDecompositionManifestSchema" !in runtimeContractsBuild,
      "runtime-contracts must no longer own runtime schema copy tasks.",
    )

    val runtimeDomainBuild = Files.readString(runtimeRoot.resolve("runtime-domain/build.gradle.kts"))
    assertTrue(
      "copyWorkflowStateSchema" !in runtimeDomainBuild &&
        "copyInstallPlanSchema" !in runtimeDomainBuild &&
        "copyDecompositionManifestSchema" !in runtimeDomainBuild,
      "runtime-domain must not own runtime contract schema copy tasks.",
    )
  }

  @Test
  fun `runtime contracts main source is free of networknt jackson and nio files`() {
    // SKILL-52.3 subtask 5 (AC4): after the subtask-1 validator relocation,
    // `runtime-contracts` is a pure DTO/constants/exceptions leaf. This test
    // LOCKS that purity: the module's main source must contain neither
    // `com.networknt.*` nor `com.fasterxml.jackson.*` nor `java.nio.file.Files`,
    // scanned over BOTH parsed imports and raw source text so an inline FQN or
    // a `Files.` call with no import is also caught. The source already passes;
    // the fixture-driven positive control below proves the scanner fires.
    val contractsFiles =
      sourceFiles().filter { file -> file.relativePath.startsWith("runtime-contracts/src/main/kotlin/") }
    assertTrue(
      contractsFiles.isNotEmpty(),
      "runtime-contracts main source must exist for the purity lock to be meaningful.",
    )
    assertNoBannedImports(
      files = contractsFiles,
      bannedImports = contractsForbiddenImports,
    )
    assertNoBannedSourceReferences(
      files = contractsFiles,
      bannedReferences = contractsForbiddenSourceReferences,
      description = "runtime-contracts infrastructure-coupling violation",
    )
  }

  @Test
  fun `runtime contracts purity scanner fires on synthetic fixtures`() {
    // SKILL-52.3 subtask 5 (AC4) positive control: each banned reference
    // (networknt, Jackson, java.nio.file.Files) must be reported by the
    // source-text scanner on a synthetic fixture so a regression in the ban
    // list or the `Files.` regex loud-fails.
    val fixtureSource =
      """
      package skillbill.contracts

      import com.networknt.schema.JsonSchemaFactory
      import com.fasterxml.jackson.databind.ObjectMapper
      import java.nio.file.Files

      object ContractsLeak {
        fun read() {
          Files.readString(somePath)
        }
      }
      """.trimIndent()
    val fixture = syntheticSourceFile("test-fixture/ContractsLeak.kt", fixtureSource)
    // F-006: imports are parsed from the fixture source via the production
    // importPattern (no hand-written second copy), and F-002: the fixture is
    // driven through the REAL `assertNoBannedImports` so a regression in the
    // import extraction or the assertion itself loud-fails.
    assertEquals(
      listOf(
        "com.networknt.schema.JsonSchemaFactory",
        "com.fasterxml.jackson.databind.ObjectMapper",
        "java.nio.file.Files",
      ),
      fixture.imports,
      "Production importPattern must parse the fixture's three forbidden imports from source.",
    )
    assertFailsWith<AssertionError>(
      "assertNoBannedImports must THROW on the contracts fixture; otherwise the runtime-contracts " +
        "import purity lock is not actually exercised.",
    ) {
      assertNoBannedImports(files = listOf(fixture), bannedImports = contractsForbiddenImports)
    }
    // Source-text positive control: the `Files.` call site (no import) must be
    // caught by the production source scanner.
    val sourceViolations = contractsForbiddenSourceReferences
      .filter { reference -> fixture.source.lines().any { line -> line.containsBannedReference(reference) } }
    assertEquals(
      contractsForbiddenSourceReferences,
      sourceViolations,
      "Contracts purity source scanner must report each banned reference (incl. the `Files.` call site).",
    )
  }

  @Test
  fun `runtime contracts purity scanner does not flag benign Files-like tokens`() {
    // F-004: clean/negative control for the load-bearing `\bFiles\.` regex and
    // the import ban — benign source that mentions `Files`-like tokens which are
    // NOT java.nio.file.Files must produce ZERO violations (no false positive).
    val cleanFixture = syntheticSourceFile(
      "test-fixture/ContractsClean.kt",
      """
      package skillbill.contracts

      data class ProfileFiles(val names: List<String>)

      object ContractsClean {
        fun count(): Int {
          val profileFiles = listOf<String>()
          return profileFiles.size
        }
      }
      """.trimIndent(),
    )
    assertEquals(
      emptyList(),
      cleanFixture.imports.filter { importedName -> contractsForbiddenImports.any(importedName::startsWith) },
      "Clean fixture must declare no forbidden imports.",
    )
    val cleanSourceViolations = cleanFixture.source.lines().flatMap { line ->
      contractsForbiddenSourceReferences.filter { reference -> line.containsBannedReference(reference) }
    }
    assertEquals(
      emptyList(),
      cleanSourceViolations,
      "Source scanner must NOT flag benign `Files`-like identifiers (`profileFiles`, `ProfileFiles`) that " +
        "are not the banned `java.nio.file.Files` / `Files.` call site.",
    )
  }

  private fun syntheticSourceFile(relativePath: String, source: String): SourceFile = SourceFile(
    relativePath = relativePath,
    packageName = packagePattern.find(source)?.groupValues?.get(1).orEmpty(),
    imports = importPattern.findAll(source).map { it.groupValues[1].substringBefore(" as ") }.toList(),
    source = source,
  )

  @Test
  fun `runtime domain workflow source must not import contract schema validators or contract mappers`() {
    // SKILL-52.2 Subtask 4 / SKILL-52.3 subtask 1: schema + coherence
    // validators (now owned by `runtime-infra-fs`) and contract payload
    // mappers are reached only through domain-owned ports wired at
    // `runtime-application` / `runtime-core`. `runtime-domain` workflow
    // AND install source consume them through the
    // `WorkflowSnapshotValidator` / `DecompositionManifestValidator` /
    // `InstallPlanWireValidator` ports. Direct imports of any concrete
    // `*SchemaValidator` / `*CoherenceValidator` (regardless of owning
    // module) or any `skillbill.contracts.*Mapper` are banned from the
    // workflow + install domain source.
    val guardedDomainFiles =
      sourceFiles().filter { file ->
        file.relativePath.startsWith("runtime-domain/src/main/kotlin/skillbill/workflow/") ||
          file.relativePath.startsWith("runtime-domain/src/main/kotlin/skillbill/install/")
      }
    val violations =
      guardedDomainFiles.flatMap { file ->
        file.imports
          .filter { importedName ->
            importedName.endsWith("SchemaValidator") ||
              importedName.endsWith("CoherenceValidator") ||
              (importedName.startsWith("skillbill.contracts.") && importedName.endsWith("Mapper"))
          }
          .map { importedName -> "${file.relativePath} imports banned $importedName" }
      }
    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `decomposition manifest application projection declares final parse seam ownership`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))
    val projectionIo = Files.readString(
      sourcePath("skillbill/application/decomposition/DecompositionManifestFileWrites.kt"),
    )

    assertContains(architecture, "Decomposition-manifest schema validation is owned by")
    assertContains(architecture, "skillbill.application.decomposition.DecompositionManifestFileWrites")
    assertContains(architecture, "skillbill.ports.workflow.DecompositionManifestFileStore")
    assertContains(architecture, "FileSystemDecompositionManifestFileStore")
    assertContains(projectionIo, "Decomposition manifest parse/emission seam")
    // SKILL-52.3 subtask 1: the concrete schema validator moved to
    // `runtime-infra-fs`; the application seam now flows through the
    // injected `DecompositionManifestValidator` port.
    assertContains(projectionIo, "validator.validateYamlText")
    assertContains(projectionIo, "DecompositionManifestValidator")
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
    val telemetryClientPort = Files.readString(sourcePath("skillbill/ports/telemetry/TelemetryClient.kt"))
    assertContains(telemetryClientPort, "skillbill.telemetry.model.TelemetryProxyCapabilities")
    assertContains(telemetryClientPort, "skillbill.telemetry.model.TelemetryRemoteStatsResult")

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
  fun `review and telemetry domain models do not own json payload contracts`() {
    val violations =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-domain/src/main/kotlin/skillbill/review/") ||
            file.relativePath.startsWith("runtime-domain/src/main/kotlin/skillbill/telemetry/")
        }
        .filter { file ->
          "JsonPayloadContract" in file.source ||
            Regex("""fun\s+[A-Za-z0-9_.]+\s*\([^)]*\)\s*:\s*Map<String,\s*Any\?>""").containsMatchIn(file.source)
        }
        .map { file -> file.relativePath }

    assertTrue(
      violations.isEmpty(),
      "Review and telemetry domain packages must stay typed; JSON payload projection belongs at " +
        "application, port, or adapter seams.\n" +
        violations.joinToString(separator = "\n"),
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
        sourcePath("skillbill/telemetry/config/TelemetryConfigRuntime.kt"),
        sourcePath("skillbill/telemetry/config/TelemetryConfigMutationRuntime.kt"),
        sourcePath("skillbill/telemetry/http/TelemetryHttpRuntime.kt"),
        sourcePath("skillbill/telemetry/sync/TelemetrySyncRuntime.kt"),
        sourcePath("skillbill/telemetry/config/TelemetryConfigMutations.kt"),
        sourcePath("skillbill/telemetry/settings/DefaultTelemetrySettingsProvider.kt"),
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
    val cliPayloads = Files.readString(sourcePath("skillbill/cli/learning/LearningCliPayloads.kt"))
    val mcpPayloads = Files.readString(sourcePath("skillbill/mcp/learning/McpLearningPayloads.kt"))
    val learningMappers = Files.readString(sourcePath("skillbill/application/learning/LearningContractMappers.kt"))
    val learningContracts = sourcePath("skillbill/contracts/learning/LearningContracts.kt")
    val systemContracts = sourcePath("skillbill/contracts/system/SystemContracts.kt")

    assertTrue(Files.exists(learningContracts), "Missing learning contract DTOs")
    assertTrue(Files.exists(systemContracts), "Missing system contract DTOs")
    assertContains(cliPayloads, "skillbill.application.learning.toLearning")
    assertContains(mcpPayloads, "skillbill.application.learning.toLearningResolveContract")
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
  fun `install ports expose typed capability APIs instead of retired gateways`() {
    val installPortFiles = sourceFiles()
      .filter { sourceFile ->
        sourceFile.relativePath.startsWith(
          "runtime-ports/src/main/kotlin/skillbill/ports/install/",
        )
      }
    assertTrue(installPortFiles.isNotEmpty(), "Install capability ports must exist.")

    val sourceText = installPortFiles.joinToString(separator = "\n", transform = SourceFile::source)
    listOf(
      "interface InstallPlanningFactsPort",
      "interface InstallPlatformSkillMaterializationPort",
      "interface InstallStagingIntentPort",
      "interface InstallApplyExecutionPort",
      "interface InstallSkillLinkPort",
      "interface InstallAgentTargetPort",
      "interface InstallNativeAgentLinkPort",
      "interface InstallMcpRegistrationPort",
    ).forEach { expectedDeclaration ->
      assertContains(sourceText, expectedDeclaration)
    }

    listOf(
      "InstallPlanGateway",
      "InstallAgentGateway",
      "NativeAgentInstallGateway",
      "McpRegistrationGateway",
      "Map<String, Any?>",
      "Map<String, *>",
      "MutableMap<String, Any?>",
    ).forEach { forbiddenText ->
      assertTrue(
        forbiddenText !in sourceText,
        "Install port public surface must not contain retired/raw-map shape '$forbiddenText'.",
      )
    }

    val nonRequestResultSignatures = installPortFiles
      .filter { sourceFile -> sourceFile.relativePath.endsWith("Port.kt") }
      .flatMap { sourceFile ->
        installPortFunctionSignatures(sourceFile).mapNotNull { signature ->
          if (signature.hasSingleRequestParameter && signature.hasResultReturn) null else signature.render()
        }
      }
    assertTrue(
      nonRequestResultSignatures.isEmpty(),
      "Install capability port functions must accept exactly one *Request model and return a *Result model.\n" +
        nonRequestResultSignatures.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `crash reconciliation liveness stays behind the injectable supervisor and out of the process runner`() {
    // AC-005 (SKILL-140 subtask 5): crash-reconciliation liveness goes only through the injectable
    // FeatureTaskRuntimeWorkerSupervisor port. The agent process runner (ProcessWaitLoop) gains no
    // agent-conditional branching or reconciliation coupling, and the reconciliation code never reaches
    // into a concrete agent runner.
    val reconciliationSources = sourceFiles().filter { file ->
      file.relativePath.endsWith("featuretask/FeatureTaskRuntimeCrashReconciler.kt") ||
        file.relativePath.endsWith("featuretask/FeatureTaskRuntimeWorkerCoordinator.kt") ||
        file.relativePath.endsWith("goalrunner/GoalRunnerWorkflowStores.kt")
    }
    assertTrue(reconciliationSources.isNotEmpty(), "crash-reconciliation source scan must be non-vacuous.")
    assertTrue(
      reconciliationSources.any { file -> "FeatureTaskRuntimeWorkerSupervisor" in file.source },
      "Crash reconciliation must reach liveness through the injectable FeatureTaskRuntimeWorkerSupervisor port.",
    )
    assertNoBannedSourceReferences(
      files = reconciliationSources,
      bannedReferences = listOf(
        "skillbill.launcher.process",
        "JvmAgentRunProcessRunner",
        "AgentRunCommandBuilder",
        "ProcessWaitLoop",
      ),
      description = "concrete agent-runner coupling in crash reconciliation",
    )

    val processRunner = sourceFiles().single { file ->
      file.relativePath.endsWith("launcher/process/JvmAgentRunProcessRunner.kt")
    }
    val runnerCouplingToReconciliation = listOf(
      "CrashReconcil",
      "CrashLiveness",
      "FeatureTaskRuntimeWorkerSupervisor",
      "reconcileFeatureTaskRuntimeCrashedWorker",
    ).filter { reference -> reference in processRunner.source }
    assertEquals(
      emptyList(),
      runnerCouplingToReconciliation,
      "The agent process runner (ProcessWaitLoop) must stay decoupled from crash reconciliation and the " +
        "supervisor liveness port; agent-conditional branching belongs behind injectable strategies.",
    )
  }

  private fun installPortFunctionSignatures(sourceFile: SourceFile): List<InstallPortFunctionSignature> {
    val lines = sourceFile.source.lines()
    return lines.mapIndexedNotNull { index, line ->
      val match = portFunctionStartPattern.find(line.trim()) ?: return@mapIndexedNotNull null
      val signatureText = collectFunctionSignature(lines, index)
      val parsed = portFunctionSignaturePattern.find(signatureText)
      val functionName = match.groupValues[1]
      val parameters = parsed?.groupValues?.get(2).orEmpty().trim()
      val returnType = parsed?.groupValues?.get(3).orEmpty()
      val parameterTypes = parameters.split(",")
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { parameter -> parameter.substringAfter(":").trim().substringAfterLast(".") }
      InstallPortFunctionSignature(
        sourcePath = sourceFile.relativePath,
        functionName = functionName,
        parameters = parameters,
        returnType = returnType,
        hasSingleRequestParameter = parameterTypes.size == 1 && parameterTypes.single().endsWith("Request"),
        hasResultReturn = returnType.substringBefore("<").substringAfterLast(".").endsWith("Result"),
      )
    }
  }

  private fun collectFunctionSignature(lines: List<String>, startIndex: Int): String {
    val signature = StringBuilder()
    var openParens = 0
    var sawParen = false
    var index = startIndex
    var shouldStop = false
    while (index < lines.size && !shouldStop) {
      val current = lines[index]
      signature.append(current.trim()).append(' ')
      current.forEach { char ->
        when (char) {
          '(' -> {
            openParens += 1
            sawParen = true
          }
          ')' -> openParens -= 1
        }
      }
      if (sawParen && openParens == 0) {
        val text = signature.toString()
        shouldStop = hasFunctionSignatureTerminator(text)
        val nextLine = lines.getOrNull(index + 1)?.trim().orEmpty()
        if (!nextLine.startsWith(":")) shouldStop = true
      }
      index += 1
    }
    return signature.toString()
  }

  private fun hasFunctionSignatureTerminator(text: String): Boolean =
    containsReturnTypeSeparator(text) || " =" in text || text.trim().endsWith("{")

  private fun containsReturnTypeSeparator(text: String): Boolean = "):" in text || ") :" in text

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
  fun `SKILL-52_2 inventory classifies every public raw-map declaration exactly once`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))
    val inventory = parseSkill522Inventory(architecture)
    assertTrue(
      inventory.entries.isNotEmpty(),
      "ARCHITECTURE.md must declare a SKILL-52.2 inventory section parseable by the architecture test.",
    )
    assertInventoryCategoriesKnown(inventory)
    assertInventoryMatchesAllowList(inventory)
    assertInventoryHasNoDuplicateFqns(inventory)
    assertAnnotatedDeclarationsAreOpenExtension(inventory)
    assertSubtaskIdsPresentForGatedCategories(inventory)
  }

  private fun assertInventoryCategoriesKnown(inventory: Skill522Inventory) {
    val knownCategories = setOf(
      "must_type_now",
      "open_extension",
      "private_serializer",
      "postponed_with_reason",
    )
    val unknownCategories = inventory.entries.map { it.category }.toSet() - knownCategories
    assertTrue(
      unknownCategories.isEmpty(),
      "SKILL-52.2 inventory contains unknown categories: $unknownCategories. " +
        "Allowed: $knownCategories.",
    )
  }

  // (a) Strict-set equality with the SKILL-52.1 open-boundary allow-list. The
  // allow-list IS the canonical set of public raw-map declarations in
  // runtime-application/-domain/-ports (parity with the document is enforced
  // by the existing `open-boundary allow-list documents required exceptions`
  // test). Therefore every classified inventory FQN MUST be in the allow-list,
  // and every allow-list FQN MUST be classified by the inventory.
  private fun assertInventoryMatchesAllowList(inventory: Skill522Inventory) {
    val inventoryFqns = inventory.entries.map { it.fqn }.toSet()
    val allowList = RAW_MAP_OPEN_BOUNDARY_ALLOWLIST.toSet()
    val missingFromInventory = allowList - inventoryFqns
    val unknownInInventory = inventoryFqns - allowList
    assertTrue(
      missingFromInventory.isEmpty() && unknownInInventory.isEmpty(),
      "SKILL-52.2 inventory must classify every entry in RAW_MAP_OPEN_BOUNDARY_ALLOWLIST " +
        "exactly once.\nMissing from inventory: $missingFromInventory\n" +
        "Inventory entries not in allow-list: $unknownInInventory",
    )
  }

  // (b) Each FQN must be classified exactly once.
  private fun assertInventoryHasNoDuplicateFqns(inventory: Skill522Inventory) {
    val duplicates = inventory.entries.groupBy { it.fqn }
      .filterValues { it.size > 1 }
      .keys
    assertTrue(
      duplicates.isEmpty(),
      "SKILL-52.2 inventory must classify every FQN exactly once. Duplicates: $duplicates",
    )
  }

  // (c) Every @OpenBoundaryMap-annotated declaration in
  // runtime-application/-domain/-ports MUST sit under the `open_extension`
  // category — the annotation may not be classified as private_serializer,
  // postponed_with_reason, or must_type_now.
  private fun assertAnnotatedDeclarationsAreOpenExtension(inventory: Skill522Inventory) {
    val annotatedFqns = sourceFiles()
      .filter { file ->
        file.relativePath.startsWith("runtime-application/src/main/kotlin/") ||
          file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
          file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
      }
      .flatMap(::findAnnotatedOpenBoundaryDeclarations)
      .toSet()
    val openExtensionFqns = inventory.entries
      .filter { it.category == "open_extension" }
      .map { it.fqn }
      .toSet()
    val annotatedNotOpenExtension = annotatedFqns - openExtensionFqns
    assertTrue(
      annotatedNotOpenExtension.isEmpty(),
      "Every @OpenBoundaryMap-annotated declaration in runtime-application/-domain/-ports " +
        "MUST be classified under SKILL-52.2 inventory category `open_extension`.\n" +
        "Misclassified: $annotatedNotOpenExtension",
    )
  }

  // (d) Every must_type_now and postponed_with_reason entry MUST carry a
  // SKILL-52.2 subtask id in 2..5.
  private fun assertSubtaskIdsPresentForGatedCategories(inventory: Skill522Inventory) {
    val needsSubtaskCategories = setOf("must_type_now", "postponed_with_reason")
    val missingSubtask = inventory.entries
      .filter { it.category in needsSubtaskCategories }
      .filter { it.subtaskId == null || it.subtaskId !in 2..5 }
      .map { "${it.fqn} (category=${it.category}, subtaskId=${it.subtaskId})" }
    assertTrue(
      missingSubtask.isEmpty(),
      "Every SKILL-52.2 inventory entry under $needsSubtaskCategories MUST carry a " +
        "[subtask N] tag with N in 2..5.\nNon-compliant:\n" +
        missingSubtask.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `SKILL-52_2 inventory parser fires on synthetic fixture`() {
    val fixture =
      """
      <!-- skill-52-2-inventory:start -->

      ### must_type_now

      - `skillbill.fake.MustTypeOne` [subtask 3] — rationale.
      - `skillbill.fake.MustTypeTwo`
        [subtask 5] — wrapped-line rationale.

      ### open_extension (@OpenBoundaryMap)

      - `skillbill.fake.OpenExtensionOne`
      - `skillbill.fake.OpenExtensionTwo`

      ### private_serializer

      _None — placeholder._

      ### postponed_with_reason

      - `skillbill.fake.PostponedOne` [subtask 4] — reason.

      <!-- skill-52-2-inventory:end -->
      """.trimIndent()
    val parsed = parseSkill522Inventory(fixture)
    assertEquals(
      setOf(
        "skillbill.fake.MustTypeOne" to "must_type_now",
        "skillbill.fake.MustTypeTwo" to "must_type_now",
        "skillbill.fake.OpenExtensionOne" to "open_extension",
        "skillbill.fake.OpenExtensionTwo" to "open_extension",
        "skillbill.fake.PostponedOne" to "postponed_with_reason",
      ),
      parsed.entries.map { it.fqn to it.category }.toSet(),
    )
    val subtaskById = parsed.entries.associate { it.fqn to it.subtaskId }
    assertEquals(3, subtaskById["skillbill.fake.MustTypeOne"])
    assertEquals(5, subtaskById["skillbill.fake.MustTypeTwo"])
    assertEquals(4, subtaskById["skillbill.fake.PostponedOne"])
    assertEquals(null, subtaskById["skillbill.fake.OpenExtensionOne"])
  }

  /**
   * SKILL-52.2 — parses the inventory bullet section in ARCHITECTURE.md
   * bracketed by `<!-- skill-52-2-inventory:start -->` /
   * `<!-- skill-52-2-inventory:end -->`. Recognises four category
   * subheadings (`### must_type_now`, `### open_extension`,
   * `### private_serializer`, `### postponed_with_reason`) and walks
   * the bullets under each heading. Bullets MUST follow the canonical
   * format `- \`<fqn>\`` and MAY carry a trailing `[subtask N]` tag.
   */
  private fun parseSkill522Inventory(architecture: String): Skill522Inventory {
    val body = extractSkill522InventoryBody(architecture)
    val rawLines = body.lines()
    val state = InventoryParseState()
    while (state.index < rawLines.size) {
      state.index = advanceInventoryCursor(rawLines, state)
    }
    return Skill522Inventory(entries = state.entries)
  }

  private fun advanceInventoryCursor(rawLines: List<String>, state: InventoryParseState): Int {
    val index = state.index
    val trimmed = rawLines[index].trim()
    val heading = INVENTORY_HEADING_PATTERN.find(trimmed)?.groupValues?.get(1)
    if (heading != null) {
      state.currentCategory = heading
    }
    val bulletMatch = if (heading == null) INVENTORY_BULLET_PATTERN.find(trimmed) else null
    val entry = buildInventoryEntry(state.currentCategory, bulletMatch, rawLines, index)
    if (entry != null) {
      state.entries += entry.entry
    }
    return entry?.nextIndex ?: (index + 1)
  }

  private class InventoryParseState(
    var index: Int = 0,
    var currentCategory: String? = null,
    val entries: MutableList<Skill522InventoryEntry> = mutableListOf(),
  )

  private fun extractSkill522InventoryBody(architecture: String): String {
    val sectionStart = architecture.indexOf("<!-- skill-52-2-inventory:start -->")
    val sectionEnd = architecture.indexOf("<!-- skill-52-2-inventory:end -->")
    require(sectionStart >= 0 && sectionEnd > sectionStart) {
      "ARCHITECTURE.md must declare a SKILL-52.2 inventory section bracketed by " +
        "'<!-- skill-52-2-inventory:start -->' / '<!-- skill-52-2-inventory:end -->' " +
        "machine-readable markers."
    }
    return architecture.substring(sectionStart, sectionEnd)
  }

  // Two-pass walk: group continuation lines (non-blank, no bullet leader)
  // with their preceding bullet so multi-line wrapped bullets carrying a
  // trailing `[subtask N]` token on the wrapped line are recognised.
  private fun buildInventoryEntry(
    category: String?,
    bulletMatch: MatchResult?,
    rawLines: List<String>,
    index: Int,
  ): InventoryEntryWithCursor? {
    if (category == null || bulletMatch == null) return null
    val (lookahead, joinedTail) = consumeContinuationLines(
      rawLines = rawLines,
      startIndex = index + 1,
      head = bulletMatch.groupValues[2],
    )
    val subtaskId = INVENTORY_SUBTASK_PATTERN.find(joinedTail)?.groupValues?.get(1)?.toIntOrNull()
    return InventoryEntryWithCursor(
      entry = Skill522InventoryEntry(
        fqn = bulletMatch.groupValues[1],
        category = category,
        subtaskId = subtaskId,
      ),
      nextIndex = lookahead,
    )
  }

  private fun consumeContinuationLines(rawLines: List<String>, startIndex: Int, head: String): Pair<Int, String> {
    val accumulator = StringBuilder(head)
    val end = rawLines
      .asSequence()
      .drop(startIndex)
      .takeWhile { line -> isInventoryContinuationLine(line) }
      .onEach { line -> accumulator.append(' ').append(line.trim()) }
      .count() + startIndex
    return end to accumulator.toString()
  }

  // Stop at blank lines, new bullets, or new headings — anything that is not
  // a wrapped continuation of the current bullet.
  private fun isInventoryContinuationLine(line: String): Boolean {
    val trimmed = line.trim()
    val isTerminator = trimmed.isEmpty() ||
      INVENTORY_BULLET_LEADER_PATTERN.containsMatchIn(line) ||
      INVENTORY_HEADING_PATTERN.containsMatchIn(trimmed)
    return !isTerminator
  }

  private data class InventoryEntryWithCursor(
    val entry: Skill522InventoryEntry,
    val nextIndex: Int,
  )

  private data class Skill522InventoryEntry(
    val fqn: String,
    val category: String,
    val subtaskId: Int?,
  )

  private data class Skill522Inventory(
    val entries: List<Skill522InventoryEntry>,
  )

  @Test
  fun `raw map violation scanner fires on known violation fixtures`() {
    val fixture = SourceFile(
      relativePath = "test-fixture/Fake.kt",
      packageName = "skillbill.application",
      imports = emptyList(),
      source = rawMapViolationFixtureSource(),
    )
    val violations = findRawMapViolations(fixture)
    val violatingNames = violations.map { it.substringAfter("public `").substringBefore('`') }
    assertEquals(
      expectedRawMapViolationFixtureNames(),
      violatingNames.sorted(),
    )
  }

  private fun rawMapViolationFixtureSource(): String = """
    package skillbill.application

    typealias AnyMapAlias = Map<String, Any>
    typealias HashMapAlias = HashMap<String, Any?>

    class Fake {
      public fun foo(): Map<String, Any?> = emptyMap()

      public fun nonNullMap(): Map<String, Any> = emptyMap()

      fun bar(): Map<String, *> = emptyMap<String, Any?>()

      fun baz(input: MutableMap<String, Any?>) { input.clear() }

      fun mutableNonNull(input: MutableMap<String, Any>) { input.clear() }

      fun mutableStar(input: MutableMap<String, *>) {}

      fun hashMap(input: HashMap<String, Any?>) { input.clear() }

      fun hashMapNonNull(input: HashMap<String, Any>) { input.clear() }

      fun hashMapStar(input: HashMap<String, *>) {}

      fun linkedHashMap(input: LinkedHashMap<String, Any?>) { input.clear() }

      fun linkedHashMapNonNull(input: LinkedHashMap<String, Any>) { input.clear() }

      fun linkedHashMapStar(input: LinkedHashMap<String, *>) {}

      fun aliasMap(input: AnyMapAlias) {}

      fun aliasHashMap(): HashMapAlias = hashMapOf()

      fun multiLine(
        first: String,
      ): Map<String, Any?> = emptyMap()
    }
  """.trimIndent()

  private fun expectedRawMapViolationFixtureNames(): List<String> = listOf(
    "aliasHashMap",
    "aliasMap",
    "bar",
    "baz",
    "foo",
    "hashMap",
    "hashMapNonNull",
    "hashMapStar",
    "linkedHashMap",
    "linkedHashMapNonNull",
    "linkedHashMapStar",
    "multiLine",
    "mutableNonNull",
    "mutableStar",
    "nonNullMap",
  ).sorted()

  @Test
  fun `every main source package is declared under an owned subsystem`() {
    val ownershipPrefixes = RuntimeModule.declaredSubsystemPackages.sortedByDescending(String::length)
    val unowned = declaredMainSourceFiles()
      .filter { file -> file.packageName.isNotBlank() }
      .filterNot { file -> file.packageName == "skillbill" }
      .filterNot { file ->
        ownershipPrefixes.any { prefix -> file.packageName == prefix || file.packageName.startsWith("$prefix.") }
      }
      .map { file -> "${file.packageName} in ${file.relativePath}" }
      .distinct()
      .sorted()
    assertEquals(
      emptyList(),
      unowned,
      "Every real main-source package must be owned by RuntimeModule.declaredSubsystemPackages.",
    )
  }

  @Test
  fun `inner layer test sources do not import adapters infrastructure or desktop packages`() {
    val forbiddenPrefixes = listOf(
      "skillbill.infrastructure.",
      "skillbill.cli.",
      "skillbill.mcp.",
      "skillbill.desktop.",
    )
    val violations = innerLayerTestSourceFiles().flatMap { file ->
      file.imports
        .filter { importedName -> forbiddenPrefixes.any(importedName::startsWith) }
        .map { importedName -> "${file.relativePath} imports $importedName" }
    }
    assertEquals(
      emptyList(),
      violations.sorted(),
      "Inner-layer tests must use application/domain/port-facing seams instead of adapter packages.",
    )
  }

  @Test
  fun `cli text rendering consumes typed presenter models instead of raw maps`() {
    val cliOutput = Files.readString(sourcePath("skillbill/cli/core/CliOutput.kt"))
    val cliPresenters = Files.readString(sourcePath("skillbill/cli/core/CliPresenters.kt"))

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
      listOf(
        "Map<String, Any?>",
        "Map<String, Any>",
        "Map<String, *>",
        "HashMap<String, Any?>",
        "HashMap<String, Any>",
        "HashMap<String, *>",
        "LinkedHashMap<String, Any?>",
        "LinkedHashMap<String, Any>",
        "LinkedHashMap<String, *>",
        "MutableMap<String, Any?>",
        "MutableMap<String, Any>",
        "MutableMap<String, *>",
      )
    val lines = file.source.lines()
    val bannedTypeAliases = rawMapTypeAliases(file.source, bannedShapes)
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
      val containsBanned = bannedShapes.any { shape -> shape in sigText } ||
        bannedTypeAliases.any { alias -> Regex("""\b${Regex.escape(alias)}\b""").containsMatchIn(sigText) }
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

  private fun rawMapTypeAliases(source: String, bannedShapes: List<String>): Set<String> {
    val directAliases = mutableMapOf<String, String>()
    val aliasPattern = Regex("""^typealias\s+([A-Za-z0-9_]+)\s*=\s*(.+)$""", RegexOption.MULTILINE)
    aliasPattern.findAll(source).forEach { match ->
      directAliases[match.groupValues[1]] = match.groupValues[2].trim()
    }
    val bannedAliases = mutableSetOf<String>()
    var changed = true
    while (changed) {
      changed = false
      directAliases.forEach { (alias, target) ->
        if (alias !in bannedAliases && (bannedShapes.any { shape -> shape in target } || target in bannedAliases)) {
          bannedAliases += alias
          changed = true
        }
      }
    }
    return bannedAliases
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
    sourceFilesIn(sourceRoot)
  }

  private fun declaredMainSourceFiles(): List<SourceFile> = RuntimeModule.declaredGradleModules
    .flatMap { moduleName -> mainSourceRoots(moduleName) }
    .flatMap { sourceRoot -> sourceFilesIn(sourceRoot) }

  private fun innerLayerTestSourceFiles(): List<SourceFile> =
    listOf("runtime-application", "runtime-domain", "runtime-ports")
      .flatMap { moduleName ->
        listOf("src/test/kotlin", "src/jvmTest/kotlin", "src/commonTest/kotlin")
          .map { sourceSet -> runtimeRoot.resolve(moduleName.replace(':', '/')).resolve(sourceSet) }
          .filter(Files::isDirectory)
      }
      .flatMap { sourceRoot -> sourceFilesIn(sourceRoot) }

  private fun mainSourceRoots(moduleName: String): List<Path> {
    val sourceRoot = runtimeRoot.resolve(moduleName.replace(':', '/')).resolve("src")
    if (!Files.isDirectory(sourceRoot)) return emptyList()
    return Files.list(sourceRoot).use { stream ->
      stream
        .filter(Files::isDirectory)
        .filter { path -> path.fileName.toString() == "main" || path.fileName.toString().endsWith("Main") }
        .map { path -> path.resolve("kotlin") }
        .filter(Files::isDirectory)
        .toList()
        .sorted()
    }
  }

  private fun sourceFilesIn(sourceRoot: Path): List<SourceFile> = Files.walk(sourceRoot).use { stream ->
    stream
      .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
      .map(::sourceFile)
      .toList()
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

  private data class InstallPortFunctionSignature(
    val sourcePath: String,
    val functionName: String,
    val parameters: String,
    val returnType: String,
    val hasSingleRequestParameter: Boolean,
    val hasResultReturn: Boolean,
  ) {
    fun render(): String = "$sourcePath::$functionName($parameters): ${returnType.ifBlank { "<missing>" }}"
  }

  private companion object {
    val INVENTORY_HEADING_PATTERN: Regex =
      Regex("""^###\s+(must_type_now|open_extension|private_serializer|postponed_with_reason)\b""")
    val INVENTORY_BULLET_PATTERN: Regex =
      Regex("""^\s*-\s+`([A-Za-z0-9_.]+)`(.*)$""")
    val INVENTORY_SUBTASK_PATTERN: Regex = Regex("""\[subtask\s+(\d+)\]""")
    val INVENTORY_BULLET_LEADER_PATTERN: Regex = Regex("""^\s*-\s+""")

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
      "skillbill.workflow.WorkflowEngine.compactContinueMap",
      "skillbill.workflow.WorkflowEngine.updateAcknowledgementMap",
      "skillbill.workflow.model.WorkflowContinuationArtifactSummary.value",
      // SKILL-52.2 subtask 4: domain-owned workflow-snapshot validator port.
      // The map is the canonical schema-validated wire snapshot envelope; the
      // port stays raw-map at the validation seam because the schema itself
      // validates against the canonical map envelope.
      "skillbill.workflow.WorkflowSnapshotValidator.validate",
      // SKILL-52.3 subtask 1: domain-owned install-plan + decomposition
      // validator ports. Each stays raw-map at the validation seam because
      // the canonical schema validates against the wire-map envelope, the
      // same rationale as the workflow-snapshot validator port above.
      "skillbill.install.model.InstallPlanWireValidator.validate",
      "skillbill.workflow.DecompositionManifestValidator.validate",
      "skillbill.workflow.DecompositionManifestValidator.validateYamlText",
      // SKILL-52.3 subtask 4: domain-owned manifest file-store port. The YAML
      // serialization seam accepts the canonical schema-validated wire map and
      // delegates the concrete `YAMLMapper` mechanics to the infra-fs adapter,
      // mirroring the decode-side validator port above.
      "skillbill.ports.workflow.DecompositionManifestFileStore.encodeManifestYaml",
      "skillbill.workflow.DecompositionManifestCodec.decodeMap",
      "skillbill.workflow.toWireMap",
      "skillbill.application.decomposition.decodeDecompositionManifestMap",
      "skillbill.application.decomposition.encodeDecompositionManifestMap",
      "skillbill.application.decomposition.DecompositionManifestWriter.writeFromWorkflowUpdate",
      "skillbill.application.decomposition.DecompositionManifestWriter.manifestFromWorkflowUpdate",
      "skillbill.application.decomposition.DecompositionManifestWriter.maybeWriteFromWorkflowUpdate",
      "skillbill.application.workflow.WorkflowFamily.sessionSummary",
      // SKILL-61 subtask 1: goal-observability event maps are durable
      // workflow-artifact/schema seams. The domain validator owns the schema
      // boundary and CLI/MCP/projector rendering consumes compact maps after
      // validation.
      "skillbill.workflow.GoalObservabilityEventValidator.validate",
      "skillbill.workflow.GoalPlanningPreparationEnvelopeValidator.validate",
      // SKILL-129 subtask 1: the review-context packet, assignment, and
      // launch envelopes are schema-validated as canonical wire maps. The
      // typed projection owns composition; only the validation seam and the
      // envelope wrapper expose the map, mirroring the validator ports above.
      "skillbill.review.context.ReviewContextEnvelopeValidator.validate",
      "skillbill.application.review.model.ReviewContextEnvelope.asWireMap",
      "skillbill.application.review.toBoundedPayload",
      "skillbill.ports.persistence.model.ReviewAccountingRecord.boundedPayload",
      "skillbill.review.model.ReviewFinishedTelemetry.reviewContextAccounting",
      "skillbill.workflow.model.GoalObservabilityEvent.toArtifactMap",
      "skillbill.workflow.model.GoalObservabilityEvent.toCompactSummaryMap",
      "skillbill.workflow.model.GoalObservabilityHistory.toArtifactList",
      "skillbill.workflow.model.goalObservabilityLatestEventFromArtifacts",
      "skillbill.workflow.model.goalObservabilityHistoryFromArtifacts",
      "skillbill.goalrunner.model.GoalRunnerStatusProjection.latestObservabilityEvent",
      "skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras.latestObservabilityEvent",
      "skillbill.goalrunner.model.GoalRunnerStatusProjector.project",
      // SKILL-64 subtask 3: declared goal-progress, best-effort session
      // accounting, and append-only attempt-ledger maps are durable
      // workflow-artifact/schema seams written through the goal-runner outcome
      // store adapter and surfaced read-only by MCP goal-observability mapping.
      "skillbill.workflow.model.GoalProgressEvent.toArtifactMap",
      "skillbill.workflow.model.GoalProgressHistory.toArtifactList",
      "skillbill.goalrunner.model.GoalSessionAccounting.toArtifactMap",
      "skillbill.goalrunner.model.GoalSessionAccountingHistory.toArtifactList",
      "skillbill.goalrunner.model.GoalAttemptLedgerEntry.toArtifactMap",
      "skillbill.goalrunner.model.GoalAttemptLedger.toArtifactList",
      // SKILL-64 subtask 3 (F-A01/F-A02): domain-owned declared-progress event
      // validator port (infra-fs adapter bound in DI) plus the shared bounded
      // sequence-ordered retention helper used by the durable goal-runner write
      // seam. Both stay raw-map: the schema validates the wire-map envelope and
      // the retention helper prunes the same artifact-map lists in place.
      "skillbill.workflow.GoalProgressEventValidator.validate",
      "skillbill.workflow.model.appendBoundedHistoryBySequence",
      // Durable artifact-map seams riding inside the family workflow row's artifacts_json.
      "skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator.validateAndReadPhaseOutput",
      // SKILL-137: domain-owned canonical planning-projections schema gate (infra-fs adapter
      // bound in DI). Raw-map because the schema validates the produced_outputs wire map.
      "skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator.validatePlanningProjection",
      "skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput.envelope",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.fromArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint.toEnvelopeMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection.toEnvelopeMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope.toEnvelopeMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope.fromEnvelopeMap",
      "skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap",
      // SKILL-140: durable append-only quarantine evidence store (private, prompt-invisible) and its
      // domain-owned schema validator port (infra-fs adapter bound in DI).
      "skillbill.workflow.FeatureTaskRuntimeQuarantineValidator.validateQuarantineRecord",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry.fromArtifactMap",
      "skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineRecordToWire",
      "skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire",
      "skillbill.workflow.FeatureTaskRuntimeHandoffEnvelopeValidator.validateEnvelope",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.fromArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalPlanningImport.toArtifactMap",
      "skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding.toArtifactMap",
      "skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding.fromArtifactMap",
      "skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult.toArtifactMap",
      "skillbill.workflow.taskruntime.model.GoalSubtaskReviewPassResult.fromArtifactMap",
      "skillbill.workflow.taskruntime.model.GoalSubtaskReviewArtifactDecoder.decode",
      "skillbill.workflow.taskruntime.model.GoalSubtaskReviewState.toArtifactMap",
      "skillbill.workflow.taskruntime.model.GoalSubtaskReviewState.fromArtifactMap",
      "skillbill.ports.workflow.model.GoalSubtaskReviewInput.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap",
      "skillbill.ports.goalrunner.GoalRunnerTerminalOutcomeStore.recoverMissingResultPrefixOutput",
      "skillbill.workflow.taskruntime.model.toArtifactMap",
      "skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap",
      "skillbill.workflow.taskruntime.model.featureTaskRuntimeDecomposePlanOutcomeOrNull",
      "skillbill.workflow.taskruntime.model.featureTaskRuntimeIsDecompositionPackage",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.toArtifactMap",
      "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap",
      "skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing.toArtifactMap",
      "skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap",
      // SKILL-52.2 subtask 2: the 11 scaffold input raw-map allow-list entries — the two public
      // application + port `scaffold(payload, dryRun)` overloads on
      // `skillbill.application.ScaffoldService` / `skillbill.ports.scaffold.ScaffoldGateway`
      // PLUS the 9 raw-map policy helpers under `skillbill.scaffold.policy` (`requireString`,
      // `requireStringOrDefault`, `validatePayloadVersion`, `detectKind`,
      // `optionalSpecialistSubagents`, `rejectLeafSubagentSpecialists`,
      // `rejectBaselineLayersForNonPlatformPack`, `resolvePlatformPackSelection`,
      // `resolvePlatformPackDefaults`) — are RETIRED. The two public overloads now accept a
      // typed `ScaffoldCommandRequest`; CLI / MCP / Desktop adapters parse to the typed model
      // at the adapter boundary. The 9 policy helpers were either inlined into the adapter
      // parsers (CLI / MCP / Desktop) or relocated as `internal` raw-map helpers inside
      // `runtime-infra-fs` (see `runtime-infra-fs/.../scaffold/ScaffoldPayloadMapPolicy.kt`),
      // which the raw-map architecture scanner does not walk.
      // SKILL-52.3 subtask 4: lifecycle telemetry payload helpers and the
      // LifecycleTelemetryService emit methods are accepted permanent open
      // boundaries (forward-compatible MCP/CLI event bags) — now annotated with
      // @OpenBoundaryMap rather than gated for removal.
      "skillbill.application.telemetry.lifecycleOkPayload",
      "skillbill.application.telemetry.lifecycleSkippedPayload",
      "skillbill.application.telemetry.lifecycleErrorPayload",
      "skillbill.application.telemetry.orchestratedStartedSkippedPayload",
      "skillbill.application.telemetry.orchestratedPayload",
      "skillbill.application.telemetry.LifecycleTelemetryService.featureImplementStarted",
      "skillbill.application.telemetry.LifecycleTelemetryService.featureImplementFinished",
      "skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeStarted",
      "skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeFinished",
      "skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckStarted",
      "skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckFinished",
      "skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyStarted",
      "skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyFinished",
      "skillbill.application.telemetry.LifecycleTelemetryService.prDescriptionGenerated",
      "skillbill.application.telemetry.LifecycleTelemetryService.goalStarted",
      "skillbill.application.telemetry.LifecycleTelemetryService.goalSubtaskFinished",
      "skillbill.application.telemetry.LifecycleTelemetryService.goalFinished",
      "skillbill.application.telemetry.LifecycleTelemetryService.goalIssueFinished",
      "skillbill.workflow.WorkflowEngine.continueDecision",
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
      "skillbill.application.model.DecompositionManifestWriteRequest.planningResult",
      "skillbill.application.model.DecompositionManifestRuntimeUpdate.stepUpdates",
      "skillbill.application.model.DecompositionManifestRuntimeUpdate.artifactsPatch",
      "skillbill.application.model.DecompositionManifestRuntimeUpdate.existingArtifacts",
      "skillbill.install.model.buildInstallPlanWireMap",
      "skillbill.scaffold.model.PlatformManifest.customFields",
      "skillbill.telemetry.model.TelemetryConfigDocument.payload",
      "skillbill.telemetry.model.TelemetryProxyCapabilities.additionalFields",
      "skillbill.telemetry.model.TelemetryRemoteStatsResult.metrics",
      "skillbill.telemetry.model.FeatureImplementFinishedRecord.childSteps",
      "skillbill.workflow.model.WorkflowSnapshotView.artifacts",
      "skillbill.workflow.model.WorkflowContinueView.stepArtifacts",
      "skillbill.workflow.model.WorkflowContinueView.extraFields",
      "skillbill.workflow.model.WorkflowContinueView.sessionSummary",
      "skillbill.workflow.model.WorkflowUpdateInput.stepUpdates",
      "skillbill.workflow.model.WorkflowUpdateInput.artifactsPatch",
      "skillbill.ports.validation.model.RepoValidationReport.toPayload",
      "skillbill.ports.validation.model.ReleaseRefMetadata.toPayload",
    )

    // SKILL-52.3 subtask 5 (AC4): runtime-contracts is a pure DTO/constants/
    // exceptions leaf. The schema validators that owned these dependencies
    // moved to runtime-infra-fs in subtask 1, so the contract leaf must carry
    // no JSON-Schema (networknt), no Jackson, and no filesystem (`Files`)
    // coupling. This is an explicit lock, not a migration.
    val contractsForbiddenImports: List<String> =
      listOf(
        "com.networknt.",
        "com.fasterxml.jackson.",
        "java.nio.file.Files",
      )
    val contractsForbiddenSourceReferences: List<String> =
      listOf(
        "com.networknt.",
        "com.fasterxml.jackson.",
        "java.nio.file.Files",
        "Files.",
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
    val processAccessSourceReferences: List<String> =
      listOf(
        "System.getenv",
        "System.getProperty",
      )
    val boundaryFrameworkImports: List<String> =
      listOf(
        "com.github.ajalt.clikt",
        "com.zaxxer.hikari",
        "io.ktor.client",
        "java.net.HttpURLConnection",
        "java.net.URL",
        "java.net.URLConnection",
        "java.net.http",
        "java.sql",
        "javax.sql",
        "okhttp3",
        "org.http4k",
        "org.jooq",
        "org.sqlite",
        "retrofit2",
      )
    val boundaryFrameworkSourceReferences: List<String> =
      listOf(
        "com.github.ajalt.clikt",
        "com.zaxxer.hikari",
        "HttpURLConnection",
        "io.ktor.client",
        "java.net.HttpURLConnection",
        "java.net.URL",
        "java.net.URLConnection",
        "java.net.http",
        "java.sql",
        "javax.sql",
        "okhttp3",
        "org.http4k",
        "org.jooq",
        "org.sqlite",
        "retrofit2",
      )
    val homeExpansionSourceReferences: List<String> =
      listOf(
        "== \"~\"",
        ".startsWith(\"~/\")",
        ".removePrefix(\"~/\")",
      )

    // SKILL-52.3: the pure runtime-domain layer must not embed nondeterministic effects. Random
    // id minting, clock reads, and java.util.logging are all effects that belong in adapters
    // (infra-fs/infra-http) or are supplied by callers. runtime-ports / infra modules legitimately
    // use these, so this ban is scoped to runtime-domain main source only.
    val domainEffectPuritySourceReferences: List<String> =
      listOf(
        "UUID.randomUUID",
        "LocalDate.now",
        "Instant.now",
        "System.currentTimeMillis",
        "System.nanoTime",
        "Clock.system",
        "java.util.logging",
      )
    val packagePattern: Regex = Regex("^package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
    val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
    val portFunctionStartPattern: Regex = Regex("^fun\\s+([A-Za-z0-9_]+)\\s*\\(")
    val portFunctionSignaturePattern: Regex =
      Regex("fun\\s+([A-Za-z0-9_]+)\\s*\\((.*?)\\)\\s*:\\s*([A-Za-z0-9_.<>]+)")
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
