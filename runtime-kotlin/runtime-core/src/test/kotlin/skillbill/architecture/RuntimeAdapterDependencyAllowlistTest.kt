package skillbill.architecture

import skillbill.RuntimeModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 5 — Adapter Gradle dependency allow-lists.
 *
 * Each entrypoint adapter (CLI / MCP / desktop data gateway / desktop feature shell)
 * MUST declare exactly the runtime project dependencies it actually concretely imports.
 * Carrying upstream modules transitively through `runtime-application` /
 * `runtime-core` keeps compilation green but obscures the boundary closure inventory
 * that SKILL-52.1 baselined. This test parses each adapter's `build.gradle.kts` and
 * fails when an adapter's `project(":runtime-...")` set drifts from the curated
 * allow-list in [AdapterAllowlists].
 *
 * Adding a new project edge to an adapter requires:
 *   1. Update [AdapterAllowlists] with the new edge.
 *   2. Update the adapter paragraph in `runtime-kotlin/ARCHITECTURE.md`.
 *   3. Document the reason (architectural need, retained exception) in
 *      `runtime-kotlin/agent/decisions.md`.
 *
 * NOTE: this test only covers source-set `implementation`/`api` `project(...)` edges
 * for the four SKILL-52.2-tracked adapters. Test-only edges
 * (`testImplementation(project(...))`) are intentionally excluded — adapter test
 * code already crosses module boundaries for fixtures.
 */
class RuntimeAdapterDependencyAllowlistTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `runtime-cli declares only the curated set of runtime project dependencies`() {
    assertAdapterProjectDependencies(
      moduleName = "runtime-cli",
      expected = AdapterAllowlists.RUNTIME_CLI,
    )
  }

  @Test
  fun `runtime-mcp declares only the curated set of runtime project dependencies`() {
    assertAdapterProjectDependencies(
      moduleName = "runtime-mcp",
      expected = AdapterAllowlists.RUNTIME_MCP,
    )
  }

  @Test
  fun `runtime-desktop core data declares only the curated set of runtime project dependencies`() {
    assertAdapterProjectDependencies(
      moduleName = "runtime-desktop:core:data",
      expected = AdapterAllowlists.RUNTIME_DESKTOP_CORE_DATA,
    )
  }

  @Test
  fun `runtime-desktop feature skillbill declares only the curated set of runtime project dependencies`() {
    assertAdapterProjectDependencies(
      moduleName = "runtime-desktop:feature:skillbill",
      expected = AdapterAllowlists.RUNTIME_DESKTOP_FEATURE_SKILLBILL,
    )
  }

  @Test
  fun `adapter allow-lists only reference declared gradle modules`() {
    val declared = RuntimeModule.declaredGradleModules.toSet()
    val all =
      AdapterAllowlists.RUNTIME_CLI +
        AdapterAllowlists.RUNTIME_MCP +
        AdapterAllowlists.RUNTIME_DESKTOP_CORE_DATA +
        AdapterAllowlists.RUNTIME_DESKTOP_FEATURE_SKILLBILL
    val undeclared = all.toSet() - declared
    assertTrue(
      undeclared.isEmpty(),
      "RuntimeAdapterDependencyAllowlistTest references modules that are not in " +
        "RuntimeModule.declaredGradleModules: ${undeclared.joinToString()}",
    )
  }

  private fun assertAdapterProjectDependencies(moduleName: String, expected: Set<String>) {
    val actual = mainProjectDependencies(moduleName)
    val missing = expected - actual
    val extra = actual - expected
    val message = buildString {
      append("Adapter '")
      append(moduleName)
      append("' main-source project dependencies drift from the curated allow-list.")
      if (missing.isNotEmpty()) {
        append("\n  Missing (declared in allow-list but absent in build.gradle.kts): ")
        append(missing.sorted().joinToString())
      }
      if (extra.isNotEmpty()) {
        append("\n  Extra (present in build.gradle.kts but not in allow-list): ")
        append(extra.sorted().joinToString())
      }
      append(
        "\n  Either narrow the build script to match the allow-list, or, " +
          "if a new edge is genuinely required, update " +
          "RuntimeAdapterDependencyAllowlistTest.AdapterAllowlists AND the matching " +
          "ARCHITECTURE.md adapter paragraph AND record the rationale in " +
          "runtime-kotlin/agent/decisions.md.",
      )
    }
    assertEquals(expected, actual, message)
  }

  /**
   * Parse the main-source `project(":runtime-...")` edges from an adapter's
   * `build.gradle.kts`, excluding test-only declarations.
   *
   * Two flavours of test-only declarations are skipped:
   *  - Per-configuration declarations: any line containing one of
   *    `testImplementation` / `testRuntimeOnly` / `testCompileOnly` /
   *    `androidTestImplementation` / `jvmTestImplementation`.
   *  - KMP DSL test-source-set blocks: any line that lives inside a
   *    `jvmTest.dependencies { ... }` / `androidTest.dependencies { ... }` /
   *    `commonTest.dependencies { ... }` block, where bare
   *    `implementation(project(...))` is implicitly test-only.
   *
   * Brace tracking is line-scoped; the parser tracks the nearest enclosing
   * source-set block so the KMP DSL is handled correctly.
   */
  private fun mainProjectDependencies(moduleName: String): Set<String> {
    val modulePath = moduleName.replace(':', '/')
    val buildFile = runtimeRoot.resolve("$modulePath/build.gradle.kts")
    val source = Files.readString(buildFile)
    val testConfigurations =
      listOf(
        "testImplementation",
        "testRuntimeOnly",
        "testCompileOnly",
        "androidTestImplementation",
        "jvmTestImplementation",
      )
    val testBlockOpen =
      Regex("^\\s*(jvmTest|androidTest|commonTest)\\.dependencies\\s*\\{")
    val projectDependencies = mutableSetOf<String>()
    var depth = 0
    var testBlockDepth = -1
    source.lineSequence().forEach { rawLine ->
      val line = rawLine
      val openMatch = testBlockOpen.find(line)
      if (openMatch != null && testBlockDepth < 0) {
        testBlockDepth = depth
      }
      val inTestBlock = testBlockDepth in 0..depth
      val isTestConfig = testConfigurations.any { configName -> line.contains(configName) }
      if (!inTestBlock && !isTestConfig) {
        Regex("project\\(\":([A-Za-z0-9:-]+)\"\\)")
          .findAll(line)
          .forEach { match -> projectDependencies += match.groupValues[1] }
      }
      depth += line.count { it == '{' }
      depth -= line.count { it == '}' }
      if (depth <= testBlockDepth) {
        testBlockDepth = -1
      }
    }
    return projectDependencies
  }

  private object AdapterAllowlists {
    /**
     * `runtime-cli` concretely imports:
     *  - `skillbill.application.*` + `skillbill.application.model.*` → runtime-application
     *  - `skillbill.contracts.*` + `skillbill.error.*` → runtime-contracts
     *  - `skillbill.di.*` + `skillbill.RuntimeModule` → runtime-core
     *  - `skillbill.domain.skillremove.*`, `skillbill.install.model.*`,
     *    `skillbill.learnings.model.*`, `skillbill.scaffold.model.*`,
     *    `skillbill.telemetry.model.*`, `skillbill.workflow.WorkflowEngine`
     *    → runtime-domain
     *  - `skillbill.ports.*`, `skillbill.model.RuntimeContext` → runtime-ports
     *
     * Concretely **dropped** vs the SKILL-52.1 baseline:
     *  - `runtime-infra-fs`: no `skillbill.infrastructure.fs.*` imports outside tests.
     *  - `runtime-infra-http`: no `skillbill.infrastructure.http.*` imports outside tests.
     */
    val RUNTIME_CLI: Set<String> =
      setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-core",
        "runtime-domain",
        "runtime-ports",
      )

    /**
     * `runtime-mcp` concretely imports:
     *  - `skillbill.application.*` + `skillbill.application.model.*` → runtime-application
     *  - `skillbill.contracts.*` + `skillbill.error.*` → runtime-contracts
     *  - `skillbill.di.*` → runtime-core
     *  - `skillbill.scaffold.model.command.*`, `skillbill.telemetry.model.*`,
     *    `skillbill.workflow.WorkflowEngine` → runtime-domain
     *  - `skillbill.ports.telemetry.*`, `skillbill.ports.workflow.*`,
     *    `skillbill.model.RuntimeContext` → runtime-ports
     *
     * Concretely **dropped** vs the SKILL-52.1 baseline:
     *  - `runtime-infra-fs`: no `skillbill.infrastructure.fs.*` imports outside tests.
     *  - `runtime-infra-http`: no `skillbill.infrastructure.http.*` imports outside tests.
     */
    val RUNTIME_MCP: Set<String> =
      setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-core",
        "runtime-domain",
        "runtime-ports",
      )

    /**
     * `runtime-desktop:core:data` concretely imports (jvmMain + commonMain combined):
     *  - `skillbill.application.*` (jvmMain) → runtime-application
     *  - `skillbill.error.*` (jvmMain) → runtime-contracts (the four error types
     *    `InvalidScaffoldPayloadError`, `MissingInstallSelectionRecordError`,
     *    `ScaffoldRollbackError`, `SkillBillRuntimeException` are all owned by
     *    `runtime-contracts`).
     *  - `skillbill.di.*` (jvmMain) → runtime-core
     *  - `skillbill.domain.skillremove.*`, `skillbill.install.model.*`,
     *    `skillbill.scaffold.model.*` (jvmMain) → runtime-domain
     *  - `skillbill.model.RuntimeContext`, `skillbill.ports.*` (jvmMain) → runtime-ports
     *  - desktop-internal modules for commonMain: core:common, core:database, core:domain.
     *
     * Concretely **dropped** vs the SKILL-52.1 baseline:
     *  - `runtime-infra-fs`: no `skillbill.infrastructure.fs.*` imports outside
     *    jvmTest. The desktop runtime composition resolves filesystem adapters
     *    through `RuntimeComponent` (kotlin-inject), not by directly importing
     *    `runtime-infra-fs` symbols.
     */
    val RUNTIME_DESKTOP_CORE_DATA: Set<String> =
      setOf(
        "runtime-application",
        "runtime-contracts",
        "runtime-core",
        "runtime-domain",
        "runtime-ports",
        "runtime-desktop:core:common",
        "runtime-desktop:core:database",
        "runtime-desktop:core:domain",
      )

    /**
     * `runtime-desktop:feature:skillbill` concretely imports only desktop modules
     * (`skillbill.desktop.core.*` + `skillbill.desktop.feature.*`). It declares no
     * upstream runtime-application / runtime-domain / runtime-ports / runtime-contracts
     * dependencies in main source sets — the data gateway crosses the runtime boundary,
     * and the feature module talks to the gateway through desktop-domain port types.
     */
    val RUNTIME_DESKTOP_FEATURE_SKILLBILL: Set<String> =
      setOf(
        "runtime-desktop:core:common",
        "runtime-desktop:core:designsystem",
        "runtime-desktop:core:datastore",
        "runtime-desktop:core:domain",
        "runtime-desktop:core:ui",
      )
  }
}
