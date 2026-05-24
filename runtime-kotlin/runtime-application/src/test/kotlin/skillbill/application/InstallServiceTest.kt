package skillbill.application

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentDefaultTarget
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.apply.model.InstallApplyExecutionRequest
import skillbill.ports.install.apply.model.InstallApplyExecutionResult
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.link.model.InstallSkillLinkRequest
import skillbill.ports.install.link.model.InstallSkillLinkResult
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.plan.model.InstallPlanningFacts
import skillbill.ports.install.plan.model.InstallPlanningFactsRequest
import skillbill.ports.install.plan.model.InstallPlanningFactsResult
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortRequest
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortResult
import skillbill.ports.install.plan.model.InstallStagingIntentRequest
import skillbill.ports.install.plan.model.InstallStagingIntentResult
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class InstallServiceTest {
  @Test
  fun `plan install carries one platform manifest snapshot through planning ports`() {
    val repoRoot = Path.of("/tmp/skillbill-install-service-test-repo")
    val home = Path.of("/tmp/skillbill-install-service-test-home")
    val platformManifests = listOf(platformManifest(repoRoot, "kotlin"))
    val request = request(repoRoot, home)
    val planningFactsPort = FakePlanningFactsPort(repoRoot, home, platformManifests)
    val service = InstallService(
      planningFactsPort = planningFactsPort,
      platformSkillMaterializationPort = SnapshotAssertingMaterializationPort(platformManifests),
      stagingIntentPort = SnapshotAssertingStagingIntentPort(home, platformManifests),
      applyExecutionPort = UnsupportedApplyExecutionPort,
      skillLinkPort = UnsupportedSkillLinkPort,
    )

    service.planInstall(request)

    assertEquals(1, planningFactsPort.collectCallCount)
  }

  private fun platformManifest(repoRoot: Path, slug: String): PlatformManifest = PlatformManifest(
    slug = slug,
    packRoot = repoRoot.resolve("platform-packs").resolve(slug),
    contractVersion = "1.1",
    routingSignals = RoutingSignals(strong = listOf(slug), tieBreakers = emptyList()),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = DeclaredFiles(baseline = null, areas = emptyMap()),
    areaMetadata = emptyMap(),
  )

  private fun request(repoRoot: Path, home: Path): InstallPlanRequest = InstallPlanRequest(
    repoRoot = repoRoot,
    home = home,
    agentSelection = InstallAgentSelection(
      mode = InstallAgentSelectionMode.MANUAL,
      manualAgents = setOf(InstallAgent.CODEX),
    ),
    platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice = McpRegistrationChoice(
      register = true,
      runtimeMcpBin = home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp"),
    ),
    runtimeDistributionInputs = RuntimeDistributionInputs(
      runtimeInstallRoot = home.resolve(".skill-bill/runtime"),
    ),
    targetPaths = InstallationTargetPaths(
      skillsRoot = repoRoot.resolve("skills"),
      platformPacksRoot = repoRoot.resolve("platform-packs"),
    ),
    windowsSymlinkPreflight = WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.NOT_WINDOWS,
      decision = WindowsSymlinkDecision.NOT_REQUIRED,
    ),
  )

  private class FakePlanningFactsPort(
    private val repoRoot: Path,
    private val home: Path,
    private val platformManifests: List<PlatformManifest>,
  ) : InstallPlanningFactsPort {
    var collectCallCount = 0
      private set

    override fun collectPlanningFacts(request: InstallPlanningFactsRequest): InstallPlanningFactsResult {
      collectCallCount += 1
      require(collectCallCount == 1) {
        "InstallService must collect install planning facts exactly once per planInstall call."
      }
      return InstallPlanningFactsResult(
        facts = InstallPlanningFacts(
          baseSkills = listOf(
            InstallPlanSkill(
              name = "bill-code-review",
              sourceDir = repoRoot.resolve("skills/bill-code-review"),
              kind = InstallPlanSkillKind.BASE,
            ),
          ),
          platformManifests = platformManifests,
          detectedAgentTargets = emptyList(),
          defaultAgentTargets = listOf(
            InstallAgentDefaultTarget(
              agent = InstallAgent.CODEX,
              path = home.resolve(".codex/skills"),
            ),
          ),
        ),
      )
    }
  }

  private class SnapshotAssertingMaterializationPort(
    private val expectedPlatformManifests: List<PlatformManifest>,
  ) : InstallPlatformSkillMaterializationPort {
    override fun materializePlatformSkills(
      request: InstallPlatformSkillMaterializationPortRequest,
    ): InstallPlatformSkillMaterializationPortResult {
      assertSame(expectedPlatformManifests, request.platformManifests)
      return InstallPlatformSkillMaterializationPortResult(
        platformPacks = request.platformManifests.map { manifest ->
          InstallPlatformPackSnapshot(
            slug = manifest.slug,
            packRoot = manifest.packRoot,
            skills = emptyList(),
          )
        },
      )
    }
  }

  private class SnapshotAssertingStagingIntentPort(
    private val home: Path,
    private val expectedPlatformManifests: List<PlatformManifest>,
  ) : InstallStagingIntentPort {
    override fun buildStagingIntent(request: InstallStagingIntentRequest): InstallStagingIntentResult {
      assertSame(expectedPlatformManifests, request.platformManifests)
      return InstallStagingIntentResult(
        staging = InstallStagingIntent(
          root = home.resolve(".skill-bill/installed-skills"),
          skillPaths = request.draft.skills.map { skill ->
            InstallStagingPathIntent(
              skillName = skill.name,
              sourceDir = skill.sourceDir,
              stagingRoot = home.resolve(".skill-bill/installed-skills"),
              stagingDir = home.resolve(".skill-bill/installed-skills/${skill.name}-test-hash"),
              contentHash = "test-hash-${skill.name}",
            )
          },
        ),
      )
    }
  }

  private object UnsupportedApplyExecutionPort : InstallApplyExecutionPort {
    override fun applyInstall(request: InstallApplyExecutionRequest): InstallApplyExecutionResult =
      error("applyInstall is not part of this test")
  }

  private object UnsupportedSkillLinkPort : InstallSkillLinkPort {
    override fun linkSkill(request: InstallSkillLinkRequest): InstallSkillLinkResult =
      error("linkSkill is not part of this test")
  }
}
