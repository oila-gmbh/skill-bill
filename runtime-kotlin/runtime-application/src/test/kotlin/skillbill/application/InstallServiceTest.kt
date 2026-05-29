package skillbill.application

import skillbill.application.install.InstallPlanningPorts
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentDefaultTarget
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentSkillLinkOutcome
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallAppliedSkill
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallSkillStagingOutcome
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.InstallStagingPathIntent
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.McpRegistrationIntent
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkApplyOutcome
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkFallbackState
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
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionResult
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionResult
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
      planningPorts = InstallPlanningPorts(
        planningFactsPort = planningFactsPort,
        platformSkillMaterializationPort = SnapshotAssertingMaterializationPort(platformManifests),
        stagingIntentPort = SnapshotAssertingStagingIntentPort(home, platformManifests),
      ),
      applyExecutionPort = UnsupportedApplyExecutionPort,
      skillLinkPort = UnsupportedSkillLinkPort,
      installSelectionPersistencePort = NoopInstallSelectionPersistencePort,
      installPlanWireValidator = testInstallPlanWireValidator,
    )

    service.planInstall(request)

    assertEquals(1, planningFactsPort.collectCallCount)
  }

  @Test
  fun `apply install persists latest selection from successful resolved agents`() {
    val repoRoot = Path.of("/tmp/skillbill-install-service-success-repo")
    val home = Path.of("/tmp/skillbill-install-service-success-home")
    val request = request(repoRoot, home)
    val plan = installPlan(
      request = request,
      agents = listOf(
        InstallAgentTarget(InstallAgent.CODEX, home.resolve(".codex/skills"), InstallAgentTargetSource.MANUAL),
        InstallAgentTarget(InstallAgent.CLAUDE, home.resolve(".claude/commands"), InstallAgentTargetSource.MANUAL),
      ),
    )
    val selectionPort = RecordingInstallSelectionPersistencePort()
    val service = serviceForApply(
      result = successfulApplyResult(plan, resolvedAgent = InstallAgent.CODEX),
      selectionPort = selectionPort,
    )

    service.applyInstall(plan)

    val write = selectionPort.writeRequests.single()
    assertEquals(home, write.installHome)
    assertEquals(setOf(InstallAgent.CODEX), write.selection.selectedAgents)
    assertEquals(PlatformPackSelectionMode.ALL, write.selection.platformPackSelection.mode)
    assertEquals(emptySet(), write.selection.platformPackSelection.selectedSlugs)
    assertEquals(InstallTelemetryLevel.ANONYMOUS, write.selection.telemetryLevel)
    assertEquals(request.mcpRegistrationChoice, write.selection.mcpRegistrationChoice)
  }

  @Test
  fun `apply install falls back to planned agents when apply result has no resolved agents`() {
    val repoRoot = Path.of("/tmp/skillbill-install-service-fallback-repo")
    val home = Path.of("/tmp/skillbill-install-service-fallback-home")
    val plan = installPlan(request(repoRoot, home))
    val selectionPort = RecordingInstallSelectionPersistencePort()
    val service = serviceForApply(
      result = successfulApplyResult(plan, resolvedAgent = null),
      selectionPort = selectionPort,
    )

    service.applyInstall(plan)

    assertEquals(setOf(InstallAgent.CODEX), selectionPort.writeRequests.single().selection.selectedAgents)
  }

  @Test
  fun `apply install persists manual selected platforms and mcp opt out`() {
    val repoRoot = Path.of("/tmp/skillbill-install-service-manual-repo")
    val home = Path.of("/tmp/skillbill-install-service-manual-home")
    val request = request(repoRoot, home).copy(
      platformPackSelection = PlatformPackSelection(
        mode = PlatformPackSelectionMode.SELECTED,
        selectedSlugs = setOf("kotlin"),
      ),
      telemetryLevel = InstallTelemetryLevel.OFF,
      mcpRegistrationChoice = McpRegistrationChoice(register = false, runtimeMcpBin = null),
    )
    val plan = installPlan(
      request = request,
      agents = listOf(
        InstallAgentTarget(InstallAgent.CODEX, home.resolve(".codex/skills"), InstallAgentTargetSource.MANUAL),
        InstallAgentTarget(InstallAgent.CLAUDE, home.resolve(".claude/commands"), InstallAgentTargetSource.MANUAL),
      ),
      selectedPlatformSlugs = listOf("kotlin"),
    )
    val selectionPort = RecordingInstallSelectionPersistencePort()
    val service = serviceForApply(
      result = successfulApplyResult(plan, resolvedAgent = null),
      selectionPort = selectionPort,
    )

    service.applyInstall(plan)

    val selection = selectionPort.writeRequests.single().selection
    assertEquals(setOf(InstallAgent.CLAUDE, InstallAgent.CODEX), selection.selectedAgents)
    assertEquals(PlatformPackSelectionMode.SELECTED, selection.platformPackSelection.mode)
    assertEquals(setOf("kotlin"), selection.platformPackSelection.selectedSlugs)
    assertEquals(InstallTelemetryLevel.OFF, selection.telemetryLevel)
    assertEquals(McpRegistrationChoice(register = false, runtimeMcpBin = null), selection.mcpRegistrationChoice)
  }

  @Test
  fun `apply install persists detected planned agents when result has no resolved agents`() {
    val repoRoot = Path.of("/tmp/skillbill-install-service-detected-repo")
    val home = Path.of("/tmp/skillbill-install-service-detected-home")
    val request = request(repoRoot, home).copy(
      agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
    )
    val plan = installPlan(
      request = request,
      agents = listOf(
        InstallAgentTarget(InstallAgent.CLAUDE, home.resolve(".claude/commands"), InstallAgentTargetSource.DETECTED),
        InstallAgentTarget(
          InstallAgent.OPENCODE,
          home.resolve(".config/opencode/skills"),
          InstallAgentTargetSource.DETECTED,
        ),
      ),
    )
    val selectionPort = RecordingInstallSelectionPersistencePort()
    val service = serviceForApply(
      result = successfulApplyResult(plan, resolvedAgent = null),
      selectionPort = selectionPort,
    )

    service.applyInstall(plan)

    assertEquals(
      setOf(InstallAgent.CLAUDE, InstallAgent.OPENCODE),
      selectionPort.writeRequests.single().selection.selectedAgents,
    )
  }

  @Test
  fun `apply install does not persist failed selections`() {
    val repoRoot = Path.of("/tmp/skillbill-install-service-failed-repo")
    val home = Path.of("/tmp/skillbill-install-service-failed-home")
    val plan = installPlan(request(repoRoot, home))
    val selectionPort = RecordingInstallSelectionPersistencePort()
    val service = serviceForApply(
      result = failedApplyResult(plan),
      selectionPort = selectionPort,
    )

    service.applyInstall(plan)

    assertEquals(emptyList(), selectionPort.writeRequests)
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

  private fun installPlan(
    request: InstallPlanRequest,
    agents: List<InstallAgentTarget> = listOf(
      InstallAgentTarget(InstallAgent.CODEX, request.home.resolve(".codex/skills"), InstallAgentTargetSource.MANUAL),
    ),
    selectedPlatformSlugs: List<String> = emptyList(),
  ): skillbill.install.model.InstallPlan = skillbill.install.model.InstallPlan(
    request = request,
    agents = agents,
    discoveredPlatformPacks = emptyList(),
    selectedPlatformSlugs = selectedPlatformSlugs,
    skills = listOf(
      InstallPlanSkill(
        name = "bill-code-review",
        sourceDir = request.repoRoot.resolve("skills/bill-code-review"),
        kind = InstallPlanSkillKind.BASE,
      ),
    ),
    staging = InstallStagingIntent(
      root = request.home.resolve(".skill-bill/installed-skills"),
      skillPaths = emptyList(),
    ),
    telemetryLevel = request.telemetryLevel,
    mcpRegistrationIntent = McpRegistrationIntent(
      register = request.mcpRegistrationChoice.register,
      runtimeMcpBin = request.mcpRegistrationChoice.runtimeMcpBin,
      agents = agents.map(InstallAgentTarget::agent),
    ),
    runtimeDistributionInputs = request.runtimeDistributionInputs,
    installationTargetPaths = request.targetPaths,
    windowsSymlinkPreflight = request.windowsSymlinkPreflight,
  )

  private fun serviceForApply(
    result: InstallApplyResult,
    selectionPort: InstallSelectionPersistencePort,
  ): InstallService = InstallService(
    planningPorts = InstallPlanningPorts(
      planningFactsPort = UnsupportedPlanningFactsPort,
      platformSkillMaterializationPort = UnsupportedPlatformSkillMaterializationPort,
      stagingIntentPort = UnsupportedStagingIntentPort,
    ),
    applyExecutionPort = StaticApplyExecutionPort(result),
    skillLinkPort = UnsupportedSkillLinkPort,
    installSelectionPersistencePort = selectionPort,
    installPlanWireValidator = testInstallPlanWireValidator,
  )

  private fun successfulApplyResult(
    plan: skillbill.install.model.InstallPlan,
    resolvedAgent: InstallAgent?,
  ): InstallApplyResult = InstallApplyResult(
    status = InstallApplyStatus.SUCCESS,
    skills = listOf(
      InstallAppliedSkill(
        skillName = "bill-code-review",
        kind = InstallPlanSkillKind.BASE,
        sourceDir = plan.request.repoRoot.resolve("skills/bill-code-review"),
        staging = InstallSkillStagingOutcome(
          status = InstallSkillStagingStatus.STAGED,
          sourceDir = plan.request.repoRoot.resolve("skills/bill-code-review"),
        ),
        links = resolvedAgent?.let { agent ->
          listOf(
            InstallAgentSkillLinkOutcome(
              agent = agent,
              targetDir = plan.request.home.resolve(".codex/skills"),
              linkPath = plan.request.home.resolve(".codex/skills/bill-code-review"),
              linkTarget = plan.request.home.resolve(".skill-bill/installed-skills/bill-code-review"),
              status = InstallAgentLinkStatus.CREATED,
            ),
          )
        }.orEmpty(),
      ),
    ),
    nativeAgents = emptyList(),
    telemetryOutcome = InstallTelemetryApplyOutcome(
      level = plan.telemetryLevel,
      status = InstallTelemetryApplyStatus.SUCCESS,
    ),
    mcpRegistrationOutcomes = emptyList(),
    warnings = emptyList(),
    failures = emptyList(),
    windowsSymlinkOutcome = WindowsSymlinkApplyOutcome(
      preflight = plan.windowsSymlinkPreflight,
      fallbackState = WindowsSymlinkFallbackState.NOT_REQUIRED,
    ),
    telemetryLevel = plan.telemetryLevel,
    mcpRegistrationIntent = plan.mcpRegistrationIntent,
  )

  private fun failedApplyResult(plan: skillbill.install.model.InstallPlan): InstallApplyResult = InstallApplyResult(
    status = InstallApplyStatus.FAILURE,
    skills = emptyList(),
    nativeAgents = emptyList(),
    telemetryOutcome = InstallTelemetryApplyOutcome(
      level = plan.telemetryLevel,
      status = InstallTelemetryApplyStatus.SKIPPED,
    ),
    mcpRegistrationOutcomes = emptyList(),
    warnings = emptyList(),
    failures = listOf(
      InstallApplyIssue(
        kind = InstallApplyIssueKind.WINDOWS_SYMLINK_PRECHECK_FAILED,
        message = "failed",
      ),
    ),
    windowsSymlinkOutcome = WindowsSymlinkApplyOutcome(
      preflight = plan.windowsSymlinkPreflight,
      fallbackState = WindowsSymlinkFallbackState.USER_ACTION_REQUIRED,
    ),
    telemetryLevel = plan.telemetryLevel,
    mcpRegistrationIntent = plan.mcpRegistrationIntent,
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

  private object UnsupportedPlanningFactsPort : InstallPlanningFactsPort {
    override fun collectPlanningFacts(request: InstallPlanningFactsRequest): InstallPlanningFactsResult =
      error("planInstall is not part of this test")
  }

  private object UnsupportedPlatformSkillMaterializationPort : InstallPlatformSkillMaterializationPort {
    override fun materializePlatformSkills(
      request: InstallPlatformSkillMaterializationPortRequest,
    ): InstallPlatformSkillMaterializationPortResult = error("planInstall is not part of this test")
  }

  private object UnsupportedStagingIntentPort : InstallStagingIntentPort {
    override fun buildStagingIntent(request: InstallStagingIntentRequest): InstallStagingIntentResult =
      error("planInstall is not part of this test")
  }

  private object NoopInstallSelectionPersistencePort : InstallSelectionPersistencePort {
    override fun readLatestSuccessfulSelection(
      request: ReadLatestSuccessfulInstallSelectionRequest,
    ): ReadLatestSuccessfulInstallSelectionResult = error("readLatestSuccessfulSelection is not part of this test")

    override fun writeLatestSuccessfulSelection(
      request: WriteLatestSuccessfulInstallSelectionRequest,
    ): WriteLatestSuccessfulInstallSelectionResult =
      WriteLatestSuccessfulInstallSelectionResult(request.installHome.resolve(".skill-bill/install-selection.json"))
  }

  private class RecordingInstallSelectionPersistencePort : InstallSelectionPersistencePort {
    val writeRequests = mutableListOf<WriteLatestSuccessfulInstallSelectionRequest>()

    override fun readLatestSuccessfulSelection(
      request: ReadLatestSuccessfulInstallSelectionRequest,
    ): ReadLatestSuccessfulInstallSelectionResult = error("readLatestSuccessfulSelection is not part of this test")

    override fun writeLatestSuccessfulSelection(
      request: WriteLatestSuccessfulInstallSelectionRequest,
    ): WriteLatestSuccessfulInstallSelectionResult {
      writeRequests += request
      return WriteLatestSuccessfulInstallSelectionResult(
        request.installHome.resolve(".skill-bill/install-selection.json"),
      )
    }
  }

  private class StaticApplyExecutionPort(
    private val result: InstallApplyResult,
  ) : InstallApplyExecutionPort {
    override fun applyInstall(request: InstallApplyExecutionRequest): InstallApplyExecutionResult =
      InstallApplyExecutionResult(result)
  }
}
