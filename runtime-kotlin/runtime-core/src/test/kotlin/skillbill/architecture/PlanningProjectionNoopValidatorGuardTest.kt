package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SKILL-140 Subtask 3 (AC-004): the Noop planning-projection validator leaves the canonical Draft
 * 2020-12 schema unenforced, so it belongs only in tests asserting the typed Kotlin rules in isolation,
 * incidental prompt/harness wiring, or the deliberate Noop-vs-real parity contrast. Every gate,
 * launch-seam, and schema-behavior assertion runs against the real infra-fs validator (the
 * RealValidator* integration suites and EdgeTest's real-validator cases).
 *
 * This guard enumerates the permitted test consumers with a rationale each. A new test that reaches for
 * the Noop stand-in — the exact regression that let asymmetric-seam bugs ship green — fails the build
 * until it is either switched to the real validator or added here with an explicit justification.
 */
class PlanningProjectionNoopValidatorGuardTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) workingDir.parent else workingDir
    }

  private val noopSymbol = "NoopFeatureTaskRuntimePlanningProjectionValidator"

  // File name -> why this test may leave the canonical schema unenforced.
  private val permittedConsumers: Map<String, String> = mapOf(
    "FeatureTaskRuntimeRunnerTest.kt" to
      "Shared run-loop harness default; runner-behavior tests do not assert schema-projection " +
      "enforcement (covered by the RealValidator* integration suites).",
    "FeatureTaskRuntimePhasePromptComposerTest.kt" to
      "Prompt composition; planning-projection enforcement is incidental to the prompt under test.",
    "GoalPlanningSweepTest.kt" to
      "Goal-planning sweep behavior; planning-projection enforcement is incidental to the sweep.",
    "FeatureTaskRuntimePlanningProjectionEdgeTest.kt" to
      "Deliberately contrasts the Noop stand-in against the real validator at the parse seam.",
    "FeatureTaskRuntimePlanningProjectionModelsTest.kt" to
      "Asserts the typed Kotlin projection-model rules in isolation, where the Noop is the point.",
  )

  @Test
  fun `only the enumerated tests may use the Noop planning-projection validator`() {
    val testRoots = listOf(
      runtimeRoot.resolve("runtime-application/src/test"),
      runtimeRoot.resolve("runtime-domain/src/test"),
      runtimeRoot.resolve("runtime-cli/src/test"),
    )

    val actualConsumers = testRoots
      .filter { Files.isDirectory(it) }
      .flatMap { root ->
        Files.walk(root).use { paths ->
          paths.filter { Files.isRegularFile(it) && it.extension == "kt" }
            .filter { Files.readString(it).contains(noopSymbol) }
            .map { it.name }
            .toList()
        }
      }
      .toSet()

    assertEquals(
      permittedConsumers.keys,
      actualConsumers,
      "The Noop planning-projection validator consumer set drifted from the AC-004 allow-list. A new " +
        "consumer must switch to the real validator (see RealValidator* integration suites) or be added " +
        "to permittedConsumers with an explicit rationale.",
    )
  }
}
