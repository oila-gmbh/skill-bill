package skillbill.desktop.feature.skillbill.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.desktop.core.domain.model.MachineSkillSourceSummary
import skillbill.desktop.core.domain.model.MachineSkillPreviewLine
import skillbill.desktop.core.domain.model.MachineSkillTargetOption
import skillbill.desktop.core.domain.model.MachineToolAction
import skillbill.desktop.core.domain.model.MachineToolsSurface
import skillbill.desktop.core.testing.FakeAuthoringGateway

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
    controller.previewReady(currentPreview, "new", listOf(MachineSkillPreviewLine("CREATE", "/target", "link")), emptyList())
    assertEquals("new", viewState.currentState.machineTools.install.planId)
  }

  private fun source(name: String) = MachineSkillSourceSummary(name, name, "/tmp/$name", 1, 1, name)
}
