package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicensePolicyDocumentationTest {
  private val repoRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
    .first { Files.isRegularFile(it.resolve("LICENSE")) }

  @Test
  fun `license and public documentation share the governing pre one matrix`() {
    val license = read("LICENSE")
    val publicFiles = listOf(
      "README.md",
      "CONTRIBUTING.md",
      "RELEASING.md",
      "docs/team-control-plane-roadmap.md",
      "docs/licensing.md",
      "docs/release-license-approval.md",
    ).associateWith(::read)

    assertTrue(license.contains("LicenseRef-Skill-Bill-Pre-1.0-Use-1.0"))
    assertTrue(license.contains("Prospective Effective Version: v0.1.2"))
    assertTrue(license.contains("first public, non-draft, non-prerelease"))
    assertTrue(license.contains("oila-gmbh/skill-bill"))
    assertTrue(license.contains("does not undo the event"))
    assertTrue(license.contains("commercial-use permission"))
    assertTrue(license.contains("Open Source Contribution Use"))
    assertTrue(license.contains("GitHub's platform-limited rights"))
    assertTrue(license.contains("Third-Party Software"))
    assertFalse(license.contains("PolyForm Noncommercial License"))

    publicFiles.forEach { (path, text) ->
      assertTrue(text.contains("LICENSE"), "$path must link to the governing LICENSE")
    }
    assertTrue(publicFiles.getValue("README.md").contains("LicenseRef-Skill-Bill-Pre-1.0-Use-1.0"))
    assertTrue(publicFiles.getValue("docs/licensing.md").contains("v0.1.0 and v0.1.1"))
    assertTrue(publicFiles.getValue("docs/licensing.md").contains("v0.1.2"))
    assertTrue(publicFiles.getValue("docs/licensing.md").contains("unmodified lawful use"))
    assertTrue(publicFiles.getValue("docs/licensing.md").contains("never grants a right to modify"))
    assertFalse(publicFiles.values.joinToString("\n").contains("PolyForm Noncommercial License 1.0.0"))
  }

  private fun read(relativePath: String): String = Files.readString(repoRoot.resolve(relativePath))
}
