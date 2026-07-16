package skillbill.desktop.feature.skillbill.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import skillbill.desktop.core.datastore.DesktopFirstRunPreferences
import skillbill.desktop.core.domain.model.FirstRunApplyResult
import skillbill.desktop.core.domain.model.FirstRunDiscoveryResult
import skillbill.desktop.core.domain.model.FirstRunInstallPlan
import skillbill.desktop.core.domain.model.FirstRunPlanResult
import skillbill.desktop.core.domain.model.FirstRunSetupRequest
import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.MachineSkillSourceSummary
import skillbill.desktop.core.domain.model.MachineSkillTargetDetail
import skillbill.desktop.core.domain.model.MachineSkillTargetOption
import skillbill.desktop.core.domain.model.MachineSkillTargetResult
import skillbill.desktop.core.domain.service.DesktopFirstRunGateway
import skillbill.desktop.core.domain.service.MachineSkillApplyPresentation
import skillbill.desktop.core.domain.service.MachineSkillInventoryPresentation
import skillbill.desktop.core.domain.service.MachineSkillPreviewPresentation
import skillbill.desktop.core.domain.service.MachineSkillSourceChoice
import skillbill.desktop.core.domain.service.ManagedMachineSkillEditPresentation
import skillbill.desktop.core.domain.service.RuntimeMachineSkillGateway
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeDesktopPreferenceStore
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway
import skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway
import skillbill.desktop.core.testing.workspace.FakeInstalledWorkspaceLocator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillBillViewModelMachineSkillTest {
  @Test
  fun `managed save failure preserves the draft and reports the apply outcome`() = runBlocking {
    val gateway = FakeMachineSkillGateway(machineInventory(machineDetail("demo", "record-1", "content-1")))
    gateway.applyPresentation = MachineSkillApplyPresentation(
      results = listOf(MachineSkillTargetResult("codex", "BLOCKED", "record changed")),
      successful = false,
      failureMessage = "record changed",
    )
    val viewModel = machineViewModel(gateway)
    viewModel.refreshMachineSkillInventory()
    viewModel.openMachineSkillTreeItem(machineItemId("demo"))
    viewModel.updateEditorDraft("changed\n")

    val request = assertNotNull(viewModel.beginSaveEditor())
    val failed = viewModel.finishSaveEditor(viewModel.runManagedSaveEditor(request))

    assertTrue(failed.editor.dirty)
    assertEquals("changed\n", failed.editor.draftContent)
    assertContains(assertNotNull(failed.editor.saveErrorMessage), "record changed")
    assertEquals("before demo\n", gateway.markdownByName.getValue("demo"))
  }

  @Test
  fun `managed save renews identities so a second save uses the accepted snapshot`() = runBlocking {
    val renewedDetail = machineDetail("demo", "record-2", "content-2")
    val gateway = FakeMachineSkillGateway(machineInventory(machineDetail("demo", "record-1", "content-1")))
    gateway.applyPresentation = MachineSkillApplyPresentation(
      results = emptyList(),
      successful = true,
      inventory = machineInventory(renewedDetail),
    )
    val viewModel = machineViewModel(gateway)
    viewModel.refreshMachineSkillInventory()
    viewModel.openMachineSkillTreeItem(machineItemId("demo"))
    viewModel.updateEditorDraft("first save\n")

    val firstRequest = assertNotNull(viewModel.beginSaveEditor())
    val firstSaved = viewModel.finishSaveEditor(viewModel.runManagedSaveEditor(firstRequest))
    viewModel.updateEditorDraft("second save\n")
    val secondRequest = assertNotNull(viewModel.beginSaveEditor())

    assertFalse(firstSaved.editor.dirty)
    assertEquals("record-2", secondRequest.managedEdit?.recordIdentity)
    assertEquals("content-2", secondRequest.managedEdit?.sourceIdentity)
    assertEquals("new-snapshot-demo", firstSaved.editor.machineSkillDetail?.activeSnapshotHash)
  }

  @Test
  fun `stale managed open cannot replace the newer machine skill selection`() = runBlocking {
    val gateway = FakeMachineSkillGateway(
      machineInventory(
        machineDetail("alpha", "record-alpha", "content-alpha"),
        machineDetail("beta", "record-beta", "content-beta"),
      ),
    )
    gateway.delayedOpenName = "alpha"
    val viewModel = machineViewModel(gateway)
    viewModel.refreshMachineSkillInventory()

    val staleOpen = async { viewModel.openMachineSkillTreeItem(machineItemId("alpha")) }
    gateway.delayedOpenStarted.await()
    val beta = viewModel.openMachineSkillTreeItem(machineItemId("beta"))
    gateway.releaseDelayedOpen.complete(Unit)
    staleOpen.await()

    assertEquals(machineItemId("beta"), beta.selectedTreeItemId)
    assertEquals(machineItemId("beta"), viewModel.state().selectedTreeItemId)
    assertEquals("before beta\n", viewModel.state().editor.draftContent)
    assertEquals("beta", viewModel.state().editor.skillName)
  }

  @Test
  fun `managed revert keyboard movement and dirty discard reopen editable machine documents`() = runBlocking {
    val gateway = FakeMachineSkillGateway(
      machineInventory(
        machineDetail("alpha", "record-alpha", "content-alpha"),
        machineDetail("beta", "record-beta", "content-beta"),
      ),
    )
    val viewModel = machineViewModel(gateway)
    viewModel.refreshMachineSkillInventory()
    viewModel.openMachineSkillTreeItem(machineItemId("alpha"))
    viewModel.updateEditorDraft("dirty alpha\n")

    val reverted = viewModel.revertEditorDraft()
    assertTrue(reverted.editor.editable)
    assertEquals("before alpha\n", reverted.editor.draftContent)

    val keyboardSelection = viewModel.moveSelection(1)
    val keyboardOpened = viewModel.openMachineSkillTreeItem(assertNotNull(keyboardSelection.selectedTreeItemId))
    assertEquals(machineItemId("beta"), keyboardOpened.selectedTreeItemId)
    assertTrue(keyboardOpened.editor.editable)

    viewModel.updateEditorDraft("dirty beta\n")
    viewModel.selectTreeItem(machineItemId("alpha"))
    val discarded = viewModel.discardDirtyEditorPrompt()
    val reopened = viewModel.openMachineSkillTreeItem(assertNotNull(discarded.selectedTreeItemId))
    assertEquals(machineItemId("alpha"), reopened.selectedTreeItemId)
    assertTrue(reopened.editor.editable)
    assertEquals("before alpha\n", reopened.editor.draftContent)
  }
}

private fun machineViewModel(gateway: RuntimeMachineSkillGateway) = SkillBillViewModel(
  repoSessionService = FakeRepoSessionService(),
  skillTreeService = FakeSkillTreeService(emptyList()),
  authoringGateway = FakeAuthoringGateway(),
  recentRepoRepository = FakeRecentRepoRepository(),
  scaffoldGateway = FakeScaffoldGateway(),
  firstRunGateway = UnusedFirstRunGateway,
  desktopPreferenceStore = FakeDesktopPreferenceStore(
    initialFirstRunPreferences = DesktopFirstRunPreferences(completed = true),
  ),
  skillRemoveGateway = FakeSkillRemoveGateway(),
  installedWorkspaceLocator = FakeInstalledWorkspaceLocator(),
  machineSkillGateway = gateway,
)

private fun machineItemId(name: String): String = "$MACHINE_SKILLS_ROOT_ID:skill:$name"

private fun machineInventory(vararg details: MachineSkillManagerDetail) = MachineSkillInventoryPresentation(
  rows = details.map { detail ->
    MachineSkillManagerRow(
      name = detail.name,
      description = detail.description,
      ownership = detail.ownership,
      health = "HEALTHY",
      agents = detail.targets.map { it.provider }.toSet(),
      logicalKey = detail.name,
    )
  },
  details = details.associateBy { it.name },
)

private fun machineDetail(name: String, recordIdentity: String, contentIdentity: String) = MachineSkillManagerDetail(
  name = name,
  description = "$name description",
  ownership = "MANAGED",
  provenance = listOf("managed record"),
  canonicalManagedSourcePath = "/managed/$name/SKILL.md",
  activeSnapshotHash = "new-snapshot-$name",
  recordIdentity = recordIdentity,
  contentIdentity = contentIdentity,
  targets = listOf(
    MachineSkillTargetDetail(
      id = "codex-$name",
      provider = "codex",
      path = "/home/tester/.codex/skills/$name",
      detectionStatus = "DETECTED",
      state = "PRESENT",
      linkHealth = listOf("HEALTHY"),
    ),
  ),
  validationIssues = emptyList(),
)

private class FakeMachineSkillGateway(
  var inventoryPresentation: MachineSkillInventoryPresentation,
) : RuntimeMachineSkillGateway {
  var applyPresentation = MachineSkillApplyPresentation(
    results = emptyList(),
    successful = true,
    inventory = inventoryPresentation,
  )
  var delayedOpenName: String? = null
  val delayedOpenStarted = CompletableDeferred<Unit>()
  val releaseDelayedOpen = CompletableDeferred<Unit>()
  val markdownByName = inventoryPresentation.details.keys.associateWith { "before $it\n" }.toMutableMap()
  private var pendingEdit: ManagedMachineSkillEditPresentation? = null

  override suspend fun chooseSource() = MachineSkillSourceChoice.Cancelled

  override suspend fun inspectSource(path: String): MachineSkillSourceSummary = error("Not used")

  override suspend fun assessInstallTargets(sourcePath: String): List<MachineSkillTargetOption> = error("Not used")

  override suspend fun previewInstall(sourcePath: String, targetIds: Set<String>): MachineSkillPreviewPresentation =
    error("Not used")

  override suspend fun apply(planId: String): MachineSkillApplyPresentation {
    if (applyPresentation.successful) {
      pendingEdit?.let { markdownByName[it.name] = it.markdown }
      applyPresentation.inventory?.let { inventoryPresentation = it }
    }
    return applyPresentation
  }

  override suspend fun previewManagerAction(
    action: String,
    name: String,
    authoritativeSource: String?,
    targetIds: Set<String>,
  ): MachineSkillPreviewPresentation = error("Not used")

  override suspend fun inventory() = inventoryPresentation

  override suspend fun refreshInventory() = inventoryPresentation

  override suspend fun openManagedEdit(
    name: String,
    recordIdentity: String,
    sourceIdentity: String,
  ): ManagedMachineSkillEditPresentation {
    if (name == delayedOpenName) {
      delayedOpenStarted.complete(Unit)
      releaseDelayedOpen.await()
    }
    return ManagedMachineSkillEditPresentation(
      name = name,
      markdown = markdownByName.getValue(name),
      recordIdentity = recordIdentity,
      sourceIdentity = sourceIdentity,
    )
  }

  override suspend fun previewManagedEdit(edit: ManagedMachineSkillEditPresentation): MachineSkillPreviewPresentation {
    pendingEdit = edit
    return MachineSkillPreviewPresentation("edit-${edit.name}", emptyList(), emptyList())
  }

  override suspend fun revealSource(skillName: String): Result<Unit> = Result.success(Unit)

  override suspend fun acknowledgePostMortem(): Result<Unit> = Result.success(Unit)
}

private object UnusedFirstRunGateway : DesktopFirstRunGateway {
  override fun hasExistingInstall(): Boolean = true

  override fun latestReusableSetupRequest(legacyFallback: FirstRunSetupRequest?): FirstRunSetupRequest? = null

  override suspend fun discoverSetup(): FirstRunDiscoveryResult = error("Not used")

  override suspend fun planSetup(request: FirstRunSetupRequest): FirstRunPlanResult = error("Not used")

  override suspend fun applySetup(plan: FirstRunInstallPlan): FirstRunApplyResult = error("Not used")
}
