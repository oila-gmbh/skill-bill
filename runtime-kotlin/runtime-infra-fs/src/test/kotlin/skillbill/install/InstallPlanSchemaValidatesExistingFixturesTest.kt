package skillbill.install

import skillbill.contracts.install.InstallPlanSchemaValidator
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
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
import skillbill.install.model.buildInstallPlanWireMap
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * SKILL-48 Subtask 2b AC3: every install-plan fixture produced by the
 * existing contract-coverage paths validates clean against
 * `orchestration/contracts/install-plan-schema.yaml`. The scenarios
 * mirror those exercised by `InstallPlanContractCoverageTest`:
 *
 * - All-platforms selection with discovered base + pack skills.
 * - Manual agent selection over every supported agent.
 * - Detection-derived agent selection.
 * - Telemetry-level coverage (every level).
 * - Windows symlink preflight coverage (every state/decision combo).
 *
 * Each scenario runs `InstallOperations.planInstall` (so the builder
 * seam already validates), then re-validates the wire map at the CLI
 * boundary so both seams are pinned.
 */
class InstallPlanSchemaValidatesExistingFixturesTest {
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
  fun `all platforms selected plan validates against the canonical schema`() {
    val fixture = setupFixture()
    seedPlatformPack(fixture.repoRoot, "python", areaNames = listOf("security"))
    val plan = InstallOperations.planInstall(
      fixture.request(platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL)),
    )
    InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
  }

  @Test
  fun `manual selected agent plan validates against the canonical schema`() {
    val fixture = setupFixture()
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
    InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
  }

  @Test
  fun `detection derived plan validates against the canonical schema`() {
    val fixture = setupFixture()
    Files.createDirectories(fixture.home.resolve(".copilot"))
    Files.createDirectories(fixture.home.resolve(".claude"))
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".config/opencode"))
    Files.createDirectories(fixture.home.resolve(".junie"))
    val plan = InstallOperations.planInstall(
      fixture.request(agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED)),
    )
    InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
  }

  @Test
  fun `every telemetry level plan validates against the canonical schema`() {
    InstallTelemetryLevel.entries.forEach { level ->
      val fixture = setupFixture()
      val plan = InstallOperations.planInstall(fixture.request(telemetryLevel = level))
      InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
    }
  }

  @Test
  fun `every windows symlink preflight plan validates against the canonical schema`() {
    windowsSymlinkPreflightCases().forEach { preflight ->
      val fixture = setupFixture()
      val plan = InstallOperations.planInstall(fixture.request(windowsSymlinkPreflight = preflight))
      InstallPlanSchemaValidator.validate(buildInstallPlanWireMap(plan))
    }
  }

  private fun setupFixture(): InstallPlanWireFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-plan-schema-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-plan-schema-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedPlatformPack(repoRoot, "kotlin", areaNames = listOf("architecture", "testing"))
    seedPlatformPack(repoRoot, "kmp", areaNames = listOf("architecture", "testing"))
    return InstallPlanWireFixture(repoRoot = repoRoot, home = home)
  }

  private fun seedBaseSkill(repoRoot: Path, skillName: String) {
    val skillDir = repoRoot.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), content(skillName))
  }

  private fun seedPlatformPack(repoRoot: Path, slug: String, areaNames: List<String>) {
    val codeReviewName = "bill-$slug-code-review"
    val qualityCheckName = "bill-$slug-code-check"
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

  private data class InstallPlanWireFixture(
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
