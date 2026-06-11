package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.domain.model.CommandPaletteAction
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.ScaffoldKind

internal data class CommandPaletteActions(
  val selectTreeItem: (String) -> Unit,
  val openRepository: () -> Unit,
  val refresh: () -> Unit,
  val save: () -> Unit,
  val openInstallSetup: () -> Unit = {},
  val openScaffoldWizard: (ScaffoldKind) -> Unit = {},
)

internal fun executeCommandPaletteResult(result: CommandPaletteResult, actions: CommandPaletteActions): Boolean {
  if (!result.enabled) {
    return false
  }
  when (result.action) {
    CommandPaletteAction.SELECT_TREE_ITEM -> {
      val treeItemId = result.treeItemId ?: return false
      actions.selectTreeItem(treeItemId)
    }
    CommandPaletteAction.OPEN_REPOSITORY -> actions.openRepository()
    CommandPaletteAction.REFRESH -> actions.refresh()
    CommandPaletteAction.SAVE -> actions.save()
    CommandPaletteAction.INSTALL_SETUP -> actions.openInstallSetup()
    CommandPaletteAction.NEW_HORIZONTAL_SKILL -> actions.openScaffoldWizard(ScaffoldKind.HORIZONTAL_SKILL)
    CommandPaletteAction.NEW_PLATFORM_PACK -> actions.openScaffoldWizard(ScaffoldKind.PLATFORM_PACK)
    CommandPaletteAction.NEW_ADD_ON -> actions.openScaffoldWizard(ScaffoldKind.ADD_ON)
  }
  return true
}
