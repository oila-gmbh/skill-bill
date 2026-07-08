package skillbill.infrastructure.fs

import skillbill.contracts.team.validBundleYaml
import skillbill.error.InvalidTeamBundleSchemaError
import skillbill.team.model.TeamBundleChannel
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeamBundleValidatorAdapterTest {
  @Test
  fun `valid yaml returns typed team bundle through public filesystem seam`() {
    val root = Files.createTempDirectory("team-bundle-adapter")
    root.resolve("skills/bill-code-check").createDirectories()
    root.resolve("skills/bill-code-check/content.md")
      .writeText("---\nname: bill-code-check\ndescription: Code check\n---\n# Code Check\n\nGuidance.\n")

    val bundle = TeamBundleValidatorAdapter().validateYamlText(
      validBundleYaml(channel = "preview"),
      "bundle.yaml",
      root,
    )

    assertEquals("team-bundle-foundation", bundle.metadata.bundleId)
    assertEquals(TeamBundleChannel.PREVIEW, bundle.metadata.channel)
    assertEquals("skills/bill-code-check/content.md", bundle.sources.single().path)
  }

  @Test
  fun `public filesystem seam canonicalizes absolute in-repo source paths`() {
    val root = Files.createTempDirectory("team-bundle-adapter")
    root.resolve("skills/bill-code-check").createDirectories()
    val contentPath = root.resolve("skills/bill-code-check/content.md")
    contentPath.writeText("---\nname: bill-code-check\ndescription: Code check\n---\n# Code Check\n\nGuidance.\n")
    val yaml = validBundleYaml().replace(
      "path: skills/bill-code-check/content.md",
      "path: ${contentPath.toAbsolutePath().normalize()}",
    )

    val bundle = TeamBundleValidatorAdapter().validateYamlText(yaml, "bundle.yaml", root)

    assertEquals("skills/bill-code-check/content.md", bundle.sources.single().path)
  }

  @Test
  fun `public filesystem seam rejects generated artifact sources`() {
    val root = Files.createTempDirectory("team-bundle-adapter")
    root.resolve("skills/bill-code-check").createDirectories()
    root.resolve("skills/bill-code-check/content.md").writeText("# Code Check\n")
    root.resolve("skills/bill-code-check/SKILL.md").writeText("# Generated\n")
    val yaml = validBundleYaml().replace(
      "path: skills/bill-code-check/content.md",
      "path: skills/bill-code-check/SKILL.md",
    )

    val error = assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleValidatorAdapter().validateYamlText(yaml, "bundle.yaml", root)
    }

    assertContains(error.reason, "generated governed SKILL.md")
  }
}
