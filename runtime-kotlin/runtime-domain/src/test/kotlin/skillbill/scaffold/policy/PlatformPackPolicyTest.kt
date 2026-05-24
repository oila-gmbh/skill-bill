package skillbill.scaffold.policy

import skillbill.error.InvalidScaffoldPayloadError
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformPackPolicyTest {
  @Test
  fun `resolvePlatformPackSelection returns all approved areas when full skeleton is chosen`() {
    val selection = resolvePlatformPackSelection(mapOf("skeleton_mode" to "full"))

    assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), selection.selectedAreas)
  }

  @Test
  fun `resolvePlatformPackSelection returns empty selection for starter skeleton`() {
    val selection = resolvePlatformPackSelection(mapOf("skeleton_mode" to "starter"))

    assertEquals(emptyList(), selection.selectedAreas)
  }

  @Test
  fun `resolvePlatformPackSelection filters specialist_areas to approved subset`() {
    val selection = resolvePlatformPackSelection(
      mapOf("specialist_areas" to listOf("ui", "security")),
    )

    assertEquals(listOf("security", "ui"), selection.selectedAreas)
  }

  @Test
  fun `resolvePlatformPackSelection rejects out-of-range skeleton_mode`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackSelection(mapOf("skeleton_mode" to "starter-full"))
    }
  }

  @Test
  fun `resolvePlatformPackSelection rejects payloads providing both skeleton_mode and specialist_areas`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackSelection(
        mapOf(
          "skeleton_mode" to "full",
          "specialist_areas" to listOf("ui"),
        ),
      )
    }
  }

  @Test
  fun `resolvePlatformPackSelection rejects unknown specialist areas`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackSelection(
        mapOf("specialist_areas" to listOf("not-an-approved-area")),
      )
    }
  }

  @Test
  fun `resolvePlatformPackDefaults resolves java preset defaults`() {
    val defaults = resolvePlatformPackDefaults(emptyMap(), "java")

    assertEquals("Java", defaults.displayName)
    assertEquals(true, defaults.presetUsed)
    assertEquals(true, defaults.strongSignals.isNotEmpty())
  }

  @Test
  fun `resolvePlatformPackDefaults merges payload routing overrides with preset defaults`() {
    val defaults = resolvePlatformPackDefaults(
      mapOf(
        "routing_signals" to mapOf(
          "strong" to listOf("custom-marker"),
        ),
      ),
      "java",
    )

    assertEquals(listOf("custom-marker"), defaults.strongSignals)
    assertEquals(false, defaults.presetUsed)
  }

  @Test
  fun `resolvePlatformPackDefaults loud-fails when no preset and no routing signals are supplied`() {
    assertFailsWith<InvalidScaffoldPayloadError> {
      resolvePlatformPackDefaults(emptyMap(), "no-such-preset")
    }
  }

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
