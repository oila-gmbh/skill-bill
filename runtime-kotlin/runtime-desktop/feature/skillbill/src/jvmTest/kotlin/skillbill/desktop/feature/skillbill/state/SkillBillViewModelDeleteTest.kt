package skillbill.desktop.feature.skillbill.state

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import skillbill.desktop.core.domain.model.DesktopSkillRemovalPreview
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakePrPublishingGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRenderGateway
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.FakeValidationGateway
import skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway
import skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway

/**
 * SKILL-46 AC9: ViewModel-level right-click → confirm → execute → refresh test path.
 *
 * Uses [FakeSkillRemoveGateway] to script preview/execute responses without exercising any
 * real gateway. The repo session is plumbed through [FakeRepoSessionService] returning a loaded
 * status so the begin/run/finish triplets can capture a non-empty repo path.
 */
class SkillBillViewModelDeleteTest {
  @Test
  fun `showConfirmDeletion opens dialog state for a horizontal skill target`() {
    val viewModel = buildViewModel()
    val target = DesktopSkillRemovalTarget.HorizontalSkill(skillName = "bill-foo")
    val state = viewModel.showConfirmDeletion(target)
    assertNotNull(state.confirmDeletion)
    assertEquals(target, state.confirmDeletion?.target)
    assertNull(state.confirmDeletion?.preview)
    assertFalse(state.confirmDeletion?.deleteEnabled ?: true)
  }

  @Test
  fun `preview success populates dossier and deleteEnabled stays false until acknowledged`() {
    val gateway = FakeSkillRemoveGateway()
    val viewModel = buildViewModel(skillRemoveGateway = gateway)
    val target = DesktopSkillRemovalTarget.HorizontalSkill(skillName = "bill-foo")
    val preview = samplePreview(target)
    gateway.enqueuePreview(target, DesktopSkillRemovalResult.Preview(preview))

    viewModel.showConfirmDeletion(target)
    val request = viewModel.beginPreviewRemoval() ?: error("preview triplet did not start")
    val result = runBlocking { viewModel.runPreviewRemoval(request) }
    val state = viewModel.finishPreviewRemoval(request, result)

    assertNotNull(state.confirmDeletion?.preview)
    assertFalse(state.confirmDeletion?.deleteEnabled ?: true)

    val acknowledged = viewModel.setRemovalAcknowledged(true)
    assertTrue(acknowledged.confirmDeletion?.deleteEnabled ?: false)
  }

  @Test
  fun `execute issues an executeCalls entry and Success triggers tree refresh exactly once`() {
    val skillTreeService = FakeSkillTreeService(
      listOf(SkillBillTreeItem(id = "skills", label = "Skills", kind = TreeItemKind.GROUP)),
    )
    val gateway = FakeSkillRemoveGateway()
    val viewModel = buildViewModel(skillRemoveGateway = gateway, skillTreeService = skillTreeService)
    val target = DesktopSkillRemovalTarget.HorizontalSkill(skillName = "bill-foo")
    val preview = samplePreview(target)
    gateway.enqueuePreview(target, DesktopSkillRemovalResult.Preview(preview))
    gateway.enqueueExecute(
      target,
      DesktopSkillRemovalResult.Success(
        preview = preview,
        removedPaths = listOf("/repo/skills/bill-foo"),
        editedManifests = emptyList(),
        unlinkedSymlinks = emptyList(),
      ),
    )

    viewModel.showConfirmDeletion(target)
    val previewRequest = viewModel.beginPreviewRemoval() ?: error("preview did not start")
    viewModel.finishPreviewRemoval(previewRequest, runBlocking { viewModel.runPreviewRemoval(previewRequest) })
    viewModel.setRemovalAcknowledged(true)

    val executeRequest = viewModel.beginExecuteRemoval() ?: error("execute did not start")
    val executeResult = runBlocking { viewModel.runExecuteRemoval(executeRequest) }
    val state = viewModel.finishExecuteRemoval(executeRequest, executeResult)

    assertEquals(1, gateway.executeCalls.size)
    assertTrue(state.confirmDeletion?.executionResult is DesktopSkillRemovalResult.Success)

    // F-004-TESTING: a Success removal triggers a post-delete tree re-scan. The route consumes
    // `beginRefreshAfterScaffold + loadRepo + finishRefreshAfterScaffold` after finishExecuteRemoval
    // returns Success — exercise the exact same begin/run/finish triplet here and assert that the
    // refreshCount went up by exactly one.
    val baselineRefreshes = skillTreeService.refreshCount
    val refreshRequest = viewModel.beginRefreshAfterScaffold()
    val refreshResult = viewModel.loadRepo(refreshRequest)
    viewModel.finishRefreshAfterScaffold(refreshResult)
    assertEquals(baselineRefreshes + 1, skillTreeService.refreshCount)
  }

  @Test
  fun `Failed result with rollbackComplete=false sets partialMutationLocked and blocks delete`() {
    val skillTreeService = FakeSkillTreeService(
      listOf(SkillBillTreeItem(id = "skills", label = "Skills", kind = TreeItemKind.GROUP)),
    )
    val gateway = FakeSkillRemoveGateway()
    val viewModel = buildViewModel(skillRemoveGateway = gateway, skillTreeService = skillTreeService)
    val target = DesktopSkillRemovalTarget.HorizontalSkill(skillName = "bill-foo")
    val preview = samplePreview(target)
    gateway.enqueuePreview(target, DesktopSkillRemovalResult.Preview(preview))
    gateway.enqueueExecute(
      target,
      DesktopSkillRemovalResult.Failed(
        exceptionName = "Boom",
        exceptionMessage = "rollback failed",
        rollbackComplete = false,
      ),
    )

    viewModel.showConfirmDeletion(target)
    val previewRequest = viewModel.beginPreviewRemoval() ?: error("preview did not start")
    viewModel.finishPreviewRemoval(previewRequest, runBlocking { viewModel.runPreviewRemoval(previewRequest) })
    viewModel.setRemovalAcknowledged(true)
    val executeRequest = viewModel.beginExecuteRemoval() ?: error("execute did not start")
    // F-004-TESTING: snapshot refreshCount BEFORE finishExecuteRemoval so we can prove a Failed
    // result does NOT auto-trigger a tree refresh.
    val baselineRefreshes = skillTreeService.refreshCount
    val executeResult = runBlocking { viewModel.runExecuteRemoval(executeRequest) }
    val locked = viewModel.finishExecuteRemoval(executeRequest, executeResult)

    assertTrue(locked.confirmDeletion?.partialMutationLocked ?: false)
    assertFalse(locked.confirmDeletion?.deleteEnabled ?: true)
    // F-004-TESTING: a Failed result must NOT trigger a tree re-scan. The route only refreshes
    // after a Success, and finishExecuteRemoval alone never calls into the tree service.
    assertEquals(baselineRefreshes, skillTreeService.refreshCount)

    val cleared = viewModel.acknowledgeRemovalFailure()
    assertFalse(cleared.confirmDeletion?.partialMutationLocked ?: true)
  }

  @Test
  fun `dismissConfirmDeletion releases DELETE busy slot`() {
    val gateway = FakeSkillRemoveGateway()
    val viewModel = buildViewModel(skillRemoveGateway = gateway)
    val target = DesktopSkillRemovalTarget.HorizontalSkill(skillName = "bill-foo")
    gateway.enqueuePreview(target, DesktopSkillRemovalResult.Preview(samplePreview(target)))

    viewModel.showConfirmDeletion(target)
    val request = viewModel.beginPreviewRemoval() ?: error("preview did not start")
    assertEquals(SkillBillBusyOperation.DELETE, viewModel.state().busyOperation)
    val dismissed = viewModel.dismissConfirmDeletion()
    assertNull(dismissed.busyOperation)
    assertNull(dismissed.confirmDeletion)
    // Drain the in-flight gateway call so the FakeSkillRemoveGateway script is consumed.
    runBlocking { viewModel.runPreviewRemoval(request) }
  }

  private fun samplePreview(target: DesktopSkillRemovalTarget): DesktopSkillRemovalPreview = DesktopSkillRemovalPreview(
    filesystemPaths = listOf("skills/bill-foo"),
    manifestEdits = emptyList(),
    agentSymlinkUnlinks = emptyList(),
    readmeCatalogEdits = emptyList(),
    skillDirRoot = "skills/bill-foo",
    cascadedSkillNames = listOf("bill-foo"),
  )

  private fun buildViewModel(
    skillRemoveGateway: FakeSkillRemoveGateway = FakeSkillRemoveGateway(),
    skillTreeService: FakeSkillTreeService = FakeSkillTreeService(
      listOf(SkillBillTreeItem(id = "skills", label = "Skills", kind = TreeItemKind.GROUP)),
    ),
  ): SkillBillViewModel {
    val viewModel = SkillBillViewModel(
      repoSessionService = FakeRepoSessionService(),
      skillTreeService = skillTreeService,
      authoringGateway = FakeAuthoringGateway(),
      gitGateway = FakeGitGateway(),
      prPublishingGateway = FakePrPublishingGateway(),
      validationGateway = FakeValidationGateway(),
      renderGateway = FakeRenderGateway(),
      recentRepoRepository = FakeRecentRepoRepository(),
      scaffoldGateway = FakeScaffoldGateway(),
      firstRunGateway = defaultDeleteTestFirstRunGateway(),
      desktopPreferenceStore = skillbill.desktop.core.testing.FakeDesktopPreferenceStore(
        initialFirstRunPreferences = skillbill.desktop.core.datastore.DesktopFirstRunPreferences(completed = true),
      ),
      skillRemoveGateway = skillRemoveGateway,
    )
    viewModel.selectRepoPath("/repo")
    return viewModel
  }
}

private fun defaultDeleteTestFirstRunGateway(): skillbill.desktop.core.domain.service.DesktopFirstRunGateway =
  skillbill.desktop.core.testing.install.FakeDesktopFirstRunGateway(
    discoveryResult = skillbill.desktop.core.domain.model.FirstRunDiscoveryResult.Success(
      skillbill.desktop.core.domain.model.FirstRunSetupDiscovery(
        agents = emptyList(),
        platformPacks = emptyList(),
      ),
    ),
    planResult = skillbill.desktop.core.domain.model.FirstRunPlanResult.Failed("not scripted"),
    applyResult = skillbill.desktop.core.domain.model.FirstRunApplyResult.Failed(
      skillbill.desktop.core.domain.model.FirstRunInstallOutcome(
        status = skillbill.desktop.core.domain.model.FirstRunInstallStatus.FAILURE,
        title = "not scripted",
      ),
    ),
  )
