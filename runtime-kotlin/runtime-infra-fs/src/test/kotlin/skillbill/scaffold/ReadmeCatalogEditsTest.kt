package skillbill.scaffold

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import skillbill.scaffold.platformpack.ReadmeCatalogEdits
import skillbill.scaffold.platformpack.ReadmeEditOutcome
import java.nio.file.Path

class ReadmeCatalogEditsTest {
  @Test
  fun `removeCatalogRow removes table row and reports Applied`(@TempDir tempDir: Path) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText(
      """
      # Header

      ### Canonical Skills (3 skills)

      | Skill | Purpose |
      |-------|---------|
      | `/bill-foo` | Foo |
      | `/bill-bar` | Bar |
      | `/bill-baz` | Baz |

      Other content.
      """.trimIndent(),
    )
    val outcome = ReadmeCatalogEdits.removeCatalogRow(readme, "bill-bar")
    assertEquals(ReadmeEditOutcome.Applied, outcome)
    val updated = readme.toFile().readText()
    assertFalse(updated.contains("`/bill-bar`"))
    assertTrue(updated.contains("`/bill-foo`"))
  }

  @Test
  fun `removeCatalogRow reports LandmarksMissing when row absent`(@TempDir tempDir: Path) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText("# Header\nNo table here.\n")
    val outcome = ReadmeCatalogEdits.removeCatalogRow(readme, "bill-foo")
    assertTrue(outcome is ReadmeEditOutcome.LandmarksMissing)
  }

  @Test
  fun `decrementSectionCount decrements and uses singular when N=1`(@TempDir tempDir: Path) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText("### Canonical Skills (2 skills)\n")
    assertEquals(ReadmeEditOutcome.Applied, ReadmeCatalogEdits.decrementSectionCount(readme))
    assertTrue(readme.toFile().readText().contains("(1 skill)"))
  }

  @Test
  fun `decrementSectionCount reports LandmarksMissing when heading absent`(@TempDir tempDir: Path) {
    val readme = tempDir.resolve("README.md")
    readme.toFile().writeText("nothing here\n")
    val outcome = ReadmeCatalogEdits.decrementSectionCount(readme)
    assertTrue(outcome is ReadmeEditOutcome.LandmarksMissing)
  }
}
