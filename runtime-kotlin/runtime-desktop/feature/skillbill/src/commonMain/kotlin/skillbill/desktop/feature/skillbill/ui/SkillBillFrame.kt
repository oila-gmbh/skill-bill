@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import skillbill.desktop.core.designsystem.SkillBillColor
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.YamlSyntaxColors
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.MachineToolAction
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillTreeItem

internal val NavigationPaneResizeHandleWidth = SkillBillMetrics.navigationPaneResizeHandleWidth

internal data class CodePaneColors(
  val background: SkillBillColor,
  val editorText: SkillBillColor,
  val editorDisabledText: SkillBillColor,
  val editorCursor: SkillBillColor,
  val lineNumber: SkillBillColor,
  val flaggedBackground: SkillBillColor,
  val yaml: YamlSyntaxColors,
)

@Composable
internal fun codePaneColors(): CodePaneColors = CodePaneColors(
  background = SkillBillTheme.colors.background,
  editorText = SkillBillTheme.textFieldTokens.text,
  editorDisabledText = SkillBillTheme.textFieldTokens.disabledText,
  editorCursor = SkillBillTheme.textFieldTokens.cursor,
  lineNumber = SkillBillTheme.colors.onSurfaceVariant,
  flaggedBackground = SkillBillTheme.semanticTones.errorBanner.container,
  yaml = SkillBillTheme.syntaxTokens.yaml,
)

@Composable
internal fun workspacePrimaryControlForeground(): SkillBillColor = SkillBillTheme.frameTokens.onPrimary

private fun Dp.coerceNavigationPaneWidth(): Dp = when {
  this < SkillBillMetrics.navigationPaneMinWidth -> SkillBillMetrics.navigationPaneMinWidth
  this > SkillBillMetrics.navigationPaneMaxWidth -> SkillBillMetrics.navigationPaneMaxWidth
  else -> this
}

internal data class OpenEditorTab(
  val id: String,
  val title: String,
  val marker: String,
  val dirty: Boolean,
  val readOnly: Boolean,
  val readOnlyLabel: String?,
)

@Composable
fun SkillBillFrame(
  state: SkillBillState,
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onRepoPathChanged: (String) -> Unit,
  onRepoSelected: (String) -> Unit,
  onChooseRepoDirectory: () -> Unit,
  onRefresh: () -> Unit,
  onInstallSetup: () -> Unit,
  onReturnToInstalledWorkspace: () -> Unit,
  onEditorDraftChanged: (String) -> Unit,
  onEditorSave: () -> Unit,
  onEditorRevert: () -> Unit,
  onDirtyPromptDiscard: () -> Unit,
  onDirtyPromptCancel: () -> Unit,
  onTreeItemSelected: (String) -> Unit,
  onTreeItemExpandedToggled: (String) -> Unit,
  onMoveTreeSelection: (Int) -> Unit,
  onWorkToggled: () -> Unit = {},
  onWorkRefreshed: () -> Unit = {},
  onGeneratedArtifactResolvable: (String) -> Boolean,
  onGeneratedArtifactSelected: (String) -> Unit,
  onCommandPaletteOpen: () -> Unit,
  onCommandPaletteDismiss: () -> Unit,
  onCommandPaletteQueryChanged: (String) -> Unit,
  onCommandPaletteMoveSelection: (Int) -> Unit,
  onCommandPaletteExecuteSelected: () -> Unit,
  onCommandPaletteExecuteResult: (CommandPaletteResult) -> Unit,
  onOpenScaffoldWizard: (ScaffoldKind) -> Unit,
  onMachineToolAction: (MachineToolAction) -> Unit = {},
  onMachineToolsDismiss: () -> Unit = {},
  machineToolsCallbacks: MachineToolsCallbacks = MachineToolsCallbacks(),
  scaffoldWizardCallbacks: ScaffoldWizardCallbacks,
  firstRunSetupCallbacks: FirstRunSetupCallbacks,
  // SKILL-46: right-click → Delete… dialog. The route owns target resolution from the node id so
  // the frame stays free of repo/skill semantics.
  onShowDeleteContextMenu: (SkillBillTreeItem) -> Unit = {},
  confirmDeletionCallbacks: ConfirmDeletionCallbacks = ConfirmDeletionCallbacks.noop(),
) {
  var inspectorVisible by remember { mutableStateOf(true) }
  var navigationPaneWidth by remember {
    mutableStateOf(SkillBillMetrics.treePaneWidth.coerceNavigationPaneWidth())
  }
  var openEditorTabs by remember { mutableStateOf<List<OpenEditorTab>>(emptyList()) }
  val toolsFocusRequester = remember { FocusRequester() }
  val canActivateRepoScopedAction = state.busyOperation == null
  val frameAcceleratorPredicates = SkillBillAcceleratorPredicates(
    busyOperationActive = state.busyOperation != null,
    saveEnabled = state.editor.editable &&
      state.editor.dirty &&
      !state.editor.saveInProgress,
    refreshEnabled = canActivateRepoScopedAction,
    repoOpenEnabled = false,
  )
  LaunchedEffect(state.selectedRepoPath, state.treeItems) {
    openEditorTabs =
      if (state.selectedRepoPath == null) {
        emptyList()
      } else {
        openEditorTabs.filter { tab -> state.treeItems.containsTreeItemId(tab.id) }
      }
  }
  LaunchedEffect(
    state.selectedTreeItemId,
    state.editor.title,
    state.editor.authoredPath,
    state.editor.dirty,
    state.editor.editable,
    state.editor.readOnlyLabel,
  ) {
    val selectedId = state.selectedTreeItemId ?: return@LaunchedEffect
    val selectedItem = state.treeItems.findTreeItem(selectedId) ?: return@LaunchedEffect
    if (!state.editor.isDocumentLike()) {
      return@LaunchedEffect
    }
    val nextTab = OpenEditorTab(
      id = selectedId,
      title = state.editor.authoredPath ?: state.editor.title,
      marker = markerFor(selectedItem.kind),
      dirty = state.editor.dirty,
      readOnly = !state.editor.editable,
      readOnlyLabel = state.editor.readOnlyLabel,
    )
    openEditorTabs = openEditorTabs.upsertTab(nextTab)
  }
  val openEditorTabIds = openEditorTabs.mapTo(mutableSetOf(), OpenEditorTab::id)
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(SkillBillTheme.frameTokens.background)
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
          false
        } else {
          dispatchFrameKeyboardAccelerator(
            event = event.toKeyboardAcceleratorEvent(),
            predicates = frameAcceleratorPredicates,
            callbacks = FrameKeyboardAcceleratorCallbacks(
              openCommandPalette = onCommandPaletteOpen,
              save = onEditorSave,
              refresh = onRefresh,
            ),
          )
        }
      },
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      WorkspaceToolbar(
        canNavigateBack = canNavigateBack,
        onNavigateBack = onNavigateBack,
        onRefresh = onRefresh,
        onInstallSetup = onInstallSetup,
        onReturnToInstalledWorkspace = onReturnToInstalledWorkspace,
        inspectorVisible = inspectorVisible,
        onInspectorVisibilityToggle = { inspectorVisible = !inspectorVisible },
        onCommandPaletteOpen = onCommandPaletteOpen,
        onOpenScaffoldWizard = onOpenScaffoldWizard,
        installSetupEnabled = state.selectedRepoPath != null &&
          state.repoStatus.state == RepoLoadState.LOADED &&
          state.busyOperation == null &&
          state.scaffoldWizard == null &&
          state.firstRunSetup == null,
        returnToInstalledWorkspaceEnabled = state.canReturnToInstalledWorkspace &&
          state.busyOperation == null &&
          state.scaffoldWizard == null &&
          state.firstRunSetup == null,
        readOnlyModeLabel = state.statusBar.readOnlyModeLabel,
        busyOperation = state.busyOperation,
        scaffoldEnabled = state.selectedRepoPath != null &&
          state.repoStatus.state == RepoLoadState.LOADED &&
          state.busyOperation == null &&
          state.scaffoldWizard == null,
        onToolsOpen = { onMachineToolAction(MachineToolAction.OPEN_CATALOG) },
        toolsFocusRequester = toolsFocusRequester,
      )
      Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
        NavigationPane(
          paneWidth = navigationPaneWidth,
          repoPath = state.repoPathText,
          repoStatus = state.repoStatus,
          treeItems = state.treeItems,
          selectedNodeId = state.selectedTreeItemId,
          openEditorTabIds = openEditorTabIds,
          expandedNodeIds = state.expandedNodeIds,
          busyOperation = state.busyOperation,
          policyLabel = state.statusBar.policyLabel,
          readOnlyModeLabel = state.statusBar.readOnlyModeLabel,
          onRepoPathChanged = onRepoPathChanged,
          onRepoSelected = onRepoSelected,
          onChooseRepoDirectory = onChooseRepoDirectory,
          onNodeSelected = onTreeItemSelected,
          onNodeOpened = onTreeItemSelected,
          onNodeExpandedToggled = onTreeItemExpandedToggled,
          onMoveSelection = onMoveTreeSelection,
          onShowContextMenu = onShowDeleteContextMenu,
          workList = state.workList,
          workEnabled = state.busyOperation == null && state.firstRunSetup == null,
          onWorkToggled = onWorkToggled,
          onWorkRefreshed = onWorkRefreshed,
        )
        NavigationPaneResizeHandle(
          onResize = { delta ->
            navigationPaneWidth = (navigationPaneWidth + delta).coerceNavigationPaneWidth()
          },
        )
        CenterWorkspace(
          editor = state.editor,
          dirtyEditorPrompt = state.dirtyEditorPrompt,
          editorInputEnabled = state.busyOperation == null,
          onEditorDraftChanged = onEditorDraftChanged,
          onEditorSave = onEditorSave,
          onEditorRevert = onEditorRevert,
          onDirtyPromptDiscard = onDirtyPromptDiscard,
          onDirtyPromptCancel = onDirtyPromptCancel,
          openEditorTabs = openEditorTabs,
          selectedTreeItemId = state.selectedTreeItemId,
          onEditorTabSelected = onTreeItemSelected,
          onEditorTabClosed = { tabId ->
            val closingActiveTab = tabId == state.selectedTreeItemId
            val closingDirtyActiveTab = closingActiveTab && state.editor.dirty
            if (!closingDirtyActiveTab) {
              val nextTabs = openEditorTabs.filterNot { tab -> tab.id == tabId }
              val nextSelection = if (closingActiveTab) {
                openEditorTabs.nextEditorTabIdAfter(tabId)
              } else {
                null
              }
              openEditorTabs = nextTabs
              nextSelection?.let(onTreeItemSelected)
            }
          },
          modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (inspectorVisible) {
          VerticalDivider(color = SkillBillTheme.frameTokens.line, modifier = Modifier.fillMaxHeight())
          InspectorPane(
            editor = state.editor,
            repoStatus = state.repoStatus,
            onGeneratedArtifactResolvable = onGeneratedArtifactResolvable,
            onGeneratedArtifactSelected = onGeneratedArtifactSelected,
          )
        }
      }
      WorkspaceStatusBar(state = state)
    }
    if (state.commandPalette.open) {
      CommandPaletteOverlay(
        palette = state.commandPalette,
        onQueryChanged = onCommandPaletteQueryChanged,
        onMoveSelection = onCommandPaletteMoveSelection,
        onExecuteSelected = onCommandPaletteExecuteSelected,
        onExecuteResult = onCommandPaletteExecuteResult,
        onDismiss = onCommandPaletteDismiss,
        modifier = Modifier.align(Alignment.TopCenter),
      )
    }
    state.scaffoldWizard?.let { wizard ->
      ScaffoldWizardDialog(
        state = wizard,
        canStartScaffoldAction = state.busyOperation == null,
        callbacks = scaffoldWizardCallbacks,
      )
    }
    state.firstRunSetup?.let { setup ->
      FirstRunSetupDialog(state = setup, callbacks = firstRunSetupCallbacks)
    }
    state.confirmDeletion?.let { confirmation ->
      ConfirmDeletionDialog(
        state = confirmation,
        callbacks = confirmDeletionCallbacks,
      )
    }
    state.machineTools.surface?.let {
      MachineToolsDialog(
        state = state.machineTools,
        onAction = onMachineToolAction,
        callbacks = machineToolsCallbacks,
        onDismiss = {
          onMachineToolsDismiss()
          toolsFocusRequester.requestFocus()
        },
      )
    }
  }
}

private fun EditorPlaceholder.isDocumentLike(): Boolean =
  content != null || !authoredPath.isNullOrBlank() || !skillName.isNullOrBlank()

private fun List<OpenEditorTab>.upsertTab(tab: OpenEditorTab): List<OpenEditorTab> {
  val existingIndex = indexOfFirst { it.id == tab.id }
  return if (existingIndex < 0) {
    this + tab
  } else {
    toMutableList().also { tabs -> tabs[existingIndex] = tab }
  }
}

private fun List<OpenEditorTab>.nextEditorTabIdAfter(closedTabId: String): String? {
  val closedIndex = indexOfFirst { it.id == closedTabId }
  if (closedIndex < 0 || size <= 1) {
    return null
  }
  val nextIndex = if (closedIndex == lastIndex) closedIndex - 1 else closedIndex + 1
  return getOrNull(nextIndex)?.id
}

private fun List<SkillBillTreeItem>.containsTreeItemId(itemId: String): Boolean = findTreeItem(itemId) != null

private fun List<SkillBillTreeItem>.findTreeItem(itemId: String): SkillBillTreeItem? {
  for (item in this) {
    if (item.id == itemId) {
      return item
    }
    val childMatch = item.children.findTreeItem(itemId)
    if (childMatch != null) {
      return childMatch
    }
  }
  return null
}
