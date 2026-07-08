@file:Suppress("MaxLineLength")

package skillbill.contracts.team

import skillbill.error.InvalidTeamBundleSchemaError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class TeamBundleSourceValidatorTest {
  @Test
  fun `valid governed skill content source is accepted`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("skills/bill-demo").createDirectories()
    root.resolve(
      "skills/bill-demo/content.md",
    ).writeText("---\nname: bill-demo\ndescription: Demo\n---\n# Demo\n\nGuidance.\n")

    TeamBundleSourceValidator.validateSources(
      bundle("horizontal_skill", "skills/bill-demo/content.md"),
      root,
      "bundle.yaml",
    )
  }

  @Test
  fun `generated SKILL md wrapper is rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("skills/bill-demo").createDirectories()
    root.resolve("skills/bill-demo/content.md").writeText("# Demo\n")
    root.resolve("skills/bill-demo/SKILL.md").writeText("# Generated\n")

    val error = assertInvalid(root, "horizontal_skill", "skills/bill-demo/SKILL.md")

    assertContains(error.reason, "generated governed SKILL.md")
  }

  @Test
  fun `support pointer names are rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("skills/bill-demo").createDirectories()
    root.resolve("skills/bill-demo/shell-ceremony.md").writeText("pointer")

    val error = assertInvalid(root, "horizontal_skill", "skills/bill-demo/shell-ceremony.md")

    assertContains(error.reason, "generated support pointer")
  }

  @Test
  fun `provider native output directories are rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("skills/bill-demo/claude-agents").createDirectories()
    root.resolve("skills/bill-demo/claude-agents/demo.md").writeText("generated")

    val error = assertInvalid(root, "native_agent_source", "skills/bill-demo/claude-agents/demo.md")

    assertContains(error.reason, "provider-specific native-agent output")
  }

  @Test
  fun `install staging artifacts are rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve(".skill-bill/staging/bill-demo").createDirectories()
    root.resolve(".skill-bill/staging/bill-demo/.content-hash").writeText("hash")

    val error = assertInvalid(root, "horizontal_skill", ".skill-bill/staging/bill-demo/.content-hash")

    assertContains(error.reason, "installed staging")
  }

  @Test
  fun `workflow database paths are rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("workflow").createDirectories()
    root.resolve("workflow/state.db").writeText("db")

    val error = assertInvalid(root, "orchestration_contract_or_support", "workflow/state.db")

    assertContains(error.reason, "workflow database")
  }

  @Test
  fun `desktop state paths are rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("desktop-state").createDirectories()
    root.resolve("desktop-state/window.json").writeText("{}")

    val error = assertInvalid(root, "orchestration_contract_or_support", "desktop-state/window.json")

    assertContains(error.reason, "desktop app state")
  }

  @Test
  fun `missing governed content file is rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("skills/bill-demo").createDirectories()
    root.resolve("skills/bill-demo/native-agents").createDirectories()
    root.resolve("skills/bill-demo/native-agents/demo.md").writeText("agent")

    val error = assertInvalid(root, "native_agent_source", "skills/bill-demo/native-agents/demo.md")

    assertContains(error.reason, "missing required content.md")
  }

  @Test
  fun `malformed platform manifest is rejected through platform validation seam`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("platform-packs/bad").createDirectories()
    root.resolve("platform-packs/bad/platform.yaml").writeText("contract_version: \"wrong\"\n")

    val error = assertInvalid(root, "platform_pack", "platform-packs/bad/platform.yaml")

    assertContains(error.reason, "Platform pack")
  }

  private fun assertInvalid(root: Path, category: String, path: String): InvalidTeamBundleSchemaError =
    assertFailsWith<InvalidTeamBundleSchemaError> {
      TeamBundleSourceValidator.validateSources(bundle(category, path), root, "bundle.yaml")
    }

  private fun bundle(category: String, path: String): Map<String, Any?> = mapOf(
    "sources" to listOf(
      mapOf(
        "category" to category,
        "path" to path,
        "content_hash" to "sha256:source",
      ),
    ),
  )
}
