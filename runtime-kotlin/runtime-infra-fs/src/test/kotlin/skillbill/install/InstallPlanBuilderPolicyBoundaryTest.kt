package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallPlanBuilderPolicyBoundaryTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  @Test
  fun `unselected platform declared content files are not materialized during planning`() {
    val fixture = setupPlanFixture()
    val outsideSkillDir = fixture.repoRoot.resolve("outside/bill-escaped-code-review")
    Files.createDirectories(outsideSkillDir)
    Files.writeString(outsideSkillDir.resolve("content.md"), content("bill-escaped-code-review"))
    seedPlatformPack(fixture.repoRoot, slug = "experimental")
    val manifest = fixture.repoRoot.resolve("platform-packs/experimental/platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "baseline: \"code-review/bill-experimental-code-review/content.md\"",
        "baseline: \"../../outside/bill-escaped-code-review/content.md\"",
      ),
    )

    val plan = InstallOperations.planInstall(
      fixture.request(
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("kotlin"),
        ),
      ),
    )

    assertTrue(plan.discoveredPlatformPacks.any { pack -> pack.slug == "experimental" && !pack.selected })
    assertEquals(listOf("kotlin"), plan.selectedPlatformSlugs)
    assertFalse(plan.skills.any { skill -> skill.platformSlug == "experimental" })
  }

  private fun setupPlanFixture(): PlanFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-plan-boundary-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-plan-boundary-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedPlatformPack(repoRoot, "kotlin")
    return PlanFixture(repoRoot = repoRoot, home = home)
  }

  private fun seedBaseSkill(repoRoot: Path, skillName: String) {
    val skillDir = repoRoot.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), content(skillName))
  }

  private fun seedPlatformPack(repoRoot: Path, slug: String) {
    val codeReviewName = "bill-$slug-code-review"
    val qualityCheckName = "bill-$slug-code-quality-check"
    val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
    Files.createDirectories(packRoot.resolve("code-review").resolve(codeReviewName))
    Files.createDirectories(packRoot.resolve("quality-check").resolve(qualityCheckName))
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      |platform: "$slug"
      |contract_version: "1.1"
      |routing_signals:
      |  strong:
      |    - "$slug"
      |  tie_breakers: []
      |declared_code_review_areas: []
      |declared_files:
      |  baseline: "code-review/$codeReviewName/content.md"
      |  areas: {}
      |area_metadata: {}
      |display_name: "$slug"
      |declared_quality_check_file: "quality-check/$qualityCheckName/content.md"
      |
      """.trimMargin(),
    )
    Files.writeString(
      packRoot.resolve("code-review").resolve(codeReviewName).resolve("content.md"),
      content(codeReviewName),
    )
    Files.writeString(
      packRoot.resolve("quality-check").resolve(qualityCheckName).resolve("content.md"),
      content(qualityCheckName),
    )
  }

  private fun content(name: String): String = """
    |---
    |name: $name
    |description: Test skill.
    |---
    |
    |# $name
    |
    |Test body.
    |
  """.trimMargin()

  private data class PlanFixture(
    val repoRoot: Path,
    val home: Path,
  ) {
    private val runtimeInstallRoot: Path = home.resolve(".skill-bill/runtime")

    fun request(
      platformPackSelection: PlatformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
    ): InstallPlanRequest = InstallPlanRequest(
      repoRoot = repoRoot,
      home = home,
      agentSelection = InstallAgentSelection(
        mode = InstallAgentSelectionMode.MANUAL,
        manualAgents = setOf(InstallAgent.CODEX),
      ),
      platformPackSelection = platformPackSelection,
      telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      mcpRegistrationChoice = McpRegistrationChoice(
        register = true,
        runtimeMcpBin = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp"),
      ),
      runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot),
      targetPaths = InstallationTargetPaths(
        skillsRoot = repoRoot.resolve("skills"),
        platformPacksRoot = repoRoot.resolve("platform-packs"),
      ),
      windowsSymlinkPreflight = WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.NOT_WINDOWS,
        decision = WindowsSymlinkDecision.NOT_REQUIRED,
      ),
    )
  }
}
