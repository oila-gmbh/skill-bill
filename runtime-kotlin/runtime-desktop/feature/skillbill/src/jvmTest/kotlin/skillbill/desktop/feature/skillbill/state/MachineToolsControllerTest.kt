package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.MachineSkillPreviewLine
import skillbill.desktop.core.domain.model.MachineSkillSourceSummary
import skillbill.desktop.core.domain.model.MachineSkillTargetOption
import skillbill.desktop.core.domain.model.MachineToolAction
import skillbill.desktop.core.domain.model.MachineToolsSurface
import skillbill.desktop.core.testing.FakeAuthoringGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachineToolsControllerTest {
  @Test
  fun `catalog and machine mutation state do not require repository state`() {
    val viewState = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(viewState)

    controller.dispatch(MachineToolAction.OPEN_CATALOG)
    assertEquals(MachineToolsSurface.CATALOG, viewState.currentState.machineTools.surface)
    assertNull(viewState.currentState.selectedRepoPath)
    assertTrue(controller.beginMutation())
    assertFalse(controller.beginMutation())
    assertNull(viewState.currentState.busyOperation)
    controller.finishMutation()
    assertFalse(viewState.currentState.machineTools.machineMutationBusy)
  }

  @Test
  fun `source assessment preselects only detected conflict free targets`() {
    val viewState = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(viewState)
    controller.sourceInspected(
      MachineSkillSourceSummary("demo", "Demo", "/tmp/demo", 2, 42, "hash"),
      listOf(
        MachineSkillTargetOption("codex", "codex", "/home/u/.codex/skills", true),
        MachineSkillTargetOption("claude", "claude", "/home/u/.claude/skills", true, "existing copy"),
        MachineSkillTargetOption("junie", "junie", "/home/u/.junie/skills", false),
      ),
    )

    val targets = viewState.currentState.machineTools.install.targets
    assertTrue(targets.single { it.id == "codex" }.selected)
    assertFalse(targets.single { it.id == "claude" }.selected)
    assertFalse(targets.single { it.id == "junie" }.selected)
  }

  @Test
  fun `stale source and preview completions are ignored`() {
    val viewState = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(viewState)
    val staleSource = controller.beginSourceInspection()
    val currentSource = controller.beginSourceInspection()

    controller.sourceInspected(staleSource, source("stale"), emptyList())
    assertNull(viewState.currentState.machineTools.install.source)
    controller.sourceInspected(currentSource, source("current"), emptyList())
    assertEquals("current", viewState.currentState.machineTools.install.source?.skillName)

    val stalePreview = controller.beginPreview()
    val currentPreview = controller.beginPreview()
    controller.previewReady(stalePreview, "old", emptyList(), emptyList())
    assertNull(viewState.currentState.machineTools.install.planId)
    controller.previewReady(
      currentPreview,
      "new",
      listOf(MachineSkillPreviewLine("CREATE", "/target", "link")),
      emptyList(),
    )
    assertEquals("new", viewState.currentState.machineTools.install.planId)
  }

  @Test
  fun `target changes invalidate previews but surface changes preserve shared inventory refresh`() {
    val viewState = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(viewState)
    controller.sourceInspected(source("demo"), listOf(MachineSkillTargetOption("codex", "codex", "/target", true)))
    val preview = controller.beginPreview()

    controller.toggleTarget("codex")
    controller.previewReady(preview, "stale", emptyList(), emptyList())
    assertNull(viewState.currentState.machineTools.install.planId)

    val inventory = controller.beginInventoryRefresh()
    controller.dispatch(MachineToolAction.INSTALL_SKILL)
    controller.inventoryRefreshed(inventory, emptyList(), null)
    assertFalse(viewState.currentState.machineTools.manager.loading)

    val dismissedInventory = controller.beginInventoryRefresh()
    controller.dismiss()
    controller.inventoryRefreshed(
      dismissedInventory,
      listOf(MachineSkillManagerRow("demo", "Demo", "MANAGED", "HEALTHY", setOf("codex"))),
      null,
    )
    assertEquals(listOf("demo"), viewState.currentState.machineTools.manager.rows.map { it.logicalKey })
  }

  @Test
  fun `source retry clears stale bundle and failures and agent filter is retained`() {
    val viewState = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(viewState)
    controller.sourceInspected(source("old"), emptyList())
    val token = controller.beginSourceInspection()
    assertNull(viewState.currentState.machineTools.install.source)
    controller.sourceFailed(token, "broken")
    assertNull(viewState.currentState.machineTools.install.source)
    controller.sourceInspected(controller.beginSourceInspection(), source("new"), emptyList())
    assertNull(viewState.currentState.machineTools.install.error)

    controller.inventoryRefreshed(
      controller.beginInventoryRefresh(),
      listOf(MachineSkillManagerRow("demo", "Demo", "MANAGED", "HEALTHY", setOf("codex"))),
      null,
    )
    controller.updateAgentFilter("codex")
    assertEquals("codex", viewState.currentState.machineTools.manager.agentFilter)
  }

  @Test
  fun `manager selection does not invalidate shared inventory refresh`() {
    val viewState = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(viewState)
    val token = controller.beginInventoryRefresh()

    controller.selectManagerSkill("demo")
    controller.inventoryRefreshed(
      token,
      listOf(MachineSkillManagerRow("demo", "Demo", "MANAGED", "HEALTHY", setOf("codex"))),
      null,
    )

    assertEquals(listOf("demo"), viewState.currentState.machineTools.manager.rows.map { it.name })
  }

  @Test
  fun `inventory failure clears loading and preserves the last accepted rows`() {
    val viewState = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(viewState)
    controller.inventoryRefreshed(
      controller.beginInventoryRefresh(),
      listOf(MachineSkillManagerRow("demo", "Demo", "UNMANAGED", "HEALTHY", setOf("codex"))),
      null,
    )

    controller.inventoryFailed(controller.beginInventoryRefresh(), "refresh failed")

    assertFalse(viewState.currentState.machineTools.manager.loading)
    assertEquals("refresh failed", viewState.currentState.machineTools.manager.error)
    assertEquals(listOf("demo"), viewState.currentState.machineTools.manager.rows.map { it.logicalKey })
  }

  @Test
  fun `divergent logical row stays deduplicated and requires occurrence guidance`() {
    val viewState = SkillBillViewState(FakeAuthoringGateway(), null)
    val controller = SkillBillMachineToolsController(viewState)
    controller.inventoryRefreshed(
      controller.beginInventoryRefresh(),
      listOf(
        MachineSkillManagerRow("Demo", "Demo", "UNMANAGED", "HEALTHY", setOf("codex", "claude"), "demo", true),
      ),
      null,
    )

    val root = viewState.currentState.treeItems.single { it.id == MACHINE_SKILLS_ROOT_ID }
    assertEquals("1", root.status)
    assertEquals("divergent", root.children.single().status)
  }

  private fun source(name: String) = MachineSkillSourceSummary(name, name, "/tmp/$name", 1, 1, name)
}
