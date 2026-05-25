package skillbill.scaffold.policy

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-52.2 subtask 2 (Task 11): coverage for `resolvePlatformPackSelection` and
 * `resolvePlatformPackDefaults` moved to
 * `runtime-infra-fs/src/test/kotlin/skillbill/scaffold/ScaffoldPayloadMapPolicyTest.kt` because
 * those entry points now live as `internal` raw-map helpers inside `runtime-infra-fs`. The
 * helpers that remain in this file (`buildPlatformPackInstallPaths`, `platformPackNotes`) take
 * typed inputs and continue to be tested here.
 */
class PlatformPackPolicyTest {
  @Test
  fun `buildPlatformPackInstallPaths includes baseline, quality-check, and selected specialists`() {
    val packRoot = Path.of("/repo/platform-packs/java")
    val specialistPaths = mapOf(
      "ui" to packRoot.resolve("code-review").resolve("bill-java-code-review-ui"),
    )

    val paths = buildPlatformPackInstallPaths(
      packRoot = packRoot,
      baselineName = "bill-java-code-review",
      qualityCheckName = "bill-java-quality-check",
      specialistPaths = specialistPaths,
      selectedAreas = listOf("ui"),
    )

    assertEquals(3, paths.size)
    assertEquals(packRoot.resolve("code-review").resolve("bill-java-code-review"), paths[0])
    assertEquals(packRoot.resolve("quality-check").resolve("bill-java-quality-check"), paths[1])
    assertEquals(specialistPaths.getValue("ui"), paths[2])
  }

  @Test
  fun `platformPackNotes mentions preset when applied and includes shared contract note`() {
    val notes = platformPackNotes(
      platform = "java",
      presetUsed = true,
      selectedAreas = listOf("ui", "security"),
    )

    assertTrue(notes.any { it.contains("built-in platform preset for 'java'") })
    assertTrue(notes.any { it.contains("Full skeleton scaffolded with 2 approved code-review area stubs") })
    assertTrue(notes.contains(sharedContractNote()))
  }

  @Test
  fun `platformPackNotes reports quality-check fallback when no specialists are selected`() {
    val notes = platformPackNotes(
      platform = "custom",
      presetUsed = false,
      selectedAreas = emptyList(),
    )

    assertTrue(notes.any { it.contains("Quality-check scaffolded by default.") })
    assertTrue(notes.none { it.contains("built-in platform preset") })
  }
}
