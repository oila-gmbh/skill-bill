package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkillKind
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

class InstallPlanContractCoverageTest {
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
  fun `plan output is driven by discovered manifests and stages generated artifacts outside source`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(fixture.repoRoot, "python", areaNames = listOf("security"))
    val before = snapshotTree(fixture.repoRoot)

    val plan = InstallOperations.planInstall(
      fixture.request(
        platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
      ),
    )

    assertEquals(listOf("kmp", "kotlin", "python"), plan.discoveredPlatformPacks.map { pack -> pack.slug })
    assertEquals(listOf("kmp", "kotlin", "python"), plan.selectedPlatformSlugs)
    val plannedSkills = plan.skills.associateBy { skill -> skill.name }
    assertEquals(InstallPlanSkillKind.BASE, plannedSkills.getValue("bill-code-review").kind)
    assertEquals(InstallPlanSkillKind.BASE, plannedSkills.getValue("bill-code-quality-check").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, plannedSkills.getValue("bill-python-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, plannedSkills.getValue("bill-python-code-review-security").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, plannedSkills.getValue("bill-python-code-quality-check").kind)
    assertEquals(plan.skills.map { skill -> skill.name }, plan.staging.skillPaths.map { path -> path.skillName })
    plan.staging.skillPaths.forEach { intent ->
      assertEquals(plan.staging.root, intent.stagingRoot)
      assertTrue(intent.stagingDir.startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
      assertFalse(intent.stagingDir.startsWith(fixture.repoRoot), "${intent.skillName} staged inside source")
      assertFalse(Files.exists(intent.sourceDir.resolve("SKILL.md")), "${intent.skillName} wrote SKILL.md into source")
    }
    assertEquals(before, snapshotTree(fixture.repoRoot), "planning must not write generated governed artifacts")
  }

  @Test
  fun `manual selected agent plan covers all supported agents and MCP intent`() {
    val fixture = setupPlanFixture()
    val explicitTargets = InstallAgent.entries.map { agent ->
      InstallAgentTarget(
        agent = agent,
        path = fixture.home.resolve("manual-targets/${agent.id}"),
        source = InstallAgentTargetSource.MANUAL,
      )
    }

    val plan = InstallOperations.planInstall(
      fixture.request(
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = InstallAgent.entries.toSet(),
        ),
        targetPaths = fixture.targetPaths(agentTargets = explicitTargets),
      ),
    )

    val expectedAgents = InstallAgent.entries.sortedBy(InstallAgent::id)
    assertEquals(expectedAgents, plan.agents.map { target -> target.agent })
    assertEquals(expectedAgents, plan.mcpRegistrationIntent.agents)
    assertTrue(plan.mcpRegistrationIntent.register)
    assertEquals(fixture.runtimeMcpBin, plan.mcpRegistrationIntent.runtimeMcpBin)
    plan.agents.forEach { target ->
      assertEquals(InstallAgentTargetSource.MANUAL, target.source)
      assertEquals(fixture.home.resolve("manual-targets/${target.agent.id}"), target.path)
    }
  }

  @Test
  fun `detection derived agent selection covers all supported agents without source mutation`() {
    val fixture = setupPlanFixture()
    Files.createDirectories(fixture.home.resolve(".copilot"))
    Files.createDirectories(fixture.home.resolve(".claude"))
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".config/opencode"))
    Files.createDirectories(fixture.home.resolve(".junie"))
    val before = snapshotTree(fixture.repoRoot)

    val plan = InstallOperations.planInstall(
      fixture.request(
        agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
      ),
    )

    assertEquals(InstallAgent.entries.toList(), plan.agents.map { target -> target.agent })
    assertEquals(InstallAgent.entries.toList(), plan.mcpRegistrationIntent.agents)
    assertEquals(
      listOf(
        fixture.home.resolve(".copilot/skills"),
        fixture.home.resolve(".claude/commands"),
        fixture.home.resolve(".codex/skills"),
        fixture.home.resolve(".config/opencode/skills"),
        fixture.home.resolve(".junie/skills"),
      ),
      plan.agents.map { target -> target.path },
    )
    assertTrue(plan.agents.all { target -> target.source == InstallAgentTargetSource.DETECTED })
    assertEquals(before, snapshotTree(fixture.repoRoot), "detected planning mutated source files")
  }

  @Test
  fun `telemetry choices are represented as stable plan fields without applying`() {
    InstallTelemetryLevel.entries.forEach { level ->
      val fixture = setupPlanFixture()
      val beforeHome = snapshotTree(fixture.home)

      val plan = InstallOperations.planInstall(
        fixture.request(telemetryLevel = level),
      )

      assertEquals(level, plan.telemetryLevel)
      assertEquals(level, plan.request.telemetryLevel)
      assertEquals(fixture.runtimeInstallRoot, plan.runtimeDistributionInputs.runtimeInstallRoot)
      assertEquals(fixture.runtimeMcpBin, plan.mcpRegistrationIntent.runtimeMcpBin)
      assertTrue(plan.mcpRegistrationIntent.register)
      assertEquals(beforeHome, snapshotTree(fixture.home), "planning telemetry '$level' mutated home")
    }
  }

  @Test
  fun `windows symlink preflight choices are represented in the plan contract`() {
    windowsSymlinkPreflightCases().forEach { preflight ->
      val fixture = setupPlanFixture()

      val plan = InstallOperations.planInstall(
        fixture.request(windowsSymlinkPreflight = preflight),
      )

      assertEquals(preflight, plan.windowsSymlinkPreflight)
      assertEquals(preflight, plan.request.windowsSymlinkPreflight)
      assertEquals(fixture.home.resolve(".skill-bill/installed-skills"), plan.staging.root)
    }
  }

  private fun setupPlanFixture(): PlanFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-plan-contract-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-plan-contract-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-quality-check")
    seedPlatformPack(repoRoot, "kotlin", areaNames = listOf("architecture", "testing"))
    seedPlatformPack(repoRoot, "kmp", areaNames = listOf("architecture", "testing"))
    return PlanFixture(repoRoot = repoRoot, home = home)
  }

  private fun seedBaseSkill(repoRoot: Path, skillName: String) {
    val skillDir = repoRoot.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), content(skillName))
  }

  private fun seedPlatformPack(repoRoot: Path, slug: String, areaNames: List<String>) {
    val codeReviewName = "bill-$slug-code-review"
    val qualityCheckName = "bill-$slug-code-quality-check"
    val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
    val areaSkillNames = areaNames.associateWith { area -> "bill-$slug-code-review-$area" }
    val declaredAreas = areaNames.joinToString("") { area -> "\n  - $area" }
    val declaredAreaFiles = areaSkillNames.entries.joinToString("") { (area, skillName) ->
      "\n    $area: \"code-review/$skillName/content.md\""
    }
    Files.createDirectories(packRoot.resolve("code-review").resolve(codeReviewName))
    areaSkillNames.values.forEach { skillName ->
      Files.createDirectories(packRoot.resolve("code-review").resolve(skillName))
    }
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
      |declared_code_review_areas:$declaredAreas
      |declared_files:
      |  baseline: "code-review/$codeReviewName/content.md"
      |  areas:$declaredAreaFiles
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
    areaSkillNames.values.forEach { skillName ->
      Files.writeString(packRoot.resolve("code-review").resolve(skillName).resolve("content.md"), content(skillName))
    }
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

  private fun snapshotTree(root: Path): Map<String, String> {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return emptyMap()
    }
    return Files.walk(root).use { stream ->
      stream
        .sorted()
        .toList()
        .associate { path ->
          val relative = root.relativize(path)
            .toString()
            .replace(java.io.File.separatorChar, '/')
            .ifEmpty { "." }
          val value = when {
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "<DIR>"
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> Files.readString(path)
            else -> "<OTHER>"
          }
          relative to value
        }
    }
  }

  private fun windowsSymlinkPreflightCases(): List<WindowsSymlinkPreflight> = listOf(
    WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.NOT_WINDOWS,
      decision = WindowsSymlinkDecision.NOT_REQUIRED,
      message = "",
    ),
    WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.AVAILABLE,
      decision = WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS,
      message = "Windows symlink support is available.",
    ),
    WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE,
      decision = WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS,
      message = "Windows symlink support was not confirmed.",
    ),
    WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.DECISION_REQUIRED,
      decision = WindowsSymlinkDecision.REQUIRE_USER_ACTION,
      message = "Windows requires elevation or Developer Mode before symlink install.",
    ),
  )

  private data class PlanFixture(
    val repoRoot: Path,
    val home: Path,
  ) {
    val runtimeInstallRoot: Path = home.resolve(".skill-bill/runtime")
    val runtimeMcpBin: Path = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp")

    fun targetPaths(agentTargets: List<InstallAgentTarget> = emptyList()): InstallationTargetPaths =
      InstallationTargetPaths(
        skillsRoot = repoRoot.resolve("skills"),
        platformPacksRoot = repoRoot.resolve("platform-packs"),
        agentTargets = agentTargets,
      )

    fun request(
      agentSelection: InstallAgentSelection = InstallAgentSelection(
        mode = InstallAgentSelectionMode.MANUAL,
        manualAgents = setOf(InstallAgent.CODEX),
      ),
      platformPackSelection: PlatformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
      telemetryLevel: InstallTelemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      targetPaths: InstallationTargetPaths = targetPaths(),
      windowsSymlinkPreflight: WindowsSymlinkPreflight = WindowsSymlinkPreflight(
        state = WindowsSymlinkPreflightState.NOT_WINDOWS,
        decision = WindowsSymlinkDecision.NOT_REQUIRED,
      ),
    ): InstallPlanRequest = InstallPlanRequest(
      repoRoot = repoRoot,
      home = home,
      agentSelection = agentSelection,
      platformPackSelection = platformPackSelection,
      telemetryLevel = telemetryLevel,
      mcpRegistrationChoice = McpRegistrationChoice(register = true, runtimeMcpBin = runtimeMcpBin),
      runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot),
      targetPaths = targetPaths,
      windowsSymlinkPreflight = windowsSymlinkPreflight,
    )
  }
}
