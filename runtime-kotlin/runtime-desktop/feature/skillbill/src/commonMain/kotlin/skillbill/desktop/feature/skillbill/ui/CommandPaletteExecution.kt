package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.domain.model.CommandPaletteAction
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.ScaffoldKind

internal data class CommandPaletteActions(
  val selectTreeItem: (String) -> Unit,
  val openRepository: () -> Unit,
  val refresh: () -> Unit,
  val validate: () -> Unit,
  val render: () -> Unit,
  val showChanges: () -> Unit,
  val showHistory: () -> Unit,
  val save: () -> Unit,
  val refreshGitStatus: () -> Unit,
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
    CommandPaletteAction.VALIDATE -> actions.validate()
    CommandPaletteAction.RENDER -> actions.render()
    CommandPaletteAction.SHOW_CHANGES -> actions.showChanges()
    CommandPaletteAction.SHOW_HISTORY -> actions.showHistory()
    CommandPaletteAction.SAVE -> actions.save()
    CommandPaletteAction.REFRESH_GIT_STATUS -> actions.refreshGitStatus()
    CommandPaletteAction.NEW_HORIZONTAL_SKILL -> actions.openScaffoldWizard(ScaffoldKind.HORIZONTAL_SKILL)
    CommandPaletteAction.NEW_PLATFORM_PACK -> actions.openScaffoldWizard(ScaffoldKind.PLATFORM_PACK)
    CommandPaletteAction.NEW_PLATFORM_OVERRIDE -> actions.openScaffoldWizard(ScaffoldKind.PLATFORM_OVERRIDE_PILOTED)
    CommandPaletteAction.NEW_CODE_REVIEW_AREA -> actions.openScaffoldWizard(ScaffoldKind.CODE_REVIEW_AREA)
    CommandPaletteAction.NEW_ADD_ON -> actions.openScaffoldWizard(ScaffoldKind.ADD_ON)
  }
  return true
}
