package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import skillbill.architecture.RuntimeImplementationImportRules.jdbcSqliteConnectionSitesOutsideDatabaseRuntime

class RuntimeArchitectureTest {
  private val runtimeRoot = runtimeArchitectureRoot
  private val sourceRoots = runtimeArchitectureSourceRoots
  private val mcpScaffoldRuntime = mcpScaffoldRuntimePath

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
        "skillbill.infrastructure",
        "skillbill.mcp",
      ),
    )
  }

@Test
  fun `runtime schema validators and schema resources are owned by runtime infra-fs`() {
    assertRegularFiles(
      listOf(
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaValidator.kt",
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaValidator.kt",
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaValidator.kt",
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestCoherenceValidator.kt",
        "runtime-infra-fs/src/main/kotlin/skillbill/contracts/workflow/IdeStatusSchemaValidator.kt",
      ),
      present = true,
    )
    assertRegularFiles(
      listOf(
        "runtime-contracts/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaPaths.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaPaths.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaPaths.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/IdeStatusSchemaPaths.kt",
      ),
      present = true,
    )
    assertRegularFiles(
      listOf(
        "runtime-contracts/src/main/kotlin/skillbill/contracts/install/InstallPlanSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/WorkflowStateSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestSchemaValidator.kt",
        "runtime-contracts/src/main/kotlin/skillbill/contracts/workflow/DecompositionManifestCoherenceValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/DecompositionManifestSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/DecompositionManifestSchemaPaths.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowStateSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/workflow/WorkflowStateSchemaPaths.kt",
        "runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanSchemaValidator.kt",
        "runtime-domain/src/main/kotlin/skillbill/install/model/InstallPlanSchemaPaths.kt",
      ),
      present = false,
    )

    val runtimeInfraFsBuild = Files.readString(runtimeArchitectureRoot.resolve("runtime-infra-fs/build.gradle.kts"))
    assertContains(runtimeInfraFsBuild, "copyWorkflowStateSchema")
    assertContains(runtimeInfraFsBuild, "copyInstallPlanSchema")
    assertContains(runtimeInfraFsBuild, "copyDecompositionManifestSchema")
    assertContains(runtimeInfraFsBuild, "copyIdeStatusSchema")

    val runtimeContractsBuild = Files.readString(runtimeArchitectureRoot.resolve("runtime-contracts/build.gradle.kts"))
    assertTrue(
      "copyWorkflowStateSchema" !in runtimeContractsBuild &&
        "copyInstallPlanSchema" !in runtimeContractsBuild &&
        "copyDecompositionManifestSchema" !in runtimeContractsBuild &&
        "copyIdeStatusSchema" !in runtimeContractsBuild,
      "runtime-contracts must no longer own runtime schema copy tasks.",
    )

    val runtimeDomainBuild = Files.readString(runtimeArchitectureRoot.resolve("runtime-domain/build.gradle.kts"))
    assertTrue(
      "copyWorkflowStateSchema" !in runtimeDomainBuild &&
        "copyInstallPlanSchema" !in runtimeDomainBuild &&
        "copyDecompositionManifestSchema" !in runtimeDomainBuild &&
        "copyIdeStatusSchema" !in runtimeDomainBuild,
      "runtime-domain must not own runtime contract schema copy tasks.",
    )
  }

@Test
  fun `runtime contracts main source is free of networknt jackson and nio files`() {
    val contractsFiles =
      sourceFiles().filter { file -> file.relativePath.startsWith("runtime-contracts/src/main/kotlin/") }
    assertTrue(
      contractsFiles.isNotEmpty(),
      "runtime-contracts main source must exist for the purity lock to be meaningful.",
    )
    assertNoBannedImports(
      files = contractsFiles,
      bannedImports = RuntimeArchitectureScanConstants.contractsForbiddenImports,
    )
    assertNoBannedSourceReferences(
      files = contractsFiles,
      bannedReferences = RuntimeArchitectureScanConstants.contractsForbiddenSourceReferences,
      description = "runtime-contracts infrastructure-coupling violation",
    )
  }

@Test
  fun `runtime contracts purity scanner fires on synthetic fixtures`() {
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
    assertEquals(
      listOf(
        "com.networknt.schema.JsonSchemaFactory",
        "com.fasterxml.jackson.databind.ObjectMapper",
        "java.nio.file.Files",
      ),
      fixture.imports,
      "Production RuntimeArchitectureScanConstants.importPattern must parse the fixture's three forbidden imports from source.",
    )
    assertFailsWith<AssertionError>(
      "assertNoBannedImports must THROW on the contracts fixture; otherwise the runtime-contracts " +
        "import purity lock is not actually exercised.",
    ) {
      assertNoBannedImports(files = listOf(fixture), bannedImports = RuntimeArchitectureScanConstants.contractsForbiddenImports)
    }
    val sourceViolations = RuntimeArchitectureScanConstants.contractsForbiddenSourceReferences
      .filter { reference -> fixture.source.lines().any { line -> line.containsBannedReference(reference) } }
    assertEquals(
      RuntimeArchitectureScanConstants.contractsForbiddenSourceReferences,
      sourceViolations,
      "Contracts purity source scanner must report each banned reference (incl. the `Files.` call site).",
    )
  }

@Test
  fun `runtime contracts purity scanner does not flag benign Files-like tokens`() {
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
      cleanFixture.imports.filter { importedName -> RuntimeArchitectureScanConstants.contractsForbiddenImports.any(importedName::startsWith) },
      "Clean fixture must declare no forbidden imports.",
    )
    val cleanSourceViolations = cleanFixture.source.lines().flatMap { line ->
      RuntimeArchitectureScanConstants.contractsForbiddenSourceReferences.filter { reference -> line.containsBannedReference(reference) }
    }
    assertEquals(
      emptyList(),
      cleanSourceViolations,
      "Source scanner must NOT flag benign `Files`-like identifiers (`profileFiles`, `ProfileFiles`) that " +
        "are not the banned `java.nio.file.Files` / `Files.` call site.",
    )
  }

@Test
  fun `runtime domain workflow source must not import contract schema validators or contract mappers`() {
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
    val architecture = Files.readString(runtimeArchitectureRoot.resolve("ARCHITECTURE.md"))
    val projectionIo = Files.readString(
      sourcePath("skillbill/application/decomposition/DecompositionManifestFileWrites.kt"),
    )

    assertContains(architecture, "Decomposition-manifest schema validation is owned by")
    assertContains(architecture, "skillbill.application.decomposition.DecompositionManifestFileWrites")
    assertContains(architecture, "skillbill.ports.workflow.decomposition.DecompositionManifestFileStore")
    assertContains(architecture, "FileSystemDecompositionManifestFileStore")
    assertContains(projectionIo, "Decomposition manifest parse/emission seam")
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
        sourcePath("skillbill/ports/telemetry/TelemetryOutboxRepository.kt"),
      )
    portFiles.forEach { path ->
      assertTrue(Files.exists(path), "Missing telemetry port: ${runtimeArchitectureRoot.relativize(path)}")
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
    val evaluation = Files.readString(runtimeArchitectureRoot.resolve("docs/architecture/gradle-module-split-evaluation.md"))

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
    assertContains(evaluation, "runtime-mcp")
    assertContains(evaluation, "RuntimeContext")
    assertContains(evaluation, "Resolved Split Blockers")
    assertContains(evaluation, "No known package-level upward dependencies remain")
    assertContains(evaluation, "Deeper Split Readiness Criteria")
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
    val reconciliationSources = sourceFiles().filter { file ->
      file.relativePath.endsWith("featuretask/FeatureTaskRuntimeCrashReconciler.kt") ||
        file.relativePath.endsWith("featuretask/FeatureTaskRuntimeWorkerCoordinator.kt") ||
        file.relativePath.endsWith("goalrunner/WorkflowGoalRunnerOutcomeStore.kt")
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

@Test
  fun `every main source package is declared under an owned subsystem`() {
    val ownershipPrefixes = RuntimeModuleCatalog.declaredSubsystemPackages.sortedByDescending(String::length)
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
      "Every real main-source package must be owned by RuntimeModuleCatalog.declaredSubsystemPackages.",
    )
  }

@Test
  fun `inner layer test sources do not import adapters or infrastructure packages`() {
    val forbiddenPrefixes = listOf(
      "skillbill.infrastructure.",
      "skillbill.cli.",
      "skillbill.mcp.",
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
}
