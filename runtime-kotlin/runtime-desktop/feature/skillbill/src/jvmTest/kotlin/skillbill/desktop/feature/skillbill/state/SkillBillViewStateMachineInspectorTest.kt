package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.testing.FakeAuthoringGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillBillViewStateMachineInspectorTest {
  @Test
  fun `repository document selection clears machine inspector detail`() {
    val authoring = FakeAuthoringGateway().apply { putDocument("repo-skill", "repository") }
    val state = SkillBillViewState(authoring, null)
    state.repositoryTreeItems = listOf(
      SkillBillTreeItem(
        id = "skills",
        label = "Skills",
        kind = TreeItemKind.GROUP,
        children = listOf(SkillBillTreeItem("repo-skill", "Repo skill", TreeItemKind.SKILL)),
      ),
    )
    state.selectedTreeItemId = "machine:third-party-skills:skill:demo"
    state.loadMachineEditorDocument(machineDocument(), detail = detail("old-snapshot"))

    state.selectedTreeItemId = "repo-skill"
    state.loadEditorForSelection()
    state.currentState = state.createState()

    assertNull(state.currentState.editor.machineSkillDetail)
    assertEquals("repository", state.currentState.editor.content)
  }

  @Test
  fun `accepted managed save inventory replaces machine inspector detail`() {
    val state = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(state)
    controller.inventoryRefreshed(
      controller.beginInventoryRefresh(),
      listOf(MachineSkillManagerRow("demo", "Demo", "MANAGED", "HEALTHY", setOf("codex"))),
      detail("old-snapshot"),
      mapOf("demo" to detail("old-snapshot")),
    )
    state.selectedTreeItemId = "machine:third-party-skills:skill:demo"
    state.loadMachineEditorDocument(machineDocument(), detail = detail("old-snapshot"))

    state.refreshMachineEditorDetail(detail("new-snapshot"))

    assertEquals("new-snapshot", state.currentState.editor.machineSkillDetail?.activeSnapshotHash)
    assertEquals(listOf("HEALTHY"), state.currentState.editor.machineSkillDetail?.targets?.single()?.linkHealth)
  }

  @Test
  fun `stale machine document cannot attach to a newer selection`() {
    val authoring = FakeAuthoringGateway().apply { putDocument("repo-skill", "repository") }
    val state = SkillBillViewState(authoring, null)
    state.repositoryTreeItems = listOf(
      SkillBillTreeItem(
        id = "skills",
        label = "Skills",
        kind = TreeItemKind.GROUP,
        children = listOf(SkillBillTreeItem("repo-skill", "Repo skill", TreeItemKind.SKILL)),
      ),
    )
    state.selectedTreeItemId = "repo-skill"
    state.loadEditorForSelection()

    state.loadMachineEditorDocument(machineDocument(), detail = detail("stale-snapshot"))
    state.currentState = state.createState()

    assertEquals("repo-skill", state.currentState.selectedTreeItemId)
    assertEquals("repository", state.currentState.editor.content)
    assertNull(state.currentState.editor.machineSkillDetail)
  }

  private fun machineDocument() = AuthoredContentDocument(
    treeItemId = "machine:third-party-skills:skill:demo",
    title = "demo",
    skillName = "demo",
    kind = "Third-party runtime skill",
    authoredPath = "/managed/demo/SKILL.md",
    text = "managed",
    editable = true,
  )

  private fun detail(snapshot: String) = MachineSkillManagerDetail(
    name = "demo",
    description = "Demo",
    ownership = "MANAGED",
    provenance = listOf("managed record"),
    canonicalManagedSourcePath = "/managed/demo/SKILL.md",
    activeSnapshotHash = snapshot,
    recordIdentity = "record-$snapshot",
    contentIdentity = "content-$snapshot",
    targets = listOf(
      skillbill.desktop.core.domain.model.MachineSkillTargetDetail(
        id = "codex",
        provider = "codex",
        path = "/home/user/.codex/skills/demo",
        detectionStatus = "DETECTED",
        state = "MANAGED",
        linkHealth = listOf("HEALTHY"),
      ),
    ),
    validationIssues = emptyList(),
  )
}
