package skillbill.desktop.feature.skillbill.state

import kotlinx.coroutines.runBlocking
import skillbill.desktop.core.datastore.DesktopFirstRunPreferences
import skillbill.desktop.core.domain.model.FirstRunAgentOption
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallDetail
import skillbill.desktop.core.domain.model.FirstRunInstallDetailSeverity
import skillbill.desktop.core.domain.model.FirstRunInstallOutcome
import skillbill.desktop.core.domain.model.FirstRunInstallPlan
import skillbill.desktop.core.domain.model.FirstRunInstallPlanHandle
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunPlatformPackOption
import skillbill.desktop.core.domain.model.FirstRunPlatformSelectionMode
import skillbill.desktop.core.domain.model.FirstRunSetupDiscovery
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.FirstRunSetupStep
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeDesktopPreferenceStore
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakePrPublishingGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRenderGateway
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.FakeValidationGateway
import skillbill.desktop.core.testing.install.FakeDesktopFirstRunGateway
import skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillBillViewModelFirstRunTest {
  @Test
  fun `first launch skips setup when install already exists`() {
    val preferences = FakeDesktopPreferenceStore(
      initialFirstRunPreferences = DesktopFirstRunPreferences(completed = false),
    )
    val viewModel = newViewModel(
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(discovery()),
        planResult = FirstRunPlanResult.Planned(plan()),
        applyResult = FirstRunApplyResult.Applied(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
        ),
        existingInstall = true,
      ),
      preferenceStore = preferences,
    )

    assertNull(viewModel.state().firstRunSetup)
    assertFalse(preferences.firstRunPreferences.value.completed)
  }

  @Test
  fun `first launch discovers setup choices and applies selected request`() = runBlocking {
    val gateway = FakeDesktopFirstRunGateway(
      discoveryResult = FirstRunDiscoveryResult.Success(discovery()),
      planResult = FirstRunPlanResult.Planned(plan()),
      applyResult = FirstRunApplyResult.Applied(
        FirstRunInstallOutcome(
          status = FirstRunInstallStatus.WARNING,
          title = "Setup completed with warnings.",
          details = listOf(
            FirstRunInstallDetail(
              label = "Windows symlinks",
              message = "Developer Mode required.",
              severity = FirstRunInstallDetailSeverity.WARNING,
              guidance = "Enable Developer Mode.",
            ),
          ),
        ),
      ),
    )
    val preferences = FakeDesktopPreferenceStore()
    val viewModel = newViewModel(gateway, preferences)

    val discoveryRequest = assertNotNull(viewModel.beginFirstRunDiscovery())
    val discovered = viewModel.finishFirstRunDiscovery(viewModel.runFirstRunDiscovery(discoveryRequest))
    assertEquals(setOf("codex"), discovered.firstRunSetup?.selectedAgentIds)
    assertEquals(1, gateway.discoveryCallCount)

    viewModel.selectFirstRunAgent("claude", selected = true)
    viewModel.advanceFirstRunStep()
    viewModel.selectFirstRunPlatform("kotlin", selected = true)
    viewModel.advanceFirstRunStep()
    viewModel.selectFirstRunTelemetry(FirstRunTelemetryLevel.FULL)
    val ready = viewModel.advanceFirstRunStep()
    assertEquals(FirstRunSetupStep.APPLY, ready.firstRunSetup?.step)

    val applyRequest = assertNotNull(viewModel.beginFirstRunApply())
    val applied = viewModel.finishFirstRunApply(viewModel.runFirstRunApply(applyRequest))

    assertEquals(setOf("claude", "codex"), gateway.planRequests.single().selectedAgentIds)
    assertEquals(setOf("kotlin"), gateway.planRequests.single().selectedPlatformSlugs)
    assertEquals(FirstRunTelemetryLevel.FULL, gateway.planRequests.single().telemetryLevel)
    assertTrue(gateway.planRequests.single().registerMcp)
    assertEquals(1, gateway.applyCallCount)
    assertEquals(FirstRunInstallStatus.WARNING, applied.firstRunSetup?.outcome?.status)
    assertTrue(preferences.firstRunPreferences.value.completed)
    assertEquals(emptySet(), preferences.firstRunPreferences.value.selectedAgentIds)

    val finished = viewModel.finishFirstRunSetup()
    assertNull(finished.firstRunSetup)
  }

  @Test
  fun `failed apply keeps first run incomplete and preserves structured outcome`() = runBlocking {
    val gateway = FakeDesktopFirstRunGateway(
      discoveryResult = FirstRunDiscoveryResult.Success(discovery()),
      planResult = FirstRunPlanResult.Planned(plan()),
      applyResult = FirstRunApplyResult.Failed(
        FirstRunInstallOutcome(
          status = FirstRunInstallStatus.FAILURE,
          title = "Setup failed.",
          details = listOf(
            FirstRunInstallDetail(
              label = "MCP codex",
              message = "MCP registration failed.",
              severity = FirstRunInstallDetailSeverity.ERROR,
              agentId = "codex",
              path = "/home/user/.codex/config.toml",
            ),
          ),
        ),
      ),
    )
    val preferences = FakeDesktopPreferenceStore()
    val viewModel = newViewModel(gateway, preferences)

    val discoveryRequest = assertNotNull(viewModel.beginFirstRunDiscovery())
    viewModel.finishFirstRunDiscovery(viewModel.runFirstRunDiscovery(discoveryRequest))
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    val applyRequest = assertNotNull(viewModel.beginFirstRunApply())
    val failed = viewModel.finishFirstRunApply(viewModel.runFirstRunApply(applyRequest))

    assertFalse(preferences.firstRunPreferences.value.completed)
    assertEquals(FirstRunInstallStatus.FAILURE, failed.firstRunSetup?.outcome?.status)
    assertEquals("MCP registration failed.", failed.firstRunSetup?.outcome?.details?.single()?.message)
    assertEquals("codex", failed.firstRunSetup?.outcome?.details?.single()?.agentId)
    assertEquals(FirstRunSetupStep.AGENTS, viewModel.retryFirstRunSetup().firstRunSetup?.step)
  }

  @Test
  fun `completed setup can be reopened for reinstall`() = runBlocking {
    val preferences = FakeDesktopPreferenceStore(
      initialFirstRunPreferences = DesktopFirstRunPreferences(
        completed = true,
        selectedAgentIds = setOf("claude"),
        selectedPlatformSlugs = setOf("kotlin"),
        telemetryLevelId = FirstRunTelemetryLevel.FULL.id,
        registerMcp = false,
      ),
    )
    val viewModel = newViewModel(
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(discovery()),
        planResult = FirstRunPlanResult.Planned(plan()),
        applyResult = FirstRunApplyResult.Applied(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
        ),
      ),
      preferenceStore = preferences,
    )

    assertNull(viewModel.state().firstRunSetup)
    val reopened = assertNotNull(viewModel.openFirstRunSetup().firstRunSetup)
    assertEquals(setOf("claude"), reopened.selectedAgentIds)
    assertEquals(setOf("kotlin"), reopened.selectedPlatformSlugs)
    assertEquals(FirstRunTelemetryLevel.FULL, reopened.telemetryLevel)
    assertFalse(reopened.registerMcp)

    val discoveryRequest = assertNotNull(viewModel.beginFirstRunDiscovery())
    val discovered = viewModel.finishFirstRunDiscovery(viewModel.runFirstRunDiscovery(discoveryRequest))

    assertEquals(setOf("claude"), discovered.firstRunSetup?.selectedAgentIds)
    assertEquals(setOf("kotlin"), discovered.firstRunSetup?.selectedPlatformSlugs)
    assertEquals(FirstRunTelemetryLevel.FULL, discovered.firstRunSetup?.telemetryLevel)
    assertFalse(discovered.firstRunSetup?.registerMcp ?: true)
  }

  @Test
  fun `reusable all platform selection survives discovery and apply`() = runBlocking {
    val gateway = FakeDesktopFirstRunGateway(
      discoveryResult = FirstRunDiscoveryResult.Success(
        discovery().copy(
          platformPacks = listOf(
            FirstRunPlatformPackOption(slug = "kmp", packRoot = "/repo/platform-packs/kmp"),
            FirstRunPlatformPackOption(slug = "kotlin", packRoot = "/repo/platform-packs/kotlin"),
          ),
        ),
      ),
      planResult = FirstRunPlanResult.Planned(plan()),
      applyResult = FirstRunApplyResult.Applied(
        FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
      ),
      latestReusableSetupRequest = FirstRunSetupRequest(
        selectedAgentIds = setOf("codex"),
        selectedPlatformSlugs = emptySet(),
        telemetryLevel = FirstRunTelemetryLevel.ANONYMOUS,
        registerMcp = true,
        platformSelectionMode = FirstRunPlatformSelectionMode.ALL,
      ),
    )
    val viewModel = newViewModel(gateway, FakeDesktopPreferenceStore())

    val discoveryRequest = assertNotNull(viewModel.beginFirstRunDiscovery())
    val discovered = viewModel.finishFirstRunDiscovery(viewModel.runFirstRunDiscovery(discoveryRequest))
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    val applyRequest = assertNotNull(viewModel.beginFirstRunApply())
    viewModel.finishFirstRunApply(viewModel.runFirstRunApply(applyRequest))

    assertEquals(setOf("kmp", "kotlin"), discovered.firstRunSetup?.selectedPlatformSlugs)
    assertEquals(FirstRunPlatformSelectionMode.ALL, gateway.planRequests.single().platformSelectionMode)
    assertEquals(setOf("kmp", "kotlin"), gateway.planRequests.single().selectedPlatformSlugs)
  }

  @Test
  fun `setup wizard can be dismissed while idle`() {
    val viewModel = newViewModel(
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(discovery()),
        planResult = FirstRunPlanResult.Planned(plan()),
        applyResult = FirstRunApplyResult.Applied(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
        ),
      ),
      preferenceStore = FakeDesktopPreferenceStore(),
    )

    assertNotNull(viewModel.state().firstRunSetup)
    val dismissed = viewModel.dismissFirstRunSetup()

    assertNull(dismissed.firstRunSetup)
  }

  @Test
  fun `setup wizard dismiss is ignored while busy`() {
    val viewModel = newViewModel(
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(discovery()),
        planResult = FirstRunPlanResult.Planned(plan()),
        applyResult = FirstRunApplyResult.Applied(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
        ),
      ),
      preferenceStore = FakeDesktopPreferenceStore(),
    )

    assertNotNull(viewModel.beginFirstRunDiscovery())
    val dismissed = viewModel.dismissFirstRunSetup()

    assertNotNull(dismissed.firstRunSetup)
    assertTrue(dismissed.firstRunSetup?.busy ?: false)
  }

  private fun newViewModel(
    firstRunGateway: FakeDesktopFirstRunGateway,
    preferenceStore: FakeDesktopPreferenceStore,
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = FakeRepoSessionService(),
    skillTreeService = FakeSkillTreeService(
      listOf(SkillBillTreeItem(id = "skills", label = "Skills", kind = TreeItemKind.GROUP)),
    ),
    authoringGateway = FakeAuthoringGateway(),
    gitGateway = FakeGitGateway(),
    prPublishingGateway = FakePrPublishingGateway(),
    validationGateway = FakeValidationGateway(),
    renderGateway = FakeRenderGateway(),
    recentRepoRepository = FakeRecentRepoRepository(),
    scaffoldGateway = FakeScaffoldGateway(),
    firstRunGateway = firstRunGateway,
    desktopPreferenceStore = preferenceStore,
    skillRemoveGateway = skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway(),
  )
}

private object TestPlanHandle : FirstRunInstallPlanHandle

private fun discovery(): FirstRunSetupDiscovery = FirstRunSetupDiscovery(
  agents = listOf(
    FirstRunAgentOption(
      agentId = "codex",
      displayName = "Codex",
      detected = true,
      detectedPath = "/home/user/.codex/agents",
      selected = true,
    ),
    FirstRunAgentOption(agentId = "claude", displayName = "Claude", detected = false),
  ),
  platformPacks = listOf(
    FirstRunPlatformPackOption(slug = "kotlin", packRoot = "/repo/platform-packs/kotlin"),
  ),
)

private fun plan(): FirstRunInstallPlan = FirstRunInstallPlan(
  handle = TestPlanHandle,
  selectedAgentIds = setOf("codex"),
  selectedPlatformSlugs = setOf("kotlin"),
  platformPacks = listOf(FirstRunPlatformPackOption(slug = "kotlin", packRoot = "/repo/platform-packs/kotlin")),
  baseSkillCount = 4,
  platformSkillCount = 2,
  stagingRoot = "/home/user/.skill-bill/installed-skills",
)
