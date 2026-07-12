package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicensePolicyDocumentationTest {
  private val repoRoot: Path = generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
    .first { Files.isRegularFile(it.resolve("LICENSE")) }

  @Test
  fun `license is a complete governing use policy rather than a marker document`() {
    val license = read("LICENSE")

    assertTrue(license.startsWith("Skill Bill Use License 1.0\n\n"))
    val publicFiles = listOf(
      "README.md",
      "CONTRIBUTING.md",
      "RELEASING.md",
      "docs/team-control-plane-roadmap.md",
      "docs/licensing.md",
      "docs/release-license-approval.md",
    ).associateWith(::read)

    val sections = listOf(
      "1. Purpose and prospective application",
      "2. Definitions",
      "3. Acceptance and ownership",
      "4. Grant before the Stable Release Event",
      "5. Grant at and after the Stable Release Event",
      "6. Customization permission",
      "7. Restrictions that apply at all times",
      "8. User Materials and Generated Outputs",
      "9. Earlier, commercial, platform, and third-party rights",
      "10. Termination and cure",
      "11. Disclaimer and limitation of liability",
      "12. General terms",
    )
    val clauses = listOf(
      "Identifier: LicenseRef-Skill-Bill-Use-1.0",
      "Prospective Effective Version: v0.1.2",
      "commercial use, consulting, managed-service use, and hosted-service use",
      "first public, non-draft, non-prerelease",
      "tagged exactly v1.0.0",
      "does not undo the event",
      "commercial-use permission in section 4 ends automatically",
      "Personal Use or Open Source Project Use",
      "requires a Commercial License purchased from the Copyright Holder",
      "may copy, modify, adapt, and create derivative works from Customizable Materials",
      "redistribute, distribute, publish, mirror, sublicense, sell, lease, transfer, bundle",
      "Licensees retain all rights in their User Materials and Generated Outputs",
      "GitHub's platform-limited rights",
      "For a first material breach",
      "Covered Software is provided \"AS IS\" and \"AS AVAILABLE.\"",
      "If any provision of this License is unenforceable",
    )

    sections.forEach { section -> assertEquals(1, license.split("$section").size - 1, section) }
    val normalizedWhitespace = license.replace(Regex("\\s+"), " ")
    clauses.forEach { clause -> assertTrue(normalizedWhitespace.contains(clause), clause) }
    listOf(
      "This License is the MIT License",
      "This License is a PolyForm license",
      "Covered Software is open source",
      "Covered Software is free software",
    ).forEach { claim -> assertFalse(license.contains(claim), claim) }

    assertTrue(license.contains("earlier MIT, PolyForm, or other license"))
  }

  @Test
  fun `public policy surfaces retain one governing matrix and an operational contributor grant`() {
    val publicFiles = listOf(
      "README.md",
      "CONTRIBUTING.md",
      "RELEASING.md",
      "docs/team-control-plane-roadmap.md",
      "docs/licensing.md",
      "docs/release-license-approval.md",
    ).associateWith(::read)

    publicFiles.forEach { (path, text) ->
      assertTrue(text.contains("LICENSE"), "$path must link to the governing LICENSE")
    }
    listOf("README.md", "CONTRIBUTING.md", "RELEASING.md", "docs/team-control-plane-roadmap.md", "docs/licensing.md")
      .forEach { path ->
        val text = publicFiles.getValue(path)
        assertTrue(text.contains("v0.1.0 and v0.1.1"), path)
        assertTrue(text.contains("v0.1.2"), path)
        assertTrue(text.contains("Stable Release Event"), path)
        assertTrue(text.contains("v1.0.0"), path)
      }
    val contribution = publicFiles.getValue("CONTRIBUTING.md")
    val normalizedContribution = contribution.replace(Regex("\\s+"), " ")
    listOf(
      "reproduce",
      "modify",
      "prepare derivative works",
      "incorporate",
      "publish",
      "distribute",
      "sublicense",
      "relicense",
    )
      .forEach { verb -> assertTrue(normalizedContribution.contains(verb), verb) }
    assertTrue(normalizedContribution.contains("authority to grant"))
    assertTrue(read("README.md").contains("LicenseRef-Skill-Bill-Use-1.0"))
    assertTrue(read("README.md").contains("License-Skill%20Bill%20Use"))
    assertTrue(read("docs/licensing.md").contains("Earlier MIT and PolyForm grants"))
    val archPackage = read("runtime-kotlin/runtime-desktop/packaging/arch/PKGBUILD")
    assertTrue(archPackage.contains("pkgver=0.1.2"))
    assertTrue(archPackage.contains("license=('LicenseRef-Skill-Bill-Use-1.0')"))
    assertTrue(read("LICENSE").contains("Prospective Effective Version: v0.1.2"))
    assertFalse(publicFiles.values.joinToString("\n").contains("PolyForm Noncommercial License 1.0.0"))
  }

  private fun read(relativePath: String): String = Files.readString(repoRoot.resolve(relativePath))
}
