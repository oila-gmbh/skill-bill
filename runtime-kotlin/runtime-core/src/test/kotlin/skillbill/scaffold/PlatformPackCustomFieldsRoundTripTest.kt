package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-48 Subtask 3 A3 + A5(a): a `platform.yaml` may carry fork-specific
 * top-level keys that the runtime does not consume by name. They MUST:
 *
 *  1. pass schema validation (the top-level `additionalProperties` is now `true`),
 *  2. reach `PlatformManifest.customFields` verbatim,
 *  3. not appear under the typed fields the runtime owns.
 *
 * Pinned through `loadPlatformManifest` because that is the seam used by all
 * real callers (CLI, desktop, validators).
 */
class PlatformPackCustomFieldsRoundTripTest {

  @Test
  fun `non-anchored top-level fields surface verbatim through customFields`() {
    val slug = "scenarioslug"
    val manifest = """
      platform: $slug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      custom_thing:
        a: 1
        b:
          - "x"
          - "y"
      another_custom: "hello"
    """.trimIndent()

    val packRoot = newTempPackRoot(slug, manifest)
    val pack = loadPlatformManifest(packRoot)

    // (1) Custom fields surfaced verbatim.
    assertTrue(
      "custom_thing" in pack.customFields,
      "Non-anchored top-level field 'custom_thing' is missing from PlatformManifest.customFields. " +
        "Keys present: ${pack.customFields.keys}",
    )
    assertTrue(
      "another_custom" in pack.customFields,
      "Non-anchored top-level field 'another_custom' is missing from PlatformManifest.customFields. " +
        "Keys present: ${pack.customFields.keys}",
    )
    assertEquals("hello", pack.customFields["another_custom"])

    @Suppress("UNCHECKED_CAST")
    val nested = pack.customFields["custom_thing"] as? Map<String, Any?>
      ?: error("Expected 'custom_thing' to deserialize to a Map but got ${pack.customFields["custom_thing"]}")
    assertEquals(1, nested["a"])
    assertEquals(listOf("x", "y"), nested["b"])

    // (2) Anchored fields MUST NOT appear in customFields.
    val anchored = anchoredTopLevelFieldNames()
    val leakedAnchored = pack.customFields.keys.intersect(anchored)
    assertTrue(
      leakedAnchored.isEmpty(),
      "Anchored top-level fields leaked into customFields: $leakedAnchored. " +
        "ShellContentLoader.buildPack must filter the anchored set out.",
    )
  }

  @Test
  fun `pack with no custom fields produces empty customFields map`() {
    // Pin the boring path: a stock manifest yields an empty `customFields`. This guards against
    // a future regression that accidentally includes anchored fields in `customFields`.
    val slug = "scenarioslug"
    val manifest = """
      platform: $slug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
    """.trimIndent()

    val packRoot = newTempPackRoot(slug, manifest)
    val pack = loadPlatformManifest(packRoot)

    assertTrue(
      pack.customFields.isEmpty(),
      "Manifest with no fork-specific keys must produce empty customFields; got ${pack.customFields.keys}.",
    )
    // Belt-and-suspenders: no anchored key snuck in.
    val anchored = anchoredTopLevelFieldNames()
    assertFalse(pack.customFields.keys.any { it in anchored })
  }

  private fun newTempPackRoot(slug: String, manifest: String): Path {
    val tempDir = Files.createTempDirectory("skillbill-platform-pack-customfields-test-")
    val packRoot = tempDir.resolve(slug)
    Files.createDirectories(packRoot)
    Files.writeString(packRoot.resolve("platform.yaml"), manifest)
    return packRoot
  }
}
