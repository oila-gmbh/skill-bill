package skillbill.architecture

import skillbill.RuntimeModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RuntimeArchitectureDocumentationTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `architecture document declares package ownership and dependency direction`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))

    assertContains(architecture, "runtime-cli / runtime-mcp / runtime-desktop data gateways")
    assertContains(architecture, "-> runtime-application use cases")
    assertContains(architecture, "runtime-core")
    assertContains(architecture, "Package Ownership")
    assertContains(architecture, "Boundary Rules")
    assertContains(architecture, "Architecture Guardrails")
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
    assertContains(architecture, "typed CLI presenter models")
    assertContains(architecture, "RuntimeSurfaceContract")
    assertContains(architecture, "RuntimeContext")
    assertContains(architecture, "skillbill.model")
    assertContains(architecture, "`model` packages")
    assertContains(architecture, "runtime-ports")
    assertContains(architecture, "gradle-module-split-evaluation.md")
    assertContains(architecture, "Raw Map Boundary Rule")
    assertContains(architecture, "Open-Boundary Allow-List")
    assertContains(architecture, "@OpenBoundaryMap")
    assertContains(architecture, "RAW_MAP_OPEN_BOUNDARY_ALLOWLIST")
    assertFalse(architecture.contains("Temporary SKILL-52 blocker"))
    assertFalse(architecture.contains("compatibility umbrella"))
    assertFalse(architecture.contains("while the"))
    assertFalse(architecture.contains("should converge"))
    assertFalse(architecture.contains("Near-Term Refactor Order"))
  }

  @Test
  fun `architecture document declares the runtime contract and schema seams`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))

    assertContains(architecture, CONTRACTS_NO_LONGER_OWNS_VALIDATORS)
    assertContains(architecture, INFRA_FS_OWNS_VALIDATORS)
    assertContains(architecture, "Runtime Contract And Schema Seams")
    assertContains(architecture, SCHEMA_SEAMS_PORT_SUMMARY)
    assertContains(architecture, "Workflow-state schema validation is owned by")
    assertContains(architecture, "compiled into\n  `runtime-infra-fs`")
    assertContains(architecture, "Install-plan schema validation is owned by")
    assertContains(architecture, INSTALL_PLAN_WIRE_VALIDATOR_PORT)
    assertContains(architecture, "Decomposition-manifest schema validation is owned by")
    assertContains(architecture, DECOMPOSITION_MANIFEST_VALIDATOR_PORT)
    assertContains(architecture, "skillbill.ports.workflow.DecompositionManifestFileStore")
    assertContains(architecture, "FileSystemDecompositionManifestFileStore")
    assertContains(architecture, "Platform-pack manifest schema validation is owned by")
    assertContains(architecture, "Native-agent composition schema validation is owned by")
    assertContains(architecture, "Telemetry-event schema validation is owned by")
  }

  @Test
  fun `runtime module declares final package boundaries`() {
    assertEquals(
      setOf(
        "skillbill.application",
        "skillbill.boundary",
        "skillbill.cli",
        "skillbill.contracts",
        "skillbill.db",
        "skillbill.desktop",
        "skillbill.di",
        "skillbill.domain.skillremove",
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
        "skillbill.skillremove",
        "skillbill.telemetry",
        "skillbill.workflow",
        "skillbill.workflow.implement",
        "skillbill.workflow.verify",
      ),
      RuntimeModule.declaredSubsystemPackages.toSet(),
    )
  }

  @Test
  fun `architecture document settings and runtime module declare the same graph`() {
    val architecture = Files.readString(runtimeRoot.resolve("ARCHITECTURE.md"))
    val settings = Files.readString(runtimeRoot.resolve("settings.gradle.kts"))

    assertEquals(
      RuntimeModule.declaredGradleModules,
      architecture.fencedTextListAfter("The Gradle module set is:"),
      "ARCHITECTURE.md Gradle module list must match RuntimeModule.declaredGradleModules.",
    )
    assertEquals(
      RuntimeModule.declaredGradleModules,
      settings.includedGradleModules(),
      "settings.gradle.kts include list must match RuntimeModule.declaredGradleModules.",
    )
    assertEquals(
      RuntimeModule.declaredSubsystemPackages.toSet(),
      architecture.fencedTextListAfter("The subsystem package set is:").toSet(),
      "ARCHITECTURE.md subsystem package list must match RuntimeModule.declaredSubsystemPackages.",
    )
  }

  private fun String.fencedTextListAfter(marker: String): List<String> {
    val markerIndex = indexOf(marker)
    require(markerIndex >= 0) { "Missing marker '$marker'." }
    val fenceStart = indexOf("```text", startIndex = markerIndex)
    require(fenceStart >= 0) { "Missing text fence after '$marker'." }
    val contentStart = indexOf('\n', startIndex = fenceStart) + 1
    val fenceEnd = indexOf("```", startIndex = contentStart)
    require(fenceEnd >= 0) { "Missing closing fence after '$marker'." }
    return substring(contentStart, fenceEnd)
      .lineSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .toList()
  }

  private companion object {
    const val CONTRACTS_NO_LONGER_OWNS_VALIDATORS =
      "It no longer owns the JSON-Schema\n  validators or their schema-resource copy tasks; " +
        "those moved to\n  `runtime-infra-fs`"

    const val INFRA_FS_OWNS_VALIDATORS =
      "It also owns the concrete JSON-Schema\n  validators"

    const val SCHEMA_SEAMS_PORT_SUMMARY =
      "The JVM JSON-Schema validators, their typed schema\n  errors, and their classpath-resource " +
        "copy tasks live in `runtime-infra-fs`,\n  reached only through the domain-neutral ports " +
        "`InstallPlanWireValidator`,\n  `DecompositionManifestValidator`, and `WorkflowSnapshotValidator`."

    const val INSTALL_PLAN_WIRE_VALIDATOR_PORT =
      "reached through the domain-owned port\n  `skillbill.install.model.InstallPlanWireValidator`"

    const val DECOMPOSITION_MANIFEST_VALIDATOR_PORT =
      "reached through the domain-owned port\n  `skillbill.workflow.DecompositionManifestValidator`"
  }

  private fun String.includedGradleModules(): List<String> {
    val includeBlocks = Regex("""include\((?<body>.*?)\)""", RegexOption.DOT_MATCHES_ALL)
      .findAll(this)
      .map { match -> match.groups["body"]?.value.orEmpty() }
      .toList()
    require(includeBlocks.isNotEmpty()) { "settings.gradle.kts must declare at least one include(...) block." }
    return Regex(""""([^"]+)"""")
      .findAll(includeBlocks.joinToString(separator = "\n"))
      .map { it.groupValues[1] }
      .toList()
  }
}
