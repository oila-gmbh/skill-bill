@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import skillbill.desktop.core.designsystem.SkillBillColor
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.YamlSyntaxColors
import skillbill.desktop.core.designsystem.contentColorFor
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.CommandPaletteResultKind
import skillbill.desktop.core.domain.model.CommandPaletteState
import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.SkillBillAcceleratorLabels
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.designsystem.SkillBillStatusTone as Tone

private val NavigationPaneMinWidth = 220.dp
private val NavigationPaneMaxWidth = 540.dp
private val NavigationPaneResizeHandleWidth = 7.dp

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
  this < NavigationPaneMinWidth -> NavigationPaneMinWidth
  this > NavigationPaneMaxWidth -> NavigationPaneMaxWidth
  else -> this
}

private data class OpenEditorTab(
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
  onGeneratedArtifactResolvable: (String) -> Boolean,
  onGeneratedArtifactSelected: (String) -> Unit,
  onCommandPaletteOpen: () -> Unit,
  onCommandPaletteDismiss: () -> Unit,
  onCommandPaletteQueryChanged: (String) -> Unit,
  onCommandPaletteMoveSelection: (Int) -> Unit,
  onCommandPaletteExecuteSelected: () -> Unit,
  onCommandPaletteExecuteResult: (CommandPaletteResult) -> Unit,
  onOpenScaffoldWizard: (ScaffoldKind) -> Unit,
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
  }
}

@Composable
private fun WorkspaceToolbar(
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onRefresh: () -> Unit,
  onInstallSetup: () -> Unit,
  onReturnToInstalledWorkspace: () -> Unit,
  inspectorVisible: Boolean,
  onInspectorVisibilityToggle: () -> Unit,
  onCommandPaletteOpen: () -> Unit,
  onOpenScaffoldWizard: (ScaffoldKind) -> Unit,
  installSetupEnabled: Boolean,
  returnToInstalledWorkspaceEnabled: Boolean,
  readOnlyModeLabel: String,
  busyOperation: SkillBillBusyOperation?,
  scaffoldEnabled: Boolean,
) {
  val busy = busyOperation != null
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(40.dp)
      .background(SkillBillTheme.frameTokens.background)
      .border(BorderStroke(0.dp, SkillBillTheme.frameTokens.transparent))
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (canNavigateBack) {
      ToolbarButton(label = "Back", marker = "<", enabled = !busy, onClick = onNavigateBack)
      Spacer(modifier = Modifier.width(8.dp))
      ToolbarDivider()
    }
    ToolbarButton(
      label = "Refresh",
      marker = "rf",
      enabled = !busy,
      acceleratorLabel = SkillBillAcceleratorLabels.REFRESH,
      onClick = onRefresh,
    )
    ToolbarButton(
      label = "Install",
      marker = "in",
      enabled = installSetupEnabled,
      onClick = onInstallSetup,
    )
    ToolbarButton(
      label = "Open installed",
      marker = "iw",
      contentDescription = "Open installed workspace",
      enabled = returnToInstalledWorkspaceEnabled,
      onClick = onReturnToInstalledWorkspace,
    )
    NewScaffoldMenuButton(enabled = scaffoldEnabled, onOpenScaffoldWizard = onOpenScaffoldWizard)
    ToolbarDivider()
    // F-X-901 (AC6): file editability is a status indicator, not a toggle. Render as a status chip
    // without click semantics.
    ToolbarStatusItem(
      label = readOnlyModeLabel,
      marker = fileModeMarker(readOnlyModeLabel),
      primary = readOnlyModeLabel != SkillBillStatusBar.READ_ONLY_MODE_LABEL,
    )
    if (busyOperation != null) {
      BusyIndicator(busyOperation)
    }
    Spacer(modifier = Modifier.weight(1f))
    CommandSearchButton(onClick = onCommandPaletteOpen)
    Spacer(modifier = Modifier.width(10.dp))
    ToolbarSidePanelButton(
      contentDescription = if (inspectorVisible) "Hide details panel" else "Show details panel",
      selected = inspectorVisible,
      enabled = !busy,
      onClick = onInspectorVisibilityToggle,
    )
  }
}

// F-X-901 (AC1/AC5): no default `onClick = {}` — every call site must pass an explicit handler so
// dead affordances cannot be silently inherited. `contentDescription` defaults to `label` but may
// be overridden when the visible label and announced intent differ (e.g. the "NEW..." disclosure
// button, which announces "Open new scaffold menu").
@Composable
private fun ToolbarButton(
  label: String,
  marker: String,
  onClick: () -> Unit,
  primary: Boolean = false,
  enabled: Boolean = true,
  contentDescription: String = label,
  acceleratorLabel: String? = null,
) {
  val background = if (primary) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.raised
  val foreground =
    when {
      !enabled -> SkillBillTheme.frameTokens.subtle
      primary -> workspacePrimaryControlForeground()
      else -> SkillBillTheme.frameTokens.text
    }
  val border = if (primary) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.line
  AcceleratorTooltip(label = label, acceleratorLabel = acceleratorLabel) {
    Row(
      modifier =
      Modifier
        .height(28.dp)
        .padding(end = 6.dp)
        .clip(RoundedCornerShape(6.dp))
        .border(1.dp, border, RoundedCornerShape(6.dp))
        .background(background)
        // F-X-901-A: merge child Text/icon semantics into the clickable node so screen readers
        // announce a single actionable element instead of three, and `disabled()` propagates to the
        // merged node when `enabled = false`.
        .semantics(mergeDescendants = true) {
          this.contentDescription = contentDescription
          if (!enabled) this.disabled()
        }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      MiniIcon(text = marker, tint = foreground)
      Text(
        text = label,
        color = foreground,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ToolbarSidePanelButton(
  contentDescription: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val foreground =
    when {
      !enabled -> SkillBillTheme.frameTokens.subtle
      selected -> SkillBillTheme.frameTokens.primary
      else -> SkillBillTheme.frameTokens.text
    }
  val border = if (selected) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.line
  Box(
    modifier = Modifier
      .size(width = 30.dp, height = 28.dp)
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, border, RoundedCornerShape(6.dp))
      .background(SkillBillTheme.frameTokens.raised)
      .semantics(mergeDescendants = true) {
        this.contentDescription = contentDescription
        this.stateDescription = if (selected) "visible" else "hidden"
        if (!enabled) this.disabled()
      }
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    SidePanelIcon(tint = foreground, panelVisible = selected)
  }
}

@Composable
private fun SidePanelIcon(tint: SkillBillColor, panelVisible: Boolean) {
  Canvas(modifier = Modifier.size(width = 15.dp, height = 14.dp)) {
    val strokeWidth = 1.4.dp.toPx()
    val cornerInset = strokeWidth / 2f
    val bodyWidth = size.width - strokeWidth
    val bodyHeight = size.height - strokeWidth
    val panelWidth = bodyWidth * 0.34f
    drawRect(
      color = tint,
      topLeft = Offset(cornerInset, cornerInset),
      size = Size(bodyWidth, bodyHeight),
      style = Stroke(width = strokeWidth),
    )
    if (panelVisible) {
      drawRect(
        color = tint.copy(alpha = 0.28f),
        topLeft = Offset(size.width - panelWidth, cornerInset + strokeWidth),
        size = Size(panelWidth - strokeWidth, bodyHeight - strokeWidth * 2f),
      )
    }
    val dividerX = size.width - panelWidth
    drawLine(
      color = tint,
      start = Offset(dividerX, cornerInset),
      end = Offset(dividerX, size.height - cornerInset),
      strokeWidth = strokeWidth,
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AcceleratorTooltip(label: String, acceleratorLabel: String?, content: @Composable () -> Unit) {
  if (acceleratorLabel == null) {
    content()
    return
  }
  TooltipArea(
    tooltip = {
      Box(
        modifier = Modifier
          .background(SkillBillTheme.frameTokens.raised, RoundedCornerShape(4.dp))
          .border(1.dp, SkillBillTheme.frameTokens.line, RoundedCornerShape(4.dp))
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        Text(
          text = "$label - $acceleratorLabel",
          color = SkillBillTheme.frameTokens.text,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
    },
    content = content,
  )
}

/**
 * F-X-901 (AC5/AC6): toolbar chip used for status indicators (branch label, file editability).
 * Has the same visual treatment as [ToolbarButton] but no .clickable, no Role.Button, no
 * hover/press affordance. Accessibility announces the value as plain text so screen readers do not
 * advertise a tappable affordance.
 */
@Composable
private fun ToolbarStatusItem(label: String, marker: String, primary: Boolean = false) {
  val background = if (primary) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.raised
  val foreground = if (primary) workspacePrimaryControlForeground() else SkillBillTheme.frameTokens.text
  val border = if (primary) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.line
  Row(
    modifier =
    Modifier
      .height(28.dp)
      .padding(end = 6.dp)
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, border, RoundedCornerShape(6.dp))
      .background(background)
      // F-X-901-G: merge the inner Text/MiniIcon semantics into one node so screen readers
      // announce the chip as a single status string rather than three separate elements.
      .semantics(mergeDescendants = true) { this.contentDescription = label }
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    MiniIcon(text = marker, tint = foreground)
    Text(
      text = label,
      color = foreground,
      fontSize = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * F-X-901 (AC7): toolbar "NEW..." entry point. Renders as a [ToolbarButton] and, when activated,
 * opens a kind picker over every active creation [ScaffoldKind] reachable from the wizard's
 * [KindPicker]. Each entry invokes [onOpenScaffoldWizard] with the corresponding kind.
 *
 * F-X-901-C: migrated from a bare `Popup` to Material3 [DropdownMenu] so keyboard navigation,
 * focus management, and Escape-to-close are provided by the framework rather than reimplemented.
 *
 * F-X-901-D: the parent ToolbarButton announces a distinct `contentDescription` ("Open new
 * scaffold menu") plus a `stateDescription` reflecting expansion, so screen readers don't recite
 * the verbatim label ("NEW dot dot dot") and users hear when the menu opens or closes.
 *
 * F-X-901-F: closes the menu automatically when the parent's `enabled` flag flips to false, so
 * items cannot remain selectable after the toolbar disables.
 */
@Composable
private fun NewScaffoldMenuButton(enabled: Boolean, onOpenScaffoldWizard: (ScaffoldKind) -> Unit) {
  var menuOpen by remember { mutableStateOf(false) }
  LaunchedEffect(enabled) {
    if (!enabled) menuOpen = false
  }
  Box(
    modifier = Modifier.semantics {
      stateDescription = if (menuOpen) "Expanded" else "Collapsed"
    },
  ) {
    ToolbarButton(
      label = "NEW...",
      marker = "nw",
      enabled = enabled,
      onClick = { menuOpen = true },
      contentDescription = "Open new scaffold menu",
    )
    DropdownMenu(
      expanded = menuOpen,
      onDismissRequest = { menuOpen = false },
      modifier = Modifier
        .background(SkillBillTheme.frameTokens.panel)
        .border(1.dp, SkillBillTheme.frameTokens.line, RoundedCornerShape(6.dp)),
    ) {
      ScaffoldKind.activeCreationValues().forEach { kind ->
        DropdownMenuItem(
          text = {
            Text(
              text = kind.displayLabel,
              color = SkillBillTheme.frameTokens.text,
              fontSize = 12.sp,
              maxLines = 1,
            )
          },
          colors = MenuDefaults.itemColors(textColor = SkillBillTheme.frameTokens.text),
          modifier = Modifier
            .widthIn(min = 220.dp)
            .semantics { contentDescription = "Open ${kind.displayLabel} wizard" },
          onClick = {
            menuOpen = false
            onOpenScaffoldWizard(kind)
          },
        )
      }
    }
  }
}

@Composable
private fun BusyIndicator(busyOperation: SkillBillBusyOperation) {
  val label = when (busyOperation) {
    SkillBillBusyOperation.OPEN_REPO -> "Opening..."
    SkillBillBusyOperation.REFRESH -> "Refreshing..."
    SkillBillBusyOperation.CHOOSE_DIRECTORY -> "Choosing..."
    SkillBillBusyOperation.SAVE -> "Saving..."
    SkillBillBusyOperation.SCAFFOLD -> "Scaffolding..."
    SkillBillBusyOperation.FIRST_RUN_SETUP -> "Setting up..."
    SkillBillBusyOperation.DELETE -> "Deleting..."
    SkillBillBusyOperation.VALIDATE_AGENT_CONFIGS -> "Validating agent configs..."
  }
  Row(
    modifier = Modifier.padding(start = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    MiniIcon(text = "..", tint = SkillBillTheme.frameTokens.primary)
    Text(text = label, color = SkillBillTheme.frameTokens.muted, fontSize = 11.sp, maxLines = 1)
  }
}

@Composable
private fun ToolbarDivider() {
  Box(
    modifier =
    Modifier
      .padding(horizontal = 5.dp)
      .width(1.dp)
      .height(20.dp)
      .background(SkillBillTheme.frameTokens.line),
  )
}

@Composable
private fun CommandSearchButton(onClick: () -> Unit) {
  Row(
    modifier =
    Modifier
      .width(288.dp)
      .height(28.dp)
      .border(1.dp, SkillBillTheme.frameTokens.line, RoundedCornerShape(6.dp))
      .background(SkillBillTheme.frameTokens.raised, RoundedCornerShape(6.dp))
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "sr", tint = SkillBillTheme.frameTokens.muted)
    Text(
      text = "Find skill, intent, or command...",
      color = SkillBillTheme.frameTokens.subtle,
      fontSize = 12.sp,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = SkillBillAcceleratorLabels.COMMAND_PALETTE,
      color = SkillBillTheme.frameTokens.subtle,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
    )
  }
}

private fun androidx.compose.ui.input.key.KeyEvent.isActivationKey(): Boolean = generatedArtifactRowActivatesForKey(key)

private fun androidx.compose.ui.input.key.KeyEvent.toKeyboardAcceleratorEvent(): KeyboardAcceleratorEvent =
  KeyboardAcceleratorEvent(
    key = key.toKeyboardAcceleratorKey(),
    commandPressed = isCtrlPressed || isMetaPressed,
    shiftPressed = isShiftPressed,
  )

private fun Key.toKeyboardAcceleratorKey(): KeyboardAcceleratorKey = when (this) {
  Key.Enter -> KeyboardAcceleratorKey.ENTER
  Key.NumPadEnter -> KeyboardAcceleratorKey.NUMPAD_ENTER
  Key.K -> KeyboardAcceleratorKey.K
  Key.P -> KeyboardAcceleratorKey.P
  Key.R -> KeyboardAcceleratorKey.R
  Key.S -> KeyboardAcceleratorKey.S
  else -> KeyboardAcceleratorKey.UNKNOWN
}

internal fun generatedArtifactRowActivatesForKey(key: Key): Boolean =
  key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar

@Composable
private fun CommandPaletteOverlay(
  palette: CommandPaletteState,
  onQueryChanged: (String) -> Unit,
  onMoveSelection: (Int) -> Unit,
  onExecuteSelected: () -> Unit,
  onExecuteResult: (CommandPaletteResult) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(palette.open) {
    if (palette.open) {
      focusRequester.requestFocus()
    }
  }
  Box(modifier = Modifier.fillMaxSize()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(SkillBillTheme.semanticTones.scrim)
        .clickable(role = Role.Button, onClick = onDismiss),
    )
    Column(
      modifier = modifier
        .padding(top = 54.dp)
        .widthIn(min = 520.dp, max = 720.dp)
        .heightIn(max = 480.dp)
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, SkillBillTheme.frameTokens.line, RoundedCornerShape(8.dp))
        .background(SkillBillTheme.frameTokens.panel)
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) {
            false
          } else {
            when (event.key) {
              Key.Escape -> {
                onDismiss()
                true
              }
              Key.DirectionDown -> {
                onMoveSelection(1)
                true
              }
              Key.DirectionUp -> {
                onMoveSelection(-1)
                true
              }
              Key.Enter, Key.NumPadEnter -> {
                onExecuteSelected()
                true
              }
              else -> false
            }
          }
        },
    ) {
      CommandPaletteInput(
        query = palette.query,
        onQueryChanged = onQueryChanged,
        focusRequester = focusRequester,
      )
      HorizontalDivider(color = SkillBillTheme.frameTokens.line)
      CommandPaletteResults(
        palette = palette,
        onExecuteResult = onExecuteResult,
        modifier = Modifier.fillMaxWidth().heightIn(max = 410.dp).verticalScroll(rememberScrollState()),
      )
    }
  }
}

@Composable
private fun CommandPaletteInput(query: String, onQueryChanged: (String) -> Unit, focusRequester: FocusRequester) {
  val textFieldTokens = SkillBillTheme.textFieldTokens
  Row(
    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    MiniIcon(text = "cmd", tint = SkillBillTheme.frameTokens.primary)
    Box(modifier = Modifier.weight(1f)) {
      if (query.isBlank()) {
        Text(
          text = "Search commands and source items",
          color = textFieldTokens.placeholder,
          fontSize = 14.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      BasicTextField(
        value = query,
        onValueChange = onQueryChanged,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
          color = textFieldTokens.text,
          fontSize = 14.sp,
          fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = SolidColor(textFieldTokens.cursor),
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
      )
    }
  }
}

@Composable
private fun CommandPaletteResults(
  palette: CommandPaletteState,
  onExecuteResult: (CommandPaletteResult) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.padding(vertical = 6.dp)) {
    if (palette.results.isEmpty()) {
      Text(
        text = "No matching commands",
        color = SkillBillTheme.frameTokens.subtle,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
    }
    palette.results.forEachIndexed { index, result ->
      CommandPaletteResultRow(
        result = result,
        selected = index == palette.selectedResultIndex,
        onExecute = { onExecuteResult(result) },
      )
    }
  }
}

@Composable
private fun CommandPaletteResultRow(result: CommandPaletteResult, selected: Boolean, onExecute: () -> Unit) {
  val enabled = result.enabled
  val background =
    when {
      selected -> SkillBillTheme.frameTokens.primary.copy(alpha = 0.14f)
      else -> SkillBillTheme.frameTokens.transparent
    }
  val titleColor =
    when {
      !enabled -> SkillBillTheme.frameTokens.subtle
      selected -> SkillBillTheme.frameTokens.text
      else -> SkillBillTheme.frameTokens.text.copy(alpha = 0.92f)
    }
  val subtitleColor = if (enabled) SkillBillTheme.frameTokens.muted else SkillBillTheme.frameTokens.subtle
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .padding(horizontal = 8.dp, vertical = 2.dp)
      .clip(RoundedCornerShape(5.dp))
      .background(background)
      .semantics {
        this.selected = selected
        this.role = Role.Button
        if (!enabled) {
          disabled()
        }
      }
      .clickable(enabled = enabled, role = Role.Button, onClick = onExecute)
      .padding(horizontal = 10.dp, vertical = 7.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    val markerTint = if (selected && enabled) {
      SkillBillTheme.frameTokens.primary
    } else {
      SkillBillTheme.frameTokens.subtle
    }
    MiniIcon(text = result.marker, tint = markerTint)
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = result.title,
          color = titleColor,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        result.acceleratorLabel?.let { acceleratorLabel ->
          CommandPaletteAcceleratorLabel(acceleratorLabel)
        }
        CommandPaletteKindLabel(result.kind)
      }
      Text(
        text = result.disabledReason ?: result.subtitle,
        color = if (result.disabledReason == null) subtitleColor else SkillBillTheme.frameTokens.status.warning,
        fontSize = 11.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun CommandPaletteAcceleratorLabel(label: String) {
  Text(
    text = label,
    color = SkillBillTheme.frameTokens.muted,
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    maxLines = 1,
  )
}

@Composable
private fun CommandPaletteKindLabel(kind: CommandPaletteResultKind) {
  val label = when (kind) {
    CommandPaletteResultKind.COMMAND -> "command"
    CommandPaletteResultKind.TREE_ITEM -> "source"
  }
  Text(
    text = label,
    color = SkillBillTheme.frameTokens.subtle,
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    maxLines = 1,
  )
}

@Composable
private fun NavigationPane(
  paneWidth: Dp,
  repoPath: String,
  repoStatus: RepoLoadStatus,
  treeItems: List<SkillBillTreeItem>,
  selectedNodeId: String?,
  openEditorTabIds: Set<String>,
  expandedNodeIds: Set<String>,
  busyOperation: SkillBillBusyOperation?,
  policyLabel: String,
  readOnlyModeLabel: String,
  onRepoPathChanged: (String) -> Unit,
  onRepoSelected: (String) -> Unit,
  onChooseRepoDirectory: () -> Unit,
  onNodeSelected: (String) -> Unit,
  onNodeOpened: (String) -> Unit,
  onNodeExpandedToggled: (String) -> Unit,
  onMoveSelection: (Int) -> Unit,
  onShowContextMenu: (SkillBillTreeItem) -> Unit = {},
) {
  val busy = busyOperation != null
  Column(
    modifier =
    Modifier
      .width(paneWidth)
      .fillMaxHeight()
      .background(SkillBillTheme.frameTokens.sidebar),
  ) {
    RepositorySelector(
      repoPath = repoPath,
      repoStatus = repoStatus,
      busy = busy,
      onRepoPathChanged = onRepoPathChanged,
      onRepoSelected = onRepoSelected,
      onChooseRepoDirectory = onChooseRepoDirectory,
    )
    Column(
      modifier =
      Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .onPreviewKeyEvent { event ->
          if (busy || event.type != KeyEventType.KeyDown) {
            false
          } else {
            when (event.key) {
              Key.DirectionDown -> {
                onMoveSelection(1)
                true
              }
              Key.DirectionUp -> {
                onMoveSelection(-1)
                true
              }
              Key.DirectionRight -> toggleSelectedNavigationExpansion(
                treeItems = treeItems,
                selectedNodeId = selectedNodeId,
                expandedNodeIds = expandedNodeIds,
                expand = true,
                onNodeExpandedToggled = onNodeExpandedToggled,
              )
              Key.DirectionLeft -> toggleSelectedNavigationExpansion(
                treeItems = treeItems,
                selectedNodeId = selectedNodeId,
                expandedNodeIds = expandedNodeIds,
                expand = false,
                onNodeExpandedToggled = onNodeExpandedToggled,
              )
              else -> false
            }
          }
        }
        .focusable()
        .padding(vertical = 6.dp),
    ) {
      if (treeItems.isEmpty()) {
        EmptyTreeMessage(repoStatus)
      }
      treeItems.forEach { group ->
        NavGroup(
          group = group,
          selectedNodeId = selectedNodeId,
          openEditorTabIds = openEditorTabIds,
          expandedNodeIds = expandedNodeIds,
          expanded = group.id in expandedNodeIds,
          enabled = !busy,
          onNodeSelected = onNodeSelected,
          onNodeOpened = onNodeOpened,
          onNodeExpandedToggled = onNodeExpandedToggled,
          onShowContextMenu = onShowContextMenu,
        )
      }
      HorizontalDivider(
        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
        color = SkillBillTheme.frameTokens.line,
      )
      // F-X-901: File editability is a workspace-wide status indicator, not an action. Render it
      // as a labeled status row (no clickable, no Role.Button) mirroring StatusItem in the bottom
      // status bar, so accessibility semantics match real behavior.
      RepositoryStatusItem(
        label = "File mode",
        statusText = readOnlyModeLabel,
        marker = fileModeMarker(readOnlyModeLabel),
        enabled = !busy,
      )
    }
    Row(
      modifier =
      Modifier
        .fillMaxWidth()
        .height(35.dp)
        .border(BorderStroke(0.dp, SkillBillTheme.frameTokens.transparent))
        .background(SkillBillTheme.frameTokens.sidebar)
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      MiniIcon(text = "lk", tint = SkillBillTheme.frameTokens.subtle)
      Text(text = "contract policy:", color = SkillBillTheme.frameTokens.subtle, fontSize = 11.sp)
      Text(text = policyLabel, color = SkillBillTheme.frameTokens.text, fontSize = 11.sp)
    }
  }
}

@Composable
private fun RepositorySelector(
  repoPath: String,
  repoStatus: RepoLoadStatus,
  busy: Boolean,
  onRepoPathChanged: (String) -> Unit,
  onRepoSelected: (String) -> Unit,
  onChooseRepoDirectory: () -> Unit,
) {
  val textFieldTokens = SkillBillTheme.textFieldTokens
  var repoPathFocused by remember { mutableStateOf(false) }
  val acceleratorPredicates = SkillBillAcceleratorPredicates(
    busyOperationActive = busy,
    saveEnabled = false,
    refreshEnabled = false,
    repoOpenEnabled = !busy,
  )
  Column(
    modifier =
    Modifier
      .fillMaxWidth()
      .border(BorderStroke(0.dp, SkillBillTheme.frameTokens.transparent))
      .padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    LabelText("Repository")
    Row(
      modifier =
      Modifier
        .fillMaxWidth()
        .height(32.dp)
        .border(
          1.dp,
          when {
            busy -> textFieldTokens.disabledBorder
            repoPathFocused -> textFieldTokens.focusedBorder
            else -> textFieldTokens.border
          },
          RoundedCornerShape(6.dp),
        )
        .background(
          if (busy) textFieldTokens.disabledContainer else textFieldTokens.container,
          RoundedCornerShape(6.dp),
        )
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MiniIcon(text = "db", tint = SkillBillTheme.frameTokens.primary)
      BasicTextField(
        value = repoPath,
        onValueChange = onRepoPathChanged,
        enabled = !busy,
        textStyle = androidx.compose.ui.text.TextStyle(
          color = if (busy) textFieldTokens.disabledText else textFieldTokens.text,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
        ),
        singleLine = true,
        cursorBrush = SolidColor(textFieldTokens.cursor),
        modifier = Modifier
          .weight(1f)
          .onFocusChanged { repoPathFocused = it.isFocused }
          .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
              false
            } else {
              dispatchRepositoryPathKeyboardAccelerator(
                event = event.toKeyboardAcceleratorEvent(),
                predicates = acceleratorPredicates,
                onOpenRepositoryPath = { onRepoSelected(repoPath) },
              )
            }
          },
      )
      // F-U05 / F-X-501: enlarge hit target and announce intent for the text-as-button actions.
      AcceleratorTooltip(label = "Open repository at path", acceleratorLabel = SkillBillAcceleratorLabels.REPO_OPEN) {
        Text(
          text = if (busy) "Busy" else "Open",
          color = if (busy) SkillBillTheme.frameTokens.subtle else SkillBillTheme.frameTokens.primary,
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier
            .iconButtonSemantics(description = "Open repository at path")
            .clickable(enabled = !busy, role = Role.Button) { onRepoSelected(repoPath) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        )
      }
      Text(
        text = "...",
        color = if (busy) SkillBillTheme.frameTokens.subtle else SkillBillTheme.frameTokens.primary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
          .iconButtonSemantics(description = "Choose repository directory")
          .clickable(enabled = !busy, role = Role.Button, onClick = onChooseRepoDirectory)
          .padding(horizontal = 6.dp, vertical = 4.dp),
      )
    }
    Text(
      text = repoStatus.message,
      color = if (repoStatus.state == RepoLoadState.INVALID) {
        SkillBillTheme.frameTokens.status.error
      } else {
        SkillBillTheme.frameTokens.subtle
      },
      fontSize = 10.sp,
      modifier = Modifier.padding(top = 6.dp),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun EmptyTreeMessage(repoStatus: RepoLoadStatus) {
  Text(
    text = repoStatus.message,
    color = if (repoStatus.state == RepoLoadState.INVALID) {
      SkillBillTheme.frameTokens.status.error
    } else {
      SkillBillTheme.frameTokens.subtle
    },
    fontSize = 12.sp,
    modifier = Modifier.padding(12.dp),
  )
}

@Composable
private fun NavigationPaneResizeHandle(onResize: (Dp) -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxHeight()
      .width(NavigationPaneResizeHandleWidth)
      .background(SkillBillTheme.frameTokens.background)
      .pointerInput(Unit) {
        detectHorizontalDragGestures { change, dragAmount ->
          change.consume()
          onResize(dragAmount.toDp())
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .width(2.dp)
        .fillMaxHeight()
        .background(SkillBillTheme.frameTokens.line),
    )
  }
}

internal fun toggleSelectedNavigationExpansion(
  treeItems: List<SkillBillTreeItem>,
  selectedNodeId: String?,
  expandedNodeIds: Set<String>,
  expand: Boolean,
  onNodeExpandedToggled: (String) -> Unit,
): Boolean {
  val selectedNode = treeItems.findNavigationNode(selectedNodeId) ?: return false
  if (selectedNode.children.isEmpty()) {
    return false
  }
  val currentlyExpanded = selectedNode.id in expandedNodeIds
  if (currentlyExpanded == expand) {
    return false
  }
  onNodeExpandedToggled(selectedNode.id)
  return true
}

private fun List<SkillBillTreeItem>.findNavigationNode(nodeId: String?): SkillBillTreeItem? {
  if (nodeId == null) {
    return null
  }
  for (item in this) {
    if (item.id == nodeId) {
      return item
    }
    item.children.findNavigationNode(nodeId)?.let { return it }
  }
  return null
}

@Composable
private fun NavGroup(
  group: SkillBillTreeItem,
  selectedNodeId: String?,
  openEditorTabIds: Set<String>,
  expandedNodeIds: Set<String>,
  expanded: Boolean,
  enabled: Boolean,
  onNodeSelected: (String) -> Unit,
  onNodeOpened: (String) -> Unit,
  onNodeExpandedToggled: (String) -> Unit,
  onShowContextMenu: (SkillBillTreeItem) -> Unit = {},
) {
  val selected = selectedNodeId == group.id
  val rowBackground = if (selected) {
    SkillBillTheme.frameTokens.primary.copy(alpha = 0.15f)
  } else {
    SkillBillTheme.frameTokens.transparent
  }
  val iconTint = if (selected) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.subtle
  val textColor =
    when {
      selected -> SkillBillTheme.frameTokens.text
      else -> SkillBillTheme.frameTokens.subtle
    }
  // SKILL-46 / AC1: per-row state for the right-click context menu (DropdownMenu with single
  // `Delete…` item). The menu is rendered at the end of the Row so it anchors to this row's
  // bounds; clicking the item triggers the actual confirmation dialog via `onShowContextMenu`.
  var menuExpanded by remember(group.id) { mutableStateOf(false) }
  Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
    Row(
      modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 27.dp)
        .padding(start = 2.dp, end = 4.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(rowBackground)
        // F-U05 / F-X-501: announce the toggle action so screen readers can distinguish it from
        // sibling rows; selection state stays on the row for tree semantics.
        .semantics {
          this.selected = selected
          this.contentDescription = "Toggle group ${group.label}"
          this.role = Role.Button
        }
        .clickable(enabled = enabled, role = Role.Button) { onNodeExpandedToggled(group.id) }
        // SKILL-46: synthetic PLATFORM_PACK group nodes (id `platform:<slug>`) also support
        // right-click → Delete. Generic GROUP nodes ignore the secondary press because the route
        // filters on `kind ∈ {SKILL, PLATFORM_PACK, ADD_ON}`.
        .skillRemoveContextMenuModifier(group, enabled) { menuExpanded = true }
        .padding(end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier =
        Modifier
          .width(3.dp)
          .fillMaxHeight()
          .background(if (selected) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.transparent),
      )
      Text(text = if (expanded) "v" else ">", color = iconTint, fontSize = 12.sp)
      MiniIcon(text = markerFor(group.kind), tint = iconTint)
      Text(
        text = group.label,
        color = textColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        modifier = Modifier.weight(1f),
      )
      Text(text = group.children.size.toString(), color = iconTint, fontSize = 11.sp)
      DropdownMenu(
        expanded = menuExpanded,
        onDismissRequest = { menuExpanded = false },
      ) {
        DropdownMenuItem(
          text = { Text("Delete…") },
          onClick = {
            menuExpanded = false
            onShowContextMenu(group)
          },
        )
      }
    }
    if (expanded) {
      group.children.forEach { node ->
        NavTreeNode(
          node = node,
          selected = selectedNodeId == node.id,
          open = treeSingleClickSwitchesToOpenTab(node.id, openEditorTabIds),
          selectedNodeId = selectedNodeId,
          openEditorTabIds = openEditorTabIds,
          expandedNodeIds = expandedNodeIds,
          enabled = enabled,
          depth = 0,
          onNodeSelected = onNodeSelected,
          onNodeOpened = onNodeOpened,
          onNodeExpandedToggled = onNodeExpandedToggled,
          onShowContextMenu = onShowContextMenu,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavTreeNode(
  node: SkillBillTreeItem,
  selected: Boolean,
  open: Boolean,
  selectedNodeId: String?,
  openEditorTabIds: Set<String>,
  expandedNodeIds: Set<String>,
  enabled: Boolean,
  depth: Int,
  onNodeSelected: (String) -> Unit,
  onNodeOpened: (String) -> Unit,
  onNodeExpandedToggled: (String) -> Unit,
  onShowContextMenu: (SkillBillTreeItem) -> Unit = {},
) {
  val expandable = node.children.isNotEmpty()
  val expanded = node.id in expandedNodeIds
  // SKILL-46 / AC1: per-row state for the right-click context menu; flipped to true by the
  // `skillRemoveContextMenuModifier` and rendered via DropdownMenu inside the Row content.
  var menuExpanded by remember(node.id) { mutableStateOf(false) }
  val rowBackground = when {
    selected -> SkillBillTheme.frameTokens.primary.copy(alpha = 0.15f)
    open -> SkillBillTheme.frameTokens.primary.copy(alpha = 0.06f)
    else -> SkillBillTheme.frameTokens.transparent
  }
  val iconTint = if (selected || open) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.subtle
  val textAlpha =
    when {
      !enabled -> 0.42f
      selected -> 1f
      open -> 0.96f
      else -> 0.86f
    }
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .padding(start = 2.dp, end = 4.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(rowBackground)
      .semantics {
        this.selected = selected
        stateDescription = treeRowStateDescription(open)
      }
      .combinedClickable(
        enabled = enabled,
        role = Role.Button,
        onDoubleClick = {
          if (expandable) {
            onNodeExpandedToggled(node.id)
          } else {
            onNodeOpened(node.id)
          }
        },
        onClick = {
          if (expandable) {
            onNodeExpandedToggled(node.id)
          } else if (open) {
            onNodeSelected(node.id)
          }
        },
      )
      // SKILL-46 / AC1: right-click opens a one-item context menu rendered below; the menu's
      // `Delete…` item triggers the confirmation dialog via `onShowContextMenu`. The menu state is
      // hoisted per-row via `menuExpanded` below.
      .skillRemoveContextMenuModifier(node, enabled) { menuExpanded = true },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
      Modifier
        .width(3.dp)
        .fillMaxHeight()
        .background(if (selected) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.transparent),
    )
    Spacer(modifier = Modifier.width((22 + depth * 16).dp))
    if (expandable) {
      Text(text = if (expanded) "v" else ">", color = iconTint, fontSize = 12.sp)
    } else {
      Spacer(modifier = Modifier.width(7.dp))
    }
    MiniIcon(text = markerFor(node.kind), tint = iconTint)
    Text(
      text = node.label,
      color = SkillBillTheme.frameTokens.text.copy(alpha = textAlpha),
      fontSize = 12.5.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.padding(start = 8.dp).weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    val readOnlyLabel = node.readOnlyLabel ?: "RO".takeIf { !node.editable }
    OpenEditorTabIndicator(open = open)
    if (readOnlyLabel != null) {
      Text(
        text = readOnlyLabel,
        color = SkillBillTheme.frameTokens.subtle,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(end = 8.dp),
      )
    }
    StatusDot(level = validationLevelFor(node.status))
    Spacer(modifier = Modifier.width(8.dp))
    DropdownMenu(
      expanded = menuExpanded,
      onDismissRequest = { menuExpanded = false },
    ) {
      DropdownMenuItem(
        text = { Text("Delete…") },
        onClick = {
          menuExpanded = false
          onShowContextMenu(node)
        },
      )
    }
  }
  if (expandable && expanded) {
    node.children.forEach { child ->
      NavTreeNode(
        node = child,
        selected = selectedNodeId == child.id,
        open = treeSingleClickSwitchesToOpenTab(child.id, openEditorTabIds),
        selectedNodeId = selectedNodeId,
        openEditorTabIds = openEditorTabIds,
        expandedNodeIds = expandedNodeIds,
        enabled = enabled,
        depth = depth + 1,
        onNodeSelected = onNodeSelected,
        onNodeOpened = onNodeOpened,
        onNodeExpandedToggled = onNodeExpandedToggled,
        onShowContextMenu = onShowContextMenu,
      )
    }
  }
}

@Composable
private fun OpenEditorTabIndicator(open: Boolean) {
  Box(
    modifier = Modifier.size(width = 10.dp, height = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (open) {
      Box(
        modifier = Modifier
          .size(5.dp)
          .clip(CircleShape)
          .background(SkillBillTheme.frameTokens.primary),
      )
    }
  }
}

/**
 * F-X-901: Sidebar row that conveys repository-level status without an action. Mirrors the
 * StatusItem pattern in the bottom WorkspaceStatusBar — no .clickable, no Role.Button, no hover or
 * press affordance. The contentDescription announces the status, not an action, so screen readers
 * do not advertise a tappable affordance.
 */
@Composable
private fun RepositoryStatusItem(label: String, statusText: String, marker: String, enabled: Boolean = true) {
  val contentColor = if (enabled) {
    SkillBillTheme.frameTokens.text.copy(alpha = 0.86f)
  } else {
    SkillBillTheme.frameTokens.subtle
  }
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      // F-X-901-H: match the sibling RepositoryAction's outer-padding shape (6dp gutter + 8dp
      // inner) using a single combined modifier instead of two stacked padding calls.
      .padding(horizontal = 14.dp)
      // F-X-901-G: merge child Text/MiniIcon semantics into one node so screen readers announce
      // "<label>: <status>" once rather than three separate fragments.
      .semantics(mergeDescendants = true) {
        this.contentDescription = "$label: $statusText"
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = marker, tint = SkillBillTheme.frameTokens.subtle)
    Text(
      text = label,
      color = contentColor,
      fontSize = 12.5.sp,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = statusText,
      color = SkillBillTheme.frameTokens.status.warning,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
    )
  }
}

@Composable
private fun CenterWorkspace(
  editor: EditorPlaceholder,
  dirtyEditorPrompt: DirtyEditorPrompt?,
  editorInputEnabled: Boolean,
  onEditorDraftChanged: (String) -> Unit,
  onEditorSave: () -> Unit,
  onEditorRevert: () -> Unit,
  onDirtyPromptDiscard: () -> Unit,
  onDirtyPromptCancel: () -> Unit,
  openEditorTabs: List<OpenEditorTab>,
  selectedTreeItemId: String?,
  onEditorTabSelected: (String) -> Unit,
  onEditorTabClosed: (String) -> Unit,
  modifier: Modifier,
) {
  Column(modifier = modifier.background(SkillBillTheme.frameTokens.background)) {
    EditorTabs(
      editor = editor,
      tabs = openEditorTabs,
      activeTabId = selectedTreeItemId,
      onTabSelected = onEditorTabSelected,
      onTabClosed = onEditorTabClosed,
    )
    CodeEditor(
      editor = editor,
      dirtyEditorPrompt = dirtyEditorPrompt,
      editorInputEnabled = editorInputEnabled,
      onDraftChanged = onEditorDraftChanged,
      onSave = onEditorSave,
      onRevert = onEditorRevert,
      onDirtyPromptDiscard = onDirtyPromptDiscard,
      onDirtyPromptCancel = onDirtyPromptCancel,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun EditorTabs(
  editor: EditorPlaceholder,
  tabs: List<OpenEditorTab>,
  activeTabId: String?,
  onTabSelected: (String) -> Unit,
  onTabClosed: (String) -> Unit,
) {
  val visibleTabs = tabs.takeIf { it.isNotEmpty() } ?: listOf(
    OpenEditorTab(
      id = "empty",
      title = editor.authoredPath ?: editor.title,
      marker = "fl",
      dirty = editor.dirty,
      readOnly = !editor.editable,
      readOnlyLabel = editor.readOnlyLabel,
    ),
  )
  val scrollState = rememberScrollState()
  val coroutineScope = rememberCoroutineScope()
  val closeableTabCount = visibleTabs.count { tab -> tab.id != "empty" }
  Box(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(36.dp)
      .background(SkillBillTheme.frameTokens.panel)
      .pointerInput(scrollState) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll && scrollState.maxValue > 0) {
              val scrollDelta = event.changes.firstOrNull()?.scrollDelta ?: Offset.Zero
              val tabScrollDelta = when {
                scrollDelta.x != 0f -> scrollDelta.x
                scrollDelta.y != 0f -> scrollDelta.y
                else -> 0f
              }
              if (tabScrollDelta != 0f) {
                coroutineScope.launch { scrollState.scrollBy(tabScrollDelta) }
                event.changes.forEach { change -> change.consume() }
              }
            }
          }
        }
      },
  ) {
    Row(
      modifier =
      Modifier
        .fillMaxSize()
        .horizontalScroll(scrollState),
      verticalAlignment = Alignment.Bottom,
    ) {
      visibleTabs.forEach { tab ->
        val active = tab.id == activeTabId || visibleTabs.size == 1 && tab.id == "empty"
        EditorTab(
          tab = tab,
          active = active,
          closeEnabled = tab.id != "empty" && closeableTabCount > 1 && (!active || !tab.dirty),
          onSelected = { if (tab.id != "empty") onTabSelected(tab.id) },
          onClosed = { if (tab.id != "empty") onTabClosed(tab.id) },
        )
      }
    }
    if (scrollState.maxValue > 0) {
      EditorTabsScrollbar(
        scrollState = scrollState,
        scrollValue = scrollState.value,
        maxScrollValue = scrollState.maxValue,
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun EditorTabsScrollbar(
  scrollState: ScrollState,
  scrollValue: Int,
  maxScrollValue: Int,
  modifier: Modifier = Modifier,
) {
  HorizontalScrollIndicator(
    scrollState = scrollState,
    scrollValue = scrollValue,
    maxScrollValue = maxScrollValue,
    modifier = modifier,
  )
}

@Composable
private fun HorizontalScrollIndicator(
  scrollState: ScrollState,
  scrollValue: Int,
  maxScrollValue: Int,
  modifier: Modifier = Modifier,
) {
  val coroutineScope = rememberCoroutineScope()
  val scrollTrackColor = SkillBillTheme.frameTokens.line
  val scrollThumbColor = SkillBillTheme.frameTokens.primary.copy(alpha = 0.72f)
  Box(
    modifier = modifier
      .height(10.dp)
      .pointerInput(scrollState, maxScrollValue) {
        detectHorizontalDragGestures { change, dragAmount ->
          change.consume()
          if (maxScrollValue > 0 && size.width > 0) {
            val viewportWidth = size.width.toFloat()
            val contentWidth = viewportWidth + maxScrollValue
            coroutineScope.launch { scrollState.scrollBy(dragAmount * contentWidth / viewportWidth) }
          }
        }
      },
    contentAlignment = Alignment.BottomStart,
  ) {
    Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
      val maxScroll = maxScrollValue.toFloat().takeIf { it > 0f } ?: return@Canvas
      val viewportWidth = size.width
      val contentWidth = viewportWidth + maxScroll
      val thumbWidth = (viewportWidth / contentWidth * viewportWidth).coerceAtLeast(48.dp.toPx())
      val thumbLeft = scrollValue / maxScroll * (viewportWidth - thumbWidth)
      drawRect(
        color = scrollTrackColor,
        topLeft = Offset.Zero,
        size = Size(viewportWidth, size.height),
      )
      drawRect(
        color = scrollThumbColor,
        topLeft = Offset(thumbLeft, 0f),
        size = Size(thumbWidth, size.height),
      )
    }
  }
}

@Composable
private fun EditorTab(
  tab: OpenEditorTab,
  active: Boolean,
  closeEnabled: Boolean,
  onSelected: () -> Unit,
  onClosed: () -> Unit,
) {
  val background = if (active) SkillBillTheme.frameTokens.background else SkillBillTheme.frameTokens.panel
  val textColor = if (active) SkillBillTheme.frameTokens.text else SkillBillTheme.frameTokens.muted
  val tabWidth = when {
    tab.title.length > 28 -> 230.dp
    tab.title.length > 18 -> 190.dp
    else -> 134.dp
  }
  Column(
    modifier =
    Modifier
      .height(36.dp)
      .width(tabWidth)
      .background(background)
      .clickable(enabled = !active, role = Role.Tab, onClick = onSelected)
      .semantics {
        selected = active
      },
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(2.dp)
        .background(
          if (active) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.transparent,
        ),
    )
    Row(
      modifier =
      Modifier
        .weight(1f)
        .fillMaxWidth()
        .border(BorderStroke(0.dp, SkillBillTheme.frameTokens.transparent))
        .padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      MiniIcon(text = tab.marker, tint = textColor)
      Text(
        text = tab.title,
        color = textColor,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (tab.dirty) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SkillBillTheme.frameTokens.primary))
      }
      if (tab.readOnly) {
        Text(
          text = tab.readOnlyLabel ?: "RO",
          color = SkillBillTheme.frameTokens.subtle,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
      if (closeEnabled) {
        Text(
          text = "x",
          color = if (active) SkillBillTheme.frameTokens.muted else SkillBillTheme.frameTokens.subtle,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClosed)
            .semantics { contentDescription = "Close ${tab.title}" },
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun CodeEditor(
  editor: EditorPlaceholder,
  dirtyEditorPrompt: DirtyEditorPrompt?,
  editorInputEnabled: Boolean,
  onDraftChanged: (String) -> Unit,
  onSave: () -> Unit,
  onRevert: () -> Unit,
  onDirtyPromptDiscard: () -> Unit,
  onDirtyPromptCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var dismissedSaveErrorDialogKey by remember { mutableStateOf<String?>(null) }
  val codePaneColors = codePaneColors()
  Column(
    modifier =
    modifier
      .fillMaxWidth()
      .background(SkillBillTheme.frameTokens.background),
  ) {
    EditorCommandBar(editor = editor, onSave = onSave, onRevert = onRevert)
    if (dirtyEditorPrompt != null) {
      DirtyEditorPromptBanner(
        prompt = dirtyEditorPrompt,
        onDiscard = onDirtyPromptDiscard,
        onCancel = onDirtyPromptCancel,
      )
    }
    editor.saveErrorMessage?.let { message ->
      SaveErrorBanner(message)
      val dialogKey = "${editor.draftContent.hashCode()}:$message"
      if (dismissedSaveErrorDialogKey != dialogKey) {
        SaveErrorDialog(
          message = message,
          onDismiss = {
            dismissedSaveErrorDialogKey = dialogKey
          },
        )
      }
    }
    if (editor.editable) {
      val editorInputActive = editorInputEnabled && !editor.saveInProgress
      Box(
        modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .background(codePaneColors.background)
          .verticalScroll(rememberScrollState()),
      ) {
        BasicTextField(
          value = editor.draftContent ?: editor.content.orEmpty(),
          onValueChange = onDraftChanged,
          enabled = editorInputActive,
          textStyle = androidx.compose.ui.text.TextStyle(
            color = if (editorInputActive) codePaneColors.editorText else codePaneColors.editorDisabledText,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 20.sp,
          ),
          cursorBrush = SolidColor(codePaneColors.editorCursor),
          modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    } else {
      val rawText = (editor.content ?: editor.detail).ifBlank { "No source selected" }
      val lines = rawText.lines()
      ReadOnlyBanner(editor)
      Column(
        modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .background(codePaneColors.background)
          .verticalScroll(rememberScrollState()),
      ) {
        lines.forEachIndexed { index, line ->
          CodeLine(number = index + 1, line = line, flagged = false, colors = codePaneColors)
        }
      }
    }
  }
}

@Composable
private fun SaveErrorDialog(message: String, onDismiss: () -> Unit) {
  val dialogTone = SkillBillTheme.semanticTones.dialog
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(text = "Save blocked", color = dialogTone.content)
    },
    text = {
      Text(text = message, color = dialogTone.content)
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "OK")
      }
    },
    containerColor = dialogTone.container,
    titleContentColor = dialogTone.content,
    textContentColor = dialogTone.content,
  )
}

@Composable
private fun EditorCommandBar(editor: EditorPlaceholder, onSave: () -> Unit, onRevert: () -> Unit) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(38.dp)
      .background(SkillBillTheme.frameTokens.raised)
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = if (editor.dirty) {
        "Modified"
      } else if (editor.editable) {
        "Saved"
      } else {
        "Read-only"
      },
      color = if (editor.dirty) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.muted,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    EditorActionButton(
      label = if (editor.saveInProgress) "Saving..." else "Save",
      marker = "sv",
      enabled = editor.editable && editor.dirty && !editor.saveInProgress,
      primary = editor.dirty,
      acceleratorLabel = SkillBillAcceleratorLabels.SAVE,
      onClick = onSave,
    )
    EditorActionButton(
      label = "Revert",
      marker = "rv",
      enabled = editor.editable && editor.dirty && !editor.saveInProgress,
      onClick = onRevert,
    )
  }
}

@Composable
private fun EditorActionButton(
  label: String,
  marker: String,
  enabled: Boolean,
  primary: Boolean = false,
  acceleratorLabel: String? = null,
  onClick: () -> Unit,
) {
  val background = if (primary && enabled) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.panel
  val foreground =
    when {
      !enabled -> SkillBillTheme.frameTokens.subtle
      primary -> workspacePrimaryControlForeground()
      else -> SkillBillTheme.frameTokens.text
    }
  AcceleratorTooltip(label = label, acceleratorLabel = acceleratorLabel) {
    Row(
      modifier =
      Modifier
        .height(26.dp)
        .clip(RoundedCornerShape(6.dp))
        .border(
          1.dp,
          if (enabled) SkillBillTheme.frameTokens.line else SkillBillTheme.frameTokens.panel,
          RoundedCornerShape(6.dp),
        )
        .background(background, RoundedCornerShape(6.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      MiniIcon(text = marker, tint = foreground)
      Text(text = label, color = foreground, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun ReadOnlyBanner(editor: EditorPlaceholder) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(SkillBillTheme.frameTokens.raised)
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "ro", tint = SkillBillTheme.frameTokens.primary)
    Text(
      text = if (editor.kind == "generated artifact") {
        editor.readOnlyReason ?: "Generated artifact is ${editor.readOnlyLabel ?: "read-only"}"
      } else {
        editor.readOnlyReason ?: "Read-only browser"
      },
      color = SkillBillTheme.frameTokens.muted,
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SaveErrorBanner(message: String) {
  val errorTone = SkillBillTheme.semanticTones.errorBanner
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .heightIn(max = 140.dp)
      .background(errorTone.container)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "x", tint = errorTone.content)
    Text(
      text = message,
      color = errorTone.content,
      fontSize = 11.sp,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun DirtyEditorPromptBanner(prompt: DirtyEditorPrompt, onDiscard: () -> Unit, onCancel: () -> Unit) {
  val warningTone = SkillBillTheme.semanticTones.warningBanner
  val message = when (prompt.reason) {
    DirtyEditorPromptReason.SELECTION_CHANGE -> "Discard unsaved edits before changing selection?"
    DirtyEditorPromptReason.REFRESH -> "Discard unsaved edits before refreshing?"
    DirtyEditorPromptReason.REPO_SWITCH -> "Discard unsaved edits before switching repositories?"
    DirtyEditorPromptReason.CHOOSE_DIRECTORY -> "Discard unsaved edits before choosing another repository?"
    DirtyEditorPromptReason.RETURN_TO_INSTALLED_WORKSPACE ->
      "Discard unsaved edits before opening the installed workspace?"
  }
  Row(
    modifier = Modifier.fillMaxWidth().background(warningTone.container).padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "!", tint = warningTone.content)
    Text(
      text = message,
      color = warningTone.content,
      fontSize = 11.sp,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    EditorActionButton(label = "Discard", marker = "ds", enabled = true, primary = true, onClick = onDiscard)
    EditorActionButton(label = "Cancel", marker = "cn", enabled = true, onClick = onCancel)
  }
}

@Composable
private fun CodeLine(number: Int, line: String, flagged: Boolean, colors: CodePaneColors) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .background(if (flagged) colors.flaggedBackground else SkillBillTheme.frameTokens.transparent),
  ) {
    Text(
      text = number.toString(),
      color = colors.lineNumber,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      modifier =
      Modifier
        .width(50.dp)
        .border(BorderStroke(0.dp, SkillBillTheme.frameTokens.transparent))
        .padding(top = 4.dp, end = 10.dp),
      maxLines = 1,
    )
    Row(
      modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 3.dp, end = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SyntaxText(line = line, colors = colors)
      if (flagged) {
        Row(
          modifier = Modifier.padding(start = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          MiniIcon(text = "x", tint = SkillBillTheme.frameTokens.status.error)
          Text(text = "contract: missing field", color = SkillBillTheme.frameTokens.status.error, fontSize = 10.5.sp)
        }
      }
    }
  }
}

@Composable
private fun SyntaxText(line: String, colors: CodePaneColors) {
  val keyMatch = Regex("^(\\s*)([A-Za-z0-9_-]+):(.*)$").matchEntire(line)
  if (line.trimStart().startsWith("#")) {
    Text(
      text = line,
      color = colors.yaml.comment,
      fontSize = 12.5.sp,
      fontFamily = FontFamily.Monospace,
      lineHeight = 20.sp,
      maxLines = 1,
    )
  } else if (keyMatch != null) {
    Row {
      Text(keyMatch.groupValues[1], color = colors.yaml.scalar, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
      Text(keyMatch.groupValues[2], color = colors.yaml.key, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
      Text(":", color = colors.yaml.marker, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
      Text(
        keyMatch.groupValues[3],
        color = colors.yaml.scalar,
        fontSize = 12.5.sp,
        fontFamily = FontFamily.Monospace,
      )
    }
  } else {
    Text(
      text = line,
      color = colors.yaml.scalar,
      fontSize = 12.5.sp,
      fontFamily = FontFamily.Monospace,
      lineHeight = 20.sp,
      maxLines = 1,
    )
  }
}

@Composable
private fun InspectorPane(
  editor: EditorPlaceholder,
  repoStatus: RepoLoadStatus,
  onGeneratedArtifactResolvable: (String) -> Boolean,
  onGeneratedArtifactSelected: (String) -> Unit,
) {
  Column(
    modifier =
    Modifier
      .width(SkillBillMetrics.inspectorPaneWidth)
      .fillMaxHeight()
      .background(SkillBillTheme.frameTokens.background)
      .border(BorderStroke(0.dp, SkillBillTheme.frameTokens.transparent)),
  ) {
    InspectorHeader(editor = editor)
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      InspectorSection(title = "Metadata", marker = "mt") {
        KeyValueRow("name", editor.skillName ?: editor.title)
        KeyValueRow("kind", editor.kind ?: "none")
        KeyValueRow("authored path", editor.authoredPath ?: "-")
        KeyValueRow("status", editor.status ?: "-", tone = toneForStatus(editor.status))
        KeyValueRow("mode", editor.readOnlyLabel ?: if (editor.editable) "editable" else "read-only")
        KeyValueRow(
          "draft",
          if (editor.dirty) "dirty" else "clean",
          tone = if (editor.dirty) Tone.Warning else Tone.Success,
        )
        KeyValueRow(
          "editable",
          if (editor.editable) "yes" else "no",
          tone = if (editor.editable) Tone.Success else Tone.Warning,
        )
      }
      InspectorSection(
        title = "Repository validation",
        marker = "vl",
        badge = repoStatus.issueCount.takeIf { it > 0 }?.toString(),
      ) {
        KeyValueRow("state", repoStatus.state.name.lowercase())
        KeyValueRow("skills", repoStatus.skillCount.toString())
        KeyValueRow("platform packs", repoStatus.platformPackCount.toString())
        KeyValueRow("add-ons", repoStatus.addonCount.toString())
        KeyValueRow("native agents", repoStatus.nativeAgentCount.toString())
      }
      val artifactsForInspector: List<GeneratedArtifactDetail> = editor.generatedArtifacts
      InspectorSection(
        title = "Generated artifacts",
        marker = "gn",
        badge = artifactsForInspector.size.takeIf { it > 0 }?.toString(),
      ) {
        if (artifactsForInspector.isEmpty()) {
          KeyValueRow("visible", "none")
        } else {
          artifactsForInspector.forEach { artifact ->
            GeneratedArtifactRow(
              artifact = artifact,
              enabled = onGeneratedArtifactResolvable(artifact.path),
              onGeneratedArtifactSelected = onGeneratedArtifactSelected,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun InspectorHeader(editor: EditorPlaceholder) {
  Column(modifier = Modifier.fillMaxWidth().background(SkillBillTheme.frameTokens.panel).padding(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MiniIcon(text = "sk", tint = SkillBillTheme.frameTokens.primary)
      Text(
        text = editor.skillName ?: editor.title,
        color = SkillBillTheme.frameTokens.text,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Badge(text = if (editor.editable) "EDIT" else "RO", tone = if (editor.editable) Tone.Success else Tone.Warning)
    }
    Text(
      text = editor.kind ?: editor.detail,
      color = SkillBillTheme.frameTokens.muted,
      fontSize = 11.sp,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}

@Composable
private fun InspectorSection(
  title: String,
  marker: String,
  badge: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(32.dp)
        .background(SkillBillTheme.frameTokens.panel)
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MiniIcon(text = marker, tint = SkillBillTheme.frameTokens.primary)
      Text(
        text = title,
        color = SkillBillTheme.frameTokens.text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        modifier = Modifier.weight(1f),
      )
      if (badge != null) {
        Badge(text = badge, tone = Tone.Error)
      }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), content = content)
    HorizontalDivider(color = SkillBillTheme.frameTokens.line)
  }
}

@Composable
private fun KeyValueRow(key: String, value: String, tone: Tone = Tone.Neutral) {
  Row(
    modifier = Modifier.fillMaxWidth().height(28.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LabelText(key, modifier = Modifier.weight(1f))
    Text(
      text = value,
      color = SkillBillTheme.frameTokens.status.contentColorFor(tone),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun GeneratedArtifactRow(
  artifact: GeneratedArtifactDetail,
  enabled: Boolean,
  onGeneratedArtifactSelected: (String) -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val rowBackground =
    if (enabled && hovered) {
      SkillBillTheme.frameTokens.raised.copy(alpha = 0.65f)
    } else {
      SkillBillTheme.frameTokens.transparent
    }
  val labelAlpha = if (enabled) 1f else 0.55f
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(rowBackground)
      .iconButtonSemantics(description = generatedArtifactRowContentDescription(artifact))
      .semantics(mergeDescendants = true) {
        if (!enabled) disabled()
      }
      .onPreviewKeyEvent { event ->
        if (enabled && event.type == KeyEventType.KeyDown && event.isActivationKey()) {
          onGeneratedArtifactSelected(artifact.path)
          true
        } else {
          false
        }
      }
      .hoverable(interactionSource = interactionSource, enabled = enabled)
      .clickable(enabled = enabled, role = Role.Button) { onGeneratedArtifactSelected(artifact.path) }
      .focusable(enabled = enabled),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = artifact.path,
      color = SkillBillTheme.frameTokens.subtle.copy(alpha = labelAlpha),
      fontSize = 10.sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.sp,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = "read-only",
      color = SkillBillTheme.frameTokens.status.contentColorFor(Tone.Warning).copy(alpha = labelAlpha),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

internal fun generatedArtifactRowContentDescription(artifact: GeneratedArtifactDetail): String =
  "Open artifact: ${artifact.path}"

private fun EditorPlaceholder.isDocumentLike(): Boolean =
  content != null || !authoredPath.isNullOrBlank() || !skillName.isNullOrBlank()

internal fun treeSingleClickSwitchesToOpenTab(itemId: String, openEditorTabIds: Set<String>): Boolean =
  itemId in openEditorTabIds

internal fun treeRowStateDescription(open: Boolean): String =
  if (open) "Open in editor tab" else "Not open in editor tab"

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

@Composable
private fun WorkspaceStatusBar(state: SkillBillState) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .background(SkillBillTheme.frameTokens.panel)
      .padding(horizontal = 12.dp)
      .horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    StatusItem("rp", state.statusBar.repoPathLabel, Tone.Neutral)
    StatusItem("tr", "${state.statusBar.targetCount} targets", Tone.Neutral)
    Spacer(modifier = Modifier.weight(1f))
    StatusItem(
      fileModeMarker(state.statusBar.readOnlyModeLabel),
      state.statusBar.readOnlyModeLabel,
      fileModeTone(state.statusBar.readOnlyModeLabel),
    )
    StatusItem("lk", state.statusBar.policyLabel, Tone.Neutral)
  }
}

private fun fileModeMarker(label: String): String = if (label == SkillBillStatusBar.READ_ONLY_MODE_LABEL) {
  "ro"
} else {
  "ed"
}

private fun fileModeTone(label: String): Tone = when (label) {
  SkillBillStatusBar.EDITABLE_MODE_LABEL -> Tone.Success
  "dirty" -> Tone.Warning
  else -> Tone.Warning
}

@Composable
private fun StatusItem(marker: String, text: String, tone: Tone) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    val markerTint = if (tone == Tone.Neutral) {
      SkillBillTheme.frameTokens.primary
    } else {
      SkillBillTheme.frameTokens.status.contentColorFor(tone)
    }
    MiniIcon(text = marker, tint = markerTint)
    Text(text = text, color = SkillBillTheme.frameTokens.status.contentColorFor(tone), fontSize = 11.sp, maxLines = 1)
  }
}

@Composable
private fun LabelText(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    color = SkillBillTheme.frameTokens.subtle,
    fontSize = 10.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp,
    modifier = modifier,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun Badge(text: String, tone: Tone) {
  val toneColor = SkillBillTheme.frameTokens.status.contentColorFor(tone)
  Text(
    text = text,
    color = toneColor,
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    modifier =
    Modifier
      .border(1.dp, toneColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
      .background(toneColor.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 1.dp),
    maxLines = 1,
  )
}

@Composable
private fun StatusDot(level: ValidationLevel?) {
  val color = when (level) {
    ValidationLevel.Ok -> SkillBillTheme.frameTokens.status.success
    ValidationLevel.Warn -> SkillBillTheme.frameTokens.status.warning
    ValidationLevel.Error -> SkillBillTheme.frameTokens.status.error
    null -> SkillBillTheme.frameTokens.subtle
  }
  Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
private fun MiniIcon(text: String, tint: SkillBillColor) {
  Box(
    modifier =
    Modifier
      .size(16.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(tint.copy(alpha = 0.12f)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text.take(2),
      color = tint,
      fontSize = 8.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
    )
  }
}

// F-U05 / F-X-501: shared helper for icon-text actions (copy, stage, unstage, clear, refresh,
// repository chooser, etc.). Applies a minimum hit target (>= 24x32 dp incl. inner padding) and a
// parameterized contentDescription so screen readers announce the action's intent. Callers still
// add their own `.clickable(role = Role.Button)` and visual padding (typically 6dp x 4dp).
private fun Modifier.iconButtonSemantics(description: String): Modifier = this
  .heightIn(min = 24.dp)
  .widthIn(min = 32.dp)
  .semantics {
    this.contentDescription = description
    this.role = Role.Button
  }

private enum class ValidationLevel(val marker: String, val tone: Tone) {
  Ok("ok", Tone.Success),
  Warn("wr", Tone.Warning),
  Error("x", Tone.Error),
}

private fun markerFor(kind: TreeItemKind): String = when (kind) {
  TreeItemKind.GROUP -> "gr"
  TreeItemKind.SKILL -> "sk"
  TreeItemKind.PLATFORM_PACK -> "pk"
  TreeItemKind.ADD_ON -> "ad"
  TreeItemKind.NATIVE_AGENT -> "ag"
  TreeItemKind.GENERATED_ARTIFACT -> "gn"
  TreeItemKind.PLACEHOLDER -> "ph"
}

private fun validationLevelFor(status: String?): ValidationLevel? = when (status) {
  "complete", "authored", "governed-content" -> ValidationLevel.Ok
  "draft", "read-only" -> ValidationLevel.Warn
  null -> null
  else -> ValidationLevel.Warn
}

private fun toneForStatus(status: String?): Tone = when (validationLevelFor(status)) {
  ValidationLevel.Ok -> Tone.Success
  ValidationLevel.Warn -> Tone.Warning
  ValidationLevel.Error -> Tone.Error
  null -> Tone.Neutral
}

/**
 * SKILL-46: helper modifier that fires [onRequestMenu] when the user right-clicks (secondary-button
 * press) on a tree row whose kind is in the supported set (SKILL, PLATFORM_PACK, ADD_ON). The
 * caller renders an intermediate context menu (a Material3 `DropdownMenu` with a single `Delete…`
 * item) that — on click — invokes the actual deletion-confirmation flow. The two-step gesture
 * (right-click → Delete… → confirmation dialog) matches AC1's spec wording. Generic
 * GROUP/NATIVE_AGENT/GENERATED_ARTIFACT/PLACEHOLDER kinds, non-editable nodes, and built-in
 * names (`.bill-shared` / `kotlin` / `kmp`) are filtered out here so the route never sees them.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun Modifier.skillRemoveContextMenuModifier(
  node: SkillBillTreeItem,
  enabled: Boolean,
  onRequestMenu: () -> Unit,
): Modifier {
  val supported = node.kind == TreeItemKind.SKILL ||
    node.kind == TreeItemKind.PLATFORM_PACK ||
    node.kind == TreeItemKind.ADD_ON
  if (!supported || !enabled) return this
  // F-606: only offer the right-click menu for nodes whose resolved target is NOT a built-in.
  // The label/metadata fall-back mirrors the route resolver's identifier extraction so the
  // modifier and the route agree about which nodes can even be considered for deletion. The
  // axis-specific predicates (`isProtectedHorizontalName` / `isProtectedPlatformName`) are
  // shared with the route (and with the domain refusal policy) so all three layers always
  // agree.
  // SKILL-49: `node.editable` is an editor-content concern (whether the document is read-only),
  // not a delete-affordance concern — synthetic PLATFORM_PACK group nodes are intentionally
  // `editable = false` because they are folders with no document to edit, but they MUST still
  // be right-click-deletable. The protection above (axis-specific predicate + route's
  // `target.isBuiltIn()`) is the load-bearing gate.
  val identifier = when (node.kind) {
    TreeItemKind.SKILL -> node.metadata?.skillName ?: node.label
    TreeItemKind.PLATFORM_PACK -> {
      val rawId = node.id.substringAfterLast('|', missingDelimiterValue = "")
      if (rawId.startsWith("platform:")) rawId.removePrefix("platform:") else node.label
    }
    else -> null
  }
  // SKILL-49: axis-specific protection — SKILL nodes hide Delete for shipped pre-shells and
  // `bill-*` product skills; PLATFORM_PACK nodes only hide Delete for `.bill-shared`.
  val isProtected = identifier != null && when (node.kind) {
    TreeItemKind.SKILL ->
      skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget.isProtectedHorizontalName(identifier)
    TreeItemKind.PLATFORM_PACK ->
      skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget.isProtectedPlatformName(identifier)
    else -> false
  }
  if (isProtected) {
    return this
  }
  return this.pointerInput(node.id) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.type == PointerEventType.Press) {
          val change = event.changes.firstOrNull()
          if (change != null && event.button == PointerButton.Secondary) {
            change.consume()
            onRequestMenu()
            // F-604: also consume the matching Release so a sibling click handler does not
            // process the secondary-button release as a selection event.
            val release = awaitPointerEvent(PointerEventPass.Main)
            if (release.type == PointerEventType.Release) {
              release.changes.forEach { it.consume() }
            }
          }
        }
      }
    }
  }
}
