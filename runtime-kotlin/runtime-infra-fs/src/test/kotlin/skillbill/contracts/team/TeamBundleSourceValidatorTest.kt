@file:Suppress("MaxLineLength")

package skillbill.contracts.team

import skillbill.error.InvalidTeamBundleSchemaError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
  fun `absolute in repo source path is canonicalized`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("skills/bill-demo").createDirectories()
    val contentPath = root.resolve("skills/bill-demo/content.md")
    contentPath.writeText("---\nname: bill-demo\ndescription: Demo\n---\n# Demo\n\nGuidance.\n")

    val canonical = TeamBundleSourceValidator.validateSources(
      bundle("horizontal_skill", contentPath.toAbsolutePath().normalize().toString()),
      root,
      "bundle.yaml",
    )

    val sources = canonical["sources"] as List<*>
    val source = sources.single() as Map<*, *>
    assertEquals("skills/bill-demo/content.md", source["path"])
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
  fun `canonical orchestration support sources are accepted`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("orchestration/shell-content-contract").createDirectories()
    root.resolve("orchestration/shell-content-contract/shell-ceremony.md").writeText("---\nname: shell-ceremony\n---\n")

    TeamBundleSourceValidator.validateSources(
      bundle("orchestration_contract_or_support", "orchestration/shell-content-contract/shell-ceremony.md"),
      root,
      "bundle.yaml",
    )
  }

  @Test
  fun `root support sources required by repo validation are accepted`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve(".agents").createDirectories()
    root.resolve("README.md").writeText("# Skill Bill\n")
    root.resolve(".agents/skill-overrides.example.md").writeText("# Overrides\n")

    listOf("README.md", ".agents/skill-overrides.example.md").forEach { path ->
      TeamBundleSourceValidator.validateSources(
        bundle("orchestration_contract_or_support", path),
        root,
        "bundle.yaml",
      )
    }
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
  fun `telemetry outbox files are rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("telemetry-outbox").createDirectories()
    root.resolve("telemetry-outbox/events.jsonl").writeText("{}")

    val error = assertInvalid(root, "orchestration_contract_or_support", "telemetry-outbox/events.jsonl")

    assertContains(error.reason, "telemetry outbox")
  }

  @Test
  fun `desktop local recents paths are rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("local-recents").createDirectories()
    root.resolve("local-recents/repos.json").writeText("{}")

    val error = assertInvalid(root, "orchestration_contract_or_support", "local-recents/repos.json")

    assertContains(error.reason, "local-recents")
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

  @Test
  fun `addon source with malformed platform manifest is rejected through platform validation seam`() {
    val root = Files.createTempDirectory("team-bundle-source")
    root.resolve("platform-packs/bad/addons").createDirectories()
    root.resolve("platform-packs/bad/platform.yaml").writeText("contract_version: \"wrong\"\n")
    root.resolve("platform-packs/bad/addons/team-addon.md").writeText("addon")

    val error = assertInvalid(root, "addon", "platform-packs/bad/addons/team-addon.md")

    assertContains(error.reason, "Platform pack")
  }

  @Test
  fun `manifest declared addon pointer at platform skill location is rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    writePlatformPackWithAddonPointer(root, "fixture")
    root.resolve("platform-packs/fixture/code-review/bill-fixture-code-review/offline-first-review.md")
      .writeText("generated pointer")

    val error = assertInvalid(
      root,
      "platform_pack",
      "platform-packs/fixture/code-review/bill-fixture-code-review/offline-first-review.md",
    )

    assertContains(error.reason, "generated support pointer")
  }

  @Test
  fun `manifest declared addon source under addons is accepted`() {
    val root = Files.createTempDirectory("team-bundle-source")
    writePlatformPackWithAddonPointer(root, "fixture")

    TeamBundleSourceValidator.validateSources(
      bundle("addon", "platform-packs/fixture/addons/offline-first-review.md"),
      root,
      "bundle.yaml",
    )
  }

  @Test
  fun `manifest declared feature task addon pointer at horizontal skill location is rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    writePlatformPackWithFeatureTaskAddonPointer(root, "fixture")
    root.resolve("skills/bill-feature-task").createDirectories()
    root.resolve("skills/bill-feature-task/content.md")
      .writeText("---\nname: bill-feature-task\ndescription: Demo\n---\n# Demo\n\nGuidance.\n")
    root.resolve("skills/bill-feature-task/android-compose-implementation.md").writeText("generated pointer")

    val error = assertInvalid(
      root,
      "horizontal_skill",
      "skills/bill-feature-task/android-compose-implementation.md",
    )

    assertContains(error.reason, "generated support pointer")
  }

  @Test
  fun `manifest declared feature task addon source under addons is accepted`() {
    val root = Files.createTempDirectory("team-bundle-source")
    writePlatformPackWithFeatureTaskAddonPointer(root, "fixture")

    TeamBundleSourceValidator.validateSources(
      bundle("addon", "platform-packs/fixture/addons/android-compose-implementation.md"),
      root,
      "bundle.yaml",
    )
  }

  @Test
  fun `platform pack code review source missing governed content is rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    writeMinimalPlatformPack(root, "fixture")
    root.resolve("platform-packs/fixture/code-review/bill-fixture-code-review").createDirectories()
    root.resolve("platform-packs/fixture/code-review/bill-fixture-code-review/notes.md").writeText("notes")

    val error = assertInvalid(
      root,
      "platform_pack",
      "platform-packs/fixture/code-review/bill-fixture-code-review/notes.md",
    )

    assertContains(error.reason, "missing required content.md")
  }

  @Test
  fun `platform pack quality check source missing governed content is rejected`() {
    val root = Files.createTempDirectory("team-bundle-source")
    writeMinimalPlatformPack(root, "fixture")
    root.resolve("platform-packs/fixture/quality-check/bill-fixture-code-check").createDirectories()
    root.resolve("platform-packs/fixture/quality-check/bill-fixture-code-check/notes.md").writeText("notes")

    val error = assertInvalid(
      root,
      "platform_pack",
      "platform-packs/fixture/quality-check/bill-fixture-code-check/notes.md",
    )

    assertContains(error.reason, "missing required content.md")
  }

  @Test
  fun `platform pack governed source with manifest and content is accepted`() {
    val root = Files.createTempDirectory("team-bundle-source")
    writeMinimalPlatformPack(root, "fixture")
    root.resolve("platform-packs/fixture/code-review/bill-fixture-code-review").createDirectories()
    root.resolve("platform-packs/fixture/code-review/bill-fixture-code-review/content.md")
      .writeText("---\nname: bill-fixture-code-review\ndescription: Demo\n---\n# Demo\n\nGuidance.\n")

    TeamBundleSourceValidator.validateSources(
      bundle("platform_pack", "platform-packs/fixture/code-review/bill-fixture-code-review/content.md"),
      root,
      "bundle.yaml",
    )
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

  private fun writeMinimalPlatformPack(root: Path, slug: String) {
    root.resolve("platform-packs/$slug").createDirectories()
    root.resolve("platform-packs/$slug/platform.yaml").writeText(
      """
      platform: $slug
      contract_version: "1.2"
      display_name: Fixture
      routing_signals:
        strong:
          - ".$slug"
      declared_code_review_areas: []
      """.trimIndent(),
    )
  }

  private fun writePlatformPackWithAddonPointer(root: Path, slug: String) {
    root.resolve("platform-packs/$slug/code-review/bill-$slug-code-review").createDirectories()
    root.resolve("platform-packs/$slug/addons").createDirectories()
    root.resolve("platform-packs/$slug/code-review/bill-$slug-code-review/content.md")
      .writeText("---\nname: bill-$slug-code-review\ndescription: Demo\n---\n# Demo\n\nGuidance.\n")
    root.resolve("platform-packs/$slug/addons/offline-first-review.md").writeText("addon")
    root.resolve("platform-packs/$slug/platform.yaml").writeText(
      """
      platform: $slug
      contract_version: "1.2"
      display_name: Fixture
      routing_signals:
        strong:
          - ".$slug"
      declared_code_review_areas: []
      declared_files:
        baseline: "code-review/bill-$slug-code-review/content.md"
      addon_usage:
        code-review/bill-$slug-code-review:
          - slug: offline-first
            entrypoint: offline-first-review.md
      pointers:
        code-review/bill-$slug-code-review:
          - name: offline-first-review.md
            target: platform-packs/$slug/addons/offline-first-review.md
      """.trimIndent(),
    )
  }

  private fun writePlatformPackWithFeatureTaskAddonPointer(root: Path, slug: String) {
    root.resolve("platform-packs/$slug/addons").createDirectories()
    root.resolve("platform-packs/$slug/addons/android-compose-implementation.md").writeText("addon")
    root.resolve("platform-packs/$slug/platform.yaml").writeText(
      """
      platform: $slug
      contract_version: "1.2"
      display_name: Fixture
      routing_signals:
        strong:
          - ".$slug"
      declared_code_review_areas: []
      feature_addon_usage:
        feature-task:
          - slug: android-compose
            entrypoint: android-compose-implementation.md
      pointers:
        feature-task:
          - name: android-compose-implementation.md
            target: platform-packs/$slug/addons/android-compose-implementation.md
      """.trimIndent(),
    )
  }
}
