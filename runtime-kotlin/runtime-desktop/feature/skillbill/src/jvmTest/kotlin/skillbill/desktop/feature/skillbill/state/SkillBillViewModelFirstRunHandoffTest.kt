package skillbill.desktop.feature.skillbill.state

import kotlinx.coroutines.runBlocking
import skillbill.desktop.core.domain.model.FirstRunAgentOption
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallOutcome
import skillbill.desktop.core.domain.model.FirstRunInstallPlan
import skillbill.desktop.core.domain.model.FirstRunInstallPlanHandle
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunPlatformPackOption
import skillbill.desktop.core.domain.model.FirstRunSetupDiscovery
import skillbill.desktop.core.domain.model.InstalledWorkspaceAvailability
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeDesktopPreferenceStore
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.install.FakeDesktopFirstRunGateway
import skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway
import skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway
import skillbill.desktop.core.testing.workspace.FakeInstalledWorkspaceLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SkillBillViewModelFirstRunHandoffTest {

  @Test
  fun `finishFirstRunSetup on success opens installed workspace`() = runBlocking {
    val installedRoot = "/home/user/.skill-bill/installed-skills"
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = installedRoot, availability = true),
    )
    val viewModel = newViewModel(
      installedWorkspaceLocator = locator,
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(handoffDiscovery()),
        planResult = FirstRunPlanResult.Planned(handoffPlan()),
        applyResult = FirstRunApplyResult.Applied(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
        ),
      ),
    )

    val discoveryRequest = assertNotNull(viewModel.beginFirstRunDiscovery())
    viewModel.finishFirstRunDiscovery(viewModel.runFirstRunDiscovery(discoveryRequest))
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    val applyRequest = assertNotNull(viewModel.beginFirstRunApply())
    viewModel.finishFirstRunApply(viewModel.runFirstRunApply(applyRequest))

    val finished = viewModel.finishFirstRunSetup()

    assertNull(finished.firstRunSetup)
    assertEquals(installedRoot, finished.selectedRepoPath)
  }

  @Test
  fun `finishFirstRunSetup on failure does not open workspace`() = runBlocking {
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = "", availability = false),
    )
    val viewModel = newViewModel(
      installedWorkspaceLocator = locator,
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(handoffDiscovery()),
        planResult = FirstRunPlanResult.Planned(handoffPlan()),
        applyResult = FirstRunApplyResult.Failed(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.FAILURE, title = "Setup failed."),
        ),
      ),
    )

    val discoveryRequest = assertNotNull(viewModel.beginFirstRunDiscovery())
    viewModel.finishFirstRunDiscovery(viewModel.runFirstRunDiscovery(discoveryRequest))
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    val applyRequest = assertNotNull(viewModel.beginFirstRunApply())
    viewModel.finishFirstRunApply(viewModel.runFirstRunApply(applyRequest))

    val result = viewModel.finishFirstRunSetup()

    assertNotNull(result.firstRunSetup)
    assertNull(result.selectedRepoPath)
    assertEquals(1, locator.locateCallCount)
  }

  @Test
  fun `post-wizard session supports file selection and editor save`() = runBlocking {
    val installedRoot = "/home/user/.skill-bill/installed-skills"
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = installedRoot, availability = true),
    )
    val authoringGateway = FakeAuthoringGateway().apply {
      putDocument("skill-content", "original content\n")
    }
    val skillTree = listOf(
      SkillBillTreeItem(
        id = "skills",
        label = "Skills",
        kind = TreeItemKind.GROUP,
        children = listOf(
          SkillBillTreeItem(id = "skill-content", label = "Skill Content", kind = TreeItemKind.SKILL),
        ),
      ),
    )
    val viewModel = newViewModel(
      installedWorkspaceLocator = locator,
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(handoffDiscovery()),
        planResult = FirstRunPlanResult.Planned(handoffPlan()),
        applyResult = FirstRunApplyResult.Applied(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
        ),
      ),
      skillTreeService = FakeSkillTreeService(skillTree),
      authoringGateway = authoringGateway,
    )

    val discoveryRequest = assertNotNull(viewModel.beginFirstRunDiscovery())
    viewModel.finishFirstRunDiscovery(viewModel.runFirstRunDiscovery(discoveryRequest))
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    val applyRequest = assertNotNull(viewModel.beginFirstRunApply())
    viewModel.finishFirstRunApply(viewModel.runFirstRunApply(applyRequest))
    val opened = viewModel.finishFirstRunSetup()

    assertEquals(installedRoot, opened.selectedRepoPath)

    viewModel.selectTreeItem("skill-content")
    viewModel.updateEditorDraft("updated content\n")

    val saveRequest = assertNotNull(viewModel.beginSaveEditor())
    val saved = viewModel.finishSaveEditor(viewModel.runSaveEditor(saveRequest))
    assertFalse(saved.editor.dirty)
    assertEquals("updated content\n", saved.editor.draftContent)
  }

  @Test
  fun `dismissFirstRunSetup does not open workspace`() {
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = "", availability = false),
    )
    val viewModel = newViewModel(
      installedWorkspaceLocator = locator,
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(handoffDiscovery()),
        planResult = FirstRunPlanResult.Planned(handoffPlan()),
        applyResult = FirstRunApplyResult.Applied(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
        ),
      ),
    )

    val result = viewModel.dismissFirstRunSetup()

    assertNull(result.firstRunSetup)
    assertNull(result.selectedRepoPath)
  }

  @Test
  fun `dismissFirstRunSetup while busy preserves setup state`() = runBlocking {
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = "", availability = false),
    )
    val viewModel = newViewModel(
      installedWorkspaceLocator = locator,
      firstRunGateway = FakeDesktopFirstRunGateway(
        discoveryResult = FirstRunDiscoveryResult.Success(handoffDiscovery()),
        planResult = FirstRunPlanResult.Planned(handoffPlan()),
        applyResult = FirstRunApplyResult.Applied(
          FirstRunInstallOutcome(status = FirstRunInstallStatus.SUCCESS, title = "Setup completed."),
        ),
      ),
    )

    val discoveryRequest = assertNotNull(viewModel.beginFirstRunDiscovery())
    viewModel.finishFirstRunDiscovery(viewModel.runFirstRunDiscovery(discoveryRequest))
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    viewModel.advanceFirstRunStep()
    viewModel.beginFirstRunApply()

    val result = viewModel.dismissFirstRunSetup()

    assertNotNull(result.firstRunSetup)
    assertNull(result.selectedRepoPath)
  }

  private fun newViewModel(
    firstRunGateway: FakeDesktopFirstRunGateway,
    installedWorkspaceLocator: FakeInstalledWorkspaceLocator = FakeInstalledWorkspaceLocator(),
    skillTreeService: FakeSkillTreeService = FakeSkillTreeService(
      listOf(SkillBillTreeItem(id = "skills", label = "Skills", kind = TreeItemKind.GROUP)),
    ),
    authoringGateway: FakeAuthoringGateway = FakeAuthoringGateway(),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = FakeRepoSessionService(),
    skillTreeService = skillTreeService,
    authoringGateway = authoringGateway,
    recentRepoRepository = FakeRecentRepoRepository(),
    scaffoldGateway = FakeScaffoldGateway(),
    firstRunGateway = firstRunGateway,
    desktopPreferenceStore = FakeDesktopPreferenceStore(),
    skillRemoveGateway = FakeSkillRemoveGateway(),
    installedWorkspaceLocator = installedWorkspaceLocator,
  )
}

private object HandoffTestPlanHandle : FirstRunInstallPlanHandle

private fun handoffDiscovery(): FirstRunSetupDiscovery = FirstRunSetupDiscovery(
  agents = listOf(
    FirstRunAgentOption(
      agentId = "codex",
      displayName = "Codex",
      detected = true,
      detectedPath = "/home/user/.codex/agents",
      selected = true,
    ),
  ),
  platformPacks = listOf(
    FirstRunPlatformPackOption(slug = "kotlin", packRoot = "/repo/platform-packs/kotlin"),
  ),
)

private fun handoffPlan(): FirstRunInstallPlan = FirstRunInstallPlan(
  handle = HandoffTestPlanHandle,
  selectedAgentIds = setOf("codex"),
  selectedPlatformSlugs = setOf("kotlin"),
  platformPacks = listOf(FirstRunPlatformPackOption(slug = "kotlin", packRoot = "/repo/platform-packs/kotlin")),
  baseSkillCount = 4,
  platformSkillCount = 2,
  stagingRoot = "/home/user/.skill-bill/installed-skills",
)
