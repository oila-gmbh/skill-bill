package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 5 — `runtime-core` composition-only no-regression guard.
 *
 * `runtime-core` is the composition module: it wires Kotlin-Inject providers and
 * publishes only the generated ABI edges its public `RuntimeComponent` requires.
 * ARCHITECTURE.md §Gradle Modules locks this contract: `runtime-core` may carry
 * `api(:runtime-application)` and `api(:runtime-ports)` (kotlin-inject needs the
 * generated service/port types to be public), but it MUST NOT grow `api(...)`
 * edges to infrastructure (`runtime-infra-*`) or entrypoint
 * (`runtime-cli`, `runtime-mcp`, `runtime-desktop`) modules.
 *
 * This test pins:
 *  1. The exact `api(project(...))` set on `runtime-core`.
 *  2. The exact `implementation(project(...))` set on `runtime-core` (infra is
 *     allowed as `implementation` only — it is consumed inside composition code
 *     but is not re-exported in the public ABI).
 *  3. No infrastructure or entrypoint module appears as `api(...)`.
 *
 * If kotlin-inject ever requires another generated ABI edge to be public, this
 * test must be updated in lock-step with the ARCHITECTURE.md adapter paragraph.
 */
class RuntimeCoreCompositionOnlyTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `runtime-core api edges are pinned to the SKILL-52_1 baseline`() {
    val source = Files.readString(runtimeRoot.resolve("runtime-core/build.gradle.kts"))
    val apiEdges = projectEdgesForConfiguration(source, "api")
    assertEquals(
      EXPECTED_API_EDGES,
      apiEdges,
      "runtime-core api(project(...)) edges drifted from the composition-only baseline. " +
        "Expected $EXPECTED_API_EDGES but found $apiEdges. " +
        "runtime-core may only re-export the generated kotlin-inject ABI edges that " +
        "RuntimeComponent's public surface requires (runtime-application + runtime-ports). " +
        "If a new edge is genuinely required, update both ARCHITECTURE.md §Gradle Modules and " +
        "this test in the same change.",
    )
  }

  @Test
  fun `runtime-core implementation edges are pinned to the SKILL-52_1 baseline`() {
    val source = Files.readString(runtimeRoot.resolve("runtime-core/build.gradle.kts"))
    val implementationEdges = projectEdgesForConfiguration(source, "implementation")
    assertEquals(
      EXPECTED_IMPLEMENTATION_EDGES,
      implementationEdges,
      "runtime-core implementation(project(...)) edges drifted from the composition-only " +
        "baseline. Expected $EXPECTED_IMPLEMENTATION_EDGES but found $implementationEdges. " +
        "Composition-internal edges are allowed but the set is pinned to keep the boundary " +
        "inventory stable.",
    )
  }

  @Test
  fun `runtime-core does not publish infrastructure or entrypoint modules as api`() {
    val source = Files.readString(runtimeRoot.resolve("runtime-core/build.gradle.kts"))
    val apiEdges = projectEdgesForConfiguration(source, "api")
    val banned =
      apiEdges.filter { edge ->
        edge.startsWith("runtime-infra-") ||
          edge == "runtime-cli" ||
          edge == "runtime-mcp" ||
          edge.startsWith("runtime-desktop")
      }
    assertTrue(
      banned.isEmpty(),
      "runtime-core must not publish infrastructure or entrypoint modules as api(...). " +
        "Offenders: $banned",
    )
  }

  private fun projectEdgesForConfiguration(source: String, configuration: String): Set<String> {
    val edges = mutableSetOf<String>()
    val pattern = Regex(
      """^\s*${Regex.escape(configuration)}\(project\(":([A-Za-z0-9:-]+)"\)\)""",
      RegexOption.MULTILINE,
    )
    pattern.findAll(source).forEach { match -> edges += match.groupValues[1] }
    return edges
  }

  private companion object {
    /**
     * `runtime-application` and `runtime-ports` are `api(...)` because the generated
     * kotlin-inject component types (RuntimeComponent) expose application service and
     * port types as part of their public ABI. Adding more api edges grows that public
     * ABI and is a hard regression.
     */
    val EXPECTED_API_EDGES: Set<String> =
      setOf(
        "runtime-application",
        "runtime-ports",
      )

    /**
     * `runtime-domain`, `runtime-contracts`, and the three `runtime-infra-*` modules
     * are `implementation(...)` — composition references them inside the kotlin-inject
     * providers, but they are not re-exported as public ABI.
     */
    val EXPECTED_IMPLEMENTATION_EDGES: Set<String> =
      setOf(
        "runtime-domain",
        "runtime-contracts",
        "runtime-infra-fs",
        "runtime-infra-http",
        "runtime-infra-sqlite",
      )
  }
}
