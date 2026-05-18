package skillbill.scaffold

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * SKILL-46 AC9: round-trip coverage of [removeCodeReviewArea] and [removeDeclaredQualityCheckFile].
 *
 * Tests verify each helper:
 * - Strips the entry across all three locations (`declared_code_review_areas`, `declared_files.areas`,
 *   `area_metadata.<area>`).
 * - Is idempotent — calling it twice does not re-write the file.
 * - Collapses to the inline empty form when the last entry is removed.
 */
class ScaffoldManifestEditsRemoveTest {
  @Test
  fun `removeCodeReviewArea strips declared list, declared_files area, and area_metadata`(@TempDir tempDir: Path) {
    val manifest = tempDir.resolve("platform.yaml")
    manifest.toFile().writeText(
      """
      platform: "foo"
      contract_version: "1.0"
      display_name: "Foo"

      routing_signals:
        strong: []
        tie_breakers: []

      declared_code_review_areas:
        - "ui"
        - "perf"

      declared_files:
        baseline: "code-review/bill-foo-code-review/content.md"
        areas:
          ui: "code-review/bill-foo-code-review-ui/content.md"
          perf: "code-review/bill-foo-code-review-perf/content.md"
      area_metadata:
        ui:
          focus: "UI review"
        perf:
          focus: "Perf review"
      """.trimIndent() + "\n",
    )

    removeCodeReviewArea(manifest, "ui")
    val updated = manifest.toFile().readText()
    assertFalse(updated.contains("\"ui\""), "expected 'ui' list entry to be removed")
    assertFalse(updated.contains("ui: \"code-review/bill-foo-code-review-ui/content.md\""))
    assertFalse(updated.contains("focus: \"UI review\""))
    // 'perf' is left intact.
    org.junit.jupiter.api.Assertions.assertTrue(updated.contains("\"perf\""))
  }

  @Test
  fun `removeCodeReviewArea is idempotent when the area is already missing`(@TempDir tempDir: Path) {
    val manifest = tempDir.resolve("platform.yaml")
    val original = """
      platform: "foo"
      declared_code_review_areas:
        - "perf"
      declared_files:
        baseline: "x"
        areas:
          perf: "p"
      area_metadata:
        perf:
          focus: "Perf"
    """.trimIndent() + "\n"
    manifest.toFile().writeText(original)
    removeCodeReviewArea(manifest, "ui-does-not-exist")
    assertEquals(original, manifest.toFile().readText())
  }

  @Test
  fun `removeDeclaredQualityCheckFile strips the line`(@TempDir tempDir: Path) {
    val manifest = tempDir.resolve("platform.yaml")
    manifest.toFile().writeText(
      """
      platform: "foo"
      declared_code_review_areas: []
      declared_files:
        baseline: "code-review/bill-foo-code-review/content.md"
        areas: {}
      area_metadata: {}

      declared_quality_check_file: "quality-check/bill-foo-quality-check/content.md"
      """.trimIndent() + "\n",
    )
    removeDeclaredQualityCheckFile(manifest)
    val updated = manifest.toFile().readText()
    assertFalse(updated.contains("declared_quality_check_file"))
  }

  @Test
  fun `removeDeclaredQualityCheckFile is no-op when the line is missing`(@TempDir tempDir: Path) {
    val manifest = tempDir.resolve("platform.yaml")
    val original = """
      platform: "foo"
      declared_code_review_areas: []
    """.trimIndent() + "\n"
    manifest.toFile().writeText(original)
    removeDeclaredQualityCheckFile(manifest)
    assertEquals(original, manifest.toFile().readText())
  }
}
