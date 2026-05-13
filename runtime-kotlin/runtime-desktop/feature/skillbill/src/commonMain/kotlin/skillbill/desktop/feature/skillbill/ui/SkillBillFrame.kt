@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.domain.model.ChangedFile
import skillbill.desktop.core.domain.model.ChangedFileGroup
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.DockTab
import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.GitAheadBehind
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RenderSummary
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.ValidationIssue
import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.desktop.core.domain.model.ValidationSeverity
import skillbill.desktop.core.domain.model.ValidationSummary

private val WorkspaceBackground = Color(0xFF050506)
private val WorkspacePanel = Color(0xFF121216)
private val WorkspaceRaised = Color(0xFF15151A)
private val WorkspaceSidebar = Color(0xFF0D0D10)
private val WorkspaceLine = Color(0xFF2A2A31)
private val WorkspaceMuted = Color(0xFFB7B1A0)
private val WorkspaceSteel = Color(0xFF6F7882)
private val WorkspaceText = Color(0xFFF6F3E7)
private val WorkspaceYellow = Color(0xFFF4C430)
private val WorkspaceGreen = Color(0xFF60D394)
private val WorkspaceRed = Color(0xFFFF5F57)
private val WorkspaceAmber = Color(0xFFFFBD2E)

@Composable
fun SkillBillFrame(
  state: SkillBillState,
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onRepoPathChanged: (String) -> Unit,
  onRepoSelected: (String) -> Unit,
  onChooseRepoDirectory: () -> Unit,
  onRefresh: () -> Unit,
  onValidate: () -> Unit,
  onRender: () -> Unit,
  onEditorDraftChanged: (String) -> Unit,
  onEditorSave: () -> Unit,
  onEditorRevert: () -> Unit,
  onDirtyPromptDiscard: () -> Unit,
  onDirtyPromptCancel: () -> Unit,
  onTreeItemSelected: (String) -> Unit,
  onTreeItemExpandedToggled: (String) -> Unit,
  onMoveTreeSelection: (Int) -> Unit,
  onValidationIssueSelected: (ValidationIssue) -> Unit,
  onCopyIssueSource: (String) -> Unit,
  onActiveDockTabChanged: (DockTab) -> Unit,
  onChangedFileSelected: (String) -> Unit,
  onStageChangedFile: (String) -> Unit,
  onUnstageChangedFile: (String) -> Unit,
  onRefreshGit: () -> Unit,
  onCommitMessageChanged: (String) -> Unit,
  onCommit: () -> Unit,
  onCommitAfterFailedValidation: () -> Unit,
  onPush: () -> Unit,
  onConfirmCanonicalPush: () -> Unit,
  onCopyChangedFilePath: (String) -> Unit,
  onCopyCommitHash: (String) -> Unit,
  onClearHistoryPathFilter: () -> Unit,
  // F-X-512: a transient key for "Copied" feedback. When non-null, any copy-affordance whose
  // value matches the key flashes its copied state until the route clears the key.
  recentlyCopiedKey: String? = null,
) {
  val publishingBusy = state.commitBusy || state.commitValidationRunning || state.pushBusy
  val validateEnabled =
    state.selectedRepoPath != null &&
      state.repoStatus.state == RepoLoadState.LOADED &&
      state.busyOperation == null &&
      !publishingBusy
  val renderEnabled =
    state.renderable &&
      state.repoStatus.state == RepoLoadState.LOADED &&
      state.busyOperation == null &&
      !publishingBusy
  Column(modifier = Modifier.fillMaxSize().background(WorkspaceBackground)) {
    WorkspaceToolbar(
      canNavigateBack = canNavigateBack,
      onNavigateBack = onNavigateBack,
      onRefresh = onRefresh,
      onValidate = onValidate,
      onRender = onRender,
      validateEnabled = validateEnabled,
      renderEnabled = renderEnabled,
      publishingBusy = publishingBusy,
      sourceControlLabel = state.sourceControl.branchLabel,
      readOnlyModeLabel = state.statusBar.readOnlyModeLabel,
      busyOperation = state.busyOperation,
    )
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
      NavigationPane(
        repoPath = state.repoPathText,
        repoStatus = state.repoStatus,
        treeItems = state.treeItems,
        selectedNodeId = state.selectedTreeItemId,
        expandedNodeIds = state.expandedNodeIds,
        busyOperation = state.busyOperation,
        publishingBusy = publishingBusy,
        policyLabel = state.statusBar.policyLabel,
        validationIssueCount = state.validation.issues.size,
        onRepoPathChanged = onRepoPathChanged,
        onRepoSelected = onRepoSelected,
        onChooseRepoDirectory = onChooseRepoDirectory,
        onNodeSelected = onTreeItemSelected,
        onNodeExpandedToggled = onTreeItemExpandedToggled,
        onMoveSelection = onMoveTreeSelection,
      )
      VerticalDivider(color = WorkspaceLine)
      CenterWorkspace(
        editor = state.editor,
        validation = state.validation,
        render = state.render,
        activeDockTab = state.activeDockTab,
        onActiveDockTabChanged = onActiveDockTabChanged,
        changes = state.changes,
        changesBusy = state.changesBusy,
        selectedChangedFile = state.selectedChangedFile,
        selectedDiff = state.selectedDiff,
        selectedDiffBusy = state.selectedDiffBusy,
        history = state.history,
        historyBusy = state.historyBusy,
        historyErrorMessage = state.historyErrorMessage,
        historyPathFilter = state.historyPathFilter,
        publishingBusy = publishingBusy,
        commitMessage = state.commitMessage,
        canCommit = state.canCommit,
        commitBusy = state.commitBusy,
        commitErrorMessage = state.commitErrorMessage,
        commitValidationFailed = state.commitValidationFailed,
        commitValidationRunning = state.commitValidationRunning,
        pushTarget = state.pushTarget,
        aheadBehind = state.aheadBehind,
        compareUrl = state.compareUrl,
        pushBusy = state.pushBusy,
        pushErrorMessage = state.pushErrorMessage,
        pushStatusErrorMessage = state.pushStatusErrorMessage,
        canonicalPushConfirmationRequired = state.canonicalPushConfirmationRequired,
        hasRepoOpen = state.selectedRepoPath != null && state.repoStatus.state == RepoLoadState.LOADED,
        dirtyEditorPrompt = state.dirtyEditorPrompt,
        editorInputEnabled = state.busyOperation == null &&
          !state.changesBusy &&
          !state.commitBusy &&
          !state.commitValidationRunning &&
          !state.pushBusy,
        onEditorDraftChanged = onEditorDraftChanged,
        onEditorSave = onEditorSave,
        onEditorRevert = onEditorRevert,
        onDirtyPromptDiscard = onDirtyPromptDiscard,
        onDirtyPromptCancel = onDirtyPromptCancel,
        onChangedFileSelected = onChangedFileSelected,
        onStageChangedFile = onStageChangedFile,
        onUnstageChangedFile = onUnstageChangedFile,
        onRefreshGit = onRefreshGit,
        onCommitMessageChanged = onCommitMessageChanged,
        onCommit = onCommit,
        onCommitAfterFailedValidation = onCommitAfterFailedValidation,
        onPush = onPush,
        onConfirmCanonicalPush = onConfirmCanonicalPush,
        onCopyChangedFilePath = onCopyChangedFilePath,
        onCopyCommitHash = onCopyCommitHash,
        onClearHistoryPathFilter = onClearHistoryPathFilter,
        recentlyCopiedKey = recentlyCopiedKey,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      )
      InspectorPane(
        editor = state.editor,
        repoStatus = state.repoStatus,
        validation = state.validation,
        render = state.render,
        onValidationIssueSelected = onValidationIssueSelected,
        onCopyIssueSource = onCopyIssueSource,
      )
    }
    WorkspaceStatusBar(state = state)
  }
}

@Composable
private fun WorkspaceToolbar(
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onRefresh: () -> Unit,
  onValidate: () -> Unit,
  onRender: () -> Unit,
  validateEnabled: Boolean,
  renderEnabled: Boolean,
  publishingBusy: Boolean,
  sourceControlLabel: String,
  readOnlyModeLabel: String,
  busyOperation: SkillBillBusyOperation?,
) {
  val busy = busyOperation != null || publishingBusy
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(40.dp)
      .background(WorkspaceBackground)
      .border(BorderStroke(0.dp, Color.Transparent))
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (canNavigateBack) {
      ToolbarButton(label = "Back", marker = "<", enabled = !busy, onClick = onNavigateBack)
      Spacer(modifier = Modifier.width(8.dp))
      ToolbarDivider()
    }
    ToolbarButton(label = sourceControlLabel, marker = "br")
    ToolbarDivider()
    ToolbarButton(label = "Refresh", marker = "rf", enabled = !busy, onClick = onRefresh)
    ToolbarButton(label = "Validate", marker = "ok", enabled = validateEnabled, onClick = onValidate)
    ToolbarButton(label = "Render check", marker = "rc", enabled = renderEnabled, onClick = onRender)
    ToolbarDivider()
    ToolbarButton(label = readOnlyModeLabel, marker = "ro", primary = true)
    if (busyOperation != null) {
      BusyIndicator(busyOperation)
    }
    Spacer(modifier = Modifier.weight(1f))
    SearchBox()
  }
}

@Composable
private fun ToolbarButton(
  label: String,
  marker: String,
  primary: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit = {},
) {
  val background = if (primary) WorkspaceYellow else WorkspaceRaised
  val foreground =
    when {
      !enabled -> WorkspaceSteel
      primary -> Color(0xFF0B0B0D)
      else -> WorkspaceText
    }
  val border = if (primary) WorkspaceYellow else WorkspaceLine
  Row(
    modifier =
    Modifier
      .height(28.dp)
      .padding(end = 6.dp)
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, border, RoundedCornerShape(6.dp))
      .background(background)
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

@Composable
private fun BusyIndicator(busyOperation: SkillBillBusyOperation) {
  val label = when (busyOperation) {
    SkillBillBusyOperation.OPEN_REPO -> "Opening..."
    SkillBillBusyOperation.REFRESH -> "Refreshing..."
    SkillBillBusyOperation.CHOOSE_DIRECTORY -> "Choosing..."
    SkillBillBusyOperation.VALIDATE -> "Validating..."
    SkillBillBusyOperation.RENDER -> "Rendering..."
    SkillBillBusyOperation.SAVE -> "Saving..."
  }
  Row(
    modifier = Modifier.padding(start = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    MiniIcon(text = "..", tint = WorkspaceYellow)
    Text(text = label, color = WorkspaceMuted, fontSize = 11.sp, maxLines = 1)
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
      .background(WorkspaceLine),
  )
}

@Composable
private fun SearchBox() {
  Row(
    modifier =
    Modifier
      .width(288.dp)
      .height(28.dp)
      .border(1.dp, WorkspaceLine, RoundedCornerShape(6.dp))
      .background(WorkspaceRaised, RoundedCornerShape(6.dp))
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "sr", tint = WorkspaceMuted)
    Text(
      text = "Find skill, intent, contract id...",
      color = WorkspaceSteel,
      fontSize = 12.sp,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(text = "⌘P", color = WorkspaceSteel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
  }
}

@Composable
private fun NavigationPane(
  repoPath: String,
  repoStatus: RepoLoadStatus,
  treeItems: List<SkillBillTreeItem>,
  selectedNodeId: String?,
  expandedNodeIds: Set<String>,
  busyOperation: SkillBillBusyOperation?,
  publishingBusy: Boolean,
  policyLabel: String,
  validationIssueCount: Int,
  onRepoPathChanged: (String) -> Unit,
  onRepoSelected: (String) -> Unit,
  onChooseRepoDirectory: () -> Unit,
  onNodeSelected: (String) -> Unit,
  onNodeExpandedToggled: (String) -> Unit,
  onMoveSelection: (Int) -> Unit,
) {
  val busy = busyOperation != null || publishingBusy
  Column(
    modifier =
    Modifier
      .width(SkillBillMetrics.treePaneWidth)
      .fillMaxHeight()
      .background(WorkspaceSidebar),
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
          expanded = group.id in expandedNodeIds,
          enabled = !busy,
          onNodeSelected = onNodeSelected,
          onNodeExpandedToggled = onNodeExpandedToggled,
        )
      }
      HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 8.dp), color = WorkspaceLine)
      RepositoryAction(
        label = "Validation",
        marker = "vl",
        badge = validationIssueCount.takeIf { it > 0 }?.toString(),
        enabled = !busy,
      )
      RepositoryAction(label = "Read-only browsing", marker = "ro", enabled = !busy)
    }
    Row(
      modifier =
      Modifier
        .fillMaxWidth()
        .height(35.dp)
        .border(BorderStroke(0.dp, Color.Transparent))
        .background(WorkspaceSidebar)
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      MiniIcon(text = "lk", tint = WorkspaceSteel)
      Text(text = "contract policy:", color = WorkspaceSteel, fontSize = 11.sp)
      Text(text = policyLabel, color = WorkspaceText, fontSize = 11.sp)
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
  Column(
    modifier =
    Modifier
      .fillMaxWidth()
      .border(BorderStroke(0.dp, Color.Transparent))
      .padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    LabelText("Repository")
    Row(
      modifier =
      Modifier
        .fillMaxWidth()
        .height(32.dp)
        .border(1.dp, WorkspaceLine, RoundedCornerShape(6.dp))
        .background(WorkspaceRaised, RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MiniIcon(text = "db", tint = WorkspaceYellow)
      BasicTextField(
        value = repoPath,
        onValueChange = onRepoPathChanged,
        enabled = !busy,
        textStyle = androidx.compose.ui.text.TextStyle(
          color = WorkspaceText,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
        ),
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      // F-U05 / F-X-501: enlarge hit target and announce intent for the text-as-button actions.
      Text(
        text = if (busy) "Busy" else "Open",
        color = if (busy) WorkspaceSteel else WorkspaceYellow,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
          .iconButtonSemantics(description = "Open repository at path")
          .clickable(enabled = !busy, role = Role.Button) { onRepoSelected(repoPath) }
          .padding(horizontal = 6.dp, vertical = 4.dp),
      )
      Text(
        text = "...",
        color = if (busy) WorkspaceSteel else WorkspaceYellow,
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
      color = if (repoStatus.state == RepoLoadState.INVALID) WorkspaceRed else WorkspaceSteel,
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
    color = if (repoStatus.state == RepoLoadState.INVALID) WorkspaceRed else WorkspaceSteel,
    fontSize = 12.sp,
    modifier = Modifier.padding(12.dp),
  )
}

@Composable
private fun NavGroup(
  group: SkillBillTreeItem,
  selectedNodeId: String?,
  expanded: Boolean,
  enabled: Boolean,
  onNodeSelected: (String) -> Unit,
  onNodeExpandedToggled: (String) -> Unit,
) {
  val selected = selectedNodeId == group.id
  val rowBackground = if (selected) WorkspaceYellow.copy(alpha = 0.15f) else Color.Transparent
  val iconTint = if (selected) WorkspaceYellow else WorkspaceSteel
  val textColor =
    when {
      selected -> WorkspaceText
      else -> WorkspaceSteel
    }
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
        .padding(end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(
        modifier =
        Modifier
          .width(3.dp)
          .fillMaxHeight()
          .background(if (selected) WorkspaceYellow else Color.Transparent),
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
    }
    if (expanded) {
      group.children.forEach { node ->
        NavNodeRow(
          node = node,
          selected = selectedNodeId == node.id,
          enabled = enabled,
          onNodeSelected = onNodeSelected,
        )
      }
    }
  }
}

@Composable
private fun NavNodeRow(
  node: SkillBillTreeItem,
  selected: Boolean,
  enabled: Boolean,
  onNodeSelected: (String) -> Unit,
) {
  val rowBackground = if (selected) WorkspaceYellow.copy(alpha = 0.15f) else Color.Transparent
  val iconTint = if (selected) WorkspaceYellow else WorkspaceSteel
  val textAlpha =
    when {
      !enabled -> 0.42f
      selected -> 1f
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
      .semantics { this.selected = selected }
      .clickable(enabled = enabled, role = Role.Button) { onNodeSelected(node.id) },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
      Modifier
        .width(3.dp)
        .fillMaxHeight()
        .background(if (selected) WorkspaceYellow else Color.Transparent),
    )
    Spacer(modifier = Modifier.width(22.dp))
    MiniIcon(text = markerFor(node.kind), tint = iconTint)
    Text(
      text = node.label,
      color = WorkspaceText.copy(alpha = textAlpha),
      fontSize = 12.5.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.padding(start = 8.dp).weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    val readOnlyLabel = node.readOnlyLabel ?: "RO".takeIf { !node.editable }
    if (readOnlyLabel != null) {
      Text(
        text = readOnlyLabel,
        color = WorkspaceSteel,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(end = 8.dp),
      )
    }
    StatusDot(level = validationLevelFor(node.status))
    Spacer(modifier = Modifier.width(8.dp))
  }
}

@Composable
private fun RepositoryAction(label: String, marker: String, badge: String? = null, enabled: Boolean = true) {
  val contentColor = if (enabled) WorkspaceText.copy(alpha = 0.86f) else WorkspaceSteel
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .padding(horizontal = 6.dp)
      .clip(RoundedCornerShape(3.dp))
      .clickable(enabled = enabled, role = Role.Button) {}
      .padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = marker, tint = WorkspaceSteel)
    Text(
      text = label,
      color = contentColor,
      fontSize = 12.5.sp,
      modifier = Modifier.weight(1f),
    )
    if (badge != null) {
      Badge(text = badge, tone = Tone.Error)
    }
  }
}

@Composable
private fun CenterWorkspace(
  editor: EditorPlaceholder,
  validation: ValidationSummary,
  render: RenderSummary,
  activeDockTab: DockTab,
  onActiveDockTabChanged: (DockTab) -> Unit,
  changes: ChangesSnapshot,
  changesBusy: Boolean,
  selectedChangedFile: ChangedFile?,
  selectedDiff: String,
  selectedDiffBusy: Boolean,
  history: List<CommitEntry>,
  historyBusy: Boolean,
  historyErrorMessage: String?,
  historyPathFilter: String?,
  publishingBusy: Boolean,
  commitMessage: String,
  canCommit: Boolean,
  commitBusy: Boolean,
  commitErrorMessage: String?,
  commitValidationFailed: Boolean,
  commitValidationRunning: Boolean,
  pushTarget: GitPushTarget?,
  aheadBehind: GitAheadBehind?,
  compareUrl: String?,
  pushBusy: Boolean,
  pushErrorMessage: String?,
  pushStatusErrorMessage: String?,
  canonicalPushConfirmationRequired: Boolean,
  hasRepoOpen: Boolean,
  dirtyEditorPrompt: DirtyEditorPrompt?,
  editorInputEnabled: Boolean,
  onEditorDraftChanged: (String) -> Unit,
  onEditorSave: () -> Unit,
  onEditorRevert: () -> Unit,
  onDirtyPromptDiscard: () -> Unit,
  onDirtyPromptCancel: () -> Unit,
  onChangedFileSelected: (String) -> Unit,
  onStageChangedFile: (String) -> Unit,
  onUnstageChangedFile: (String) -> Unit,
  onRefreshGit: () -> Unit,
  onCommitMessageChanged: (String) -> Unit,
  onCommit: () -> Unit,
  onCommitAfterFailedValidation: () -> Unit,
  onPush: () -> Unit,
  onConfirmCanonicalPush: () -> Unit,
  onCopyChangedFilePath: (String) -> Unit,
  onCopyCommitHash: (String) -> Unit,
  onClearHistoryPathFilter: () -> Unit,
  recentlyCopiedKey: String?,
  modifier: Modifier,
) {
  Column(modifier = modifier.background(WorkspaceBackground)) {
    EditorTabs(editor)
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
    BottomDock(
      editor = editor,
      validation = validation,
      render = render,
      activeTab = activeDockTab,
      onActiveTabSelected = onActiveDockTabChanged,
      changes = changes,
      changesBusy = changesBusy,
      selectedChangedFile = selectedChangedFile,
      selectedDiff = selectedDiff,
      selectedDiffBusy = selectedDiffBusy,
      history = history,
      historyBusy = historyBusy,
      historyErrorMessage = historyErrorMessage,
      historyPathFilter = historyPathFilter,
      publishingBusy = publishingBusy,
      commitMessage = commitMessage,
      canCommit = canCommit,
      commitBusy = commitBusy,
      commitErrorMessage = commitErrorMessage,
      commitValidationFailed = commitValidationFailed,
      commitValidationRunning = commitValidationRunning,
      pushTarget = pushTarget,
      aheadBehind = aheadBehind,
      compareUrl = compareUrl,
      pushBusy = pushBusy,
      pushErrorMessage = pushErrorMessage,
      pushStatusErrorMessage = pushStatusErrorMessage,
      canonicalPushConfirmationRequired = canonicalPushConfirmationRequired,
      hasRepoOpen = hasRepoOpen,
      onChangedFileSelected = onChangedFileSelected,
      onStageChangedFile = onStageChangedFile,
      onUnstageChangedFile = onUnstageChangedFile,
      onRefreshGit = onRefreshGit,
      onCommitMessageChanged = onCommitMessageChanged,
      onCommit = onCommit,
      onCommitAfterFailedValidation = onCommitAfterFailedValidation,
      onPush = onPush,
      onConfirmCanonicalPush = onConfirmCanonicalPush,
      onCopyChangedFilePath = onCopyChangedFilePath,
      onCopyCommitHash = onCopyCommitHash,
      onClearHistoryPathFilter = onClearHistoryPathFilter,
      recentlyCopiedKey = recentlyCopiedKey,
    )
  }
}

@Composable
private fun EditorTabs(editor: EditorPlaceholder) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(36.dp)
      .background(WorkspacePanel)
      .horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.Bottom,
  ) {
    EditorTab(
      name = editor.authoredPath ?: editor.title,
      active = true,
      dirty = editor.dirty,
      readOnly = !editor.editable,
      readOnlyLabel = editor.readOnlyLabel,
    )
  }
}

@Composable
private fun EditorTab(name: String, active: Boolean, dirty: Boolean, readOnly: Boolean, readOnlyLabel: String?) {
  val background = if (active) WorkspaceBackground else WorkspacePanel
  val textColor = if (active) WorkspaceText else WorkspaceMuted
  Column(
    modifier =
    Modifier
      .height(36.dp)
      .width(if (name.length > 18) 190.dp else 118.dp)
      .background(background),
  ) {
    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (active) WorkspaceYellow else Color.Transparent))
    Row(
      modifier =
      Modifier
        .weight(1f)
        .fillMaxWidth()
        .border(BorderStroke(0.dp, Color.Transparent))
        .padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      MiniIcon(text = "fl", tint = textColor)
      Text(
        text = name,
        color = textColor,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (dirty) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(WorkspaceYellow))
      }
      if (readOnly) {
        Text(text = readOnlyLabel ?: "RO", color = WorkspaceSteel, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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
  Column(
    modifier =
    modifier
      .fillMaxWidth()
      .background(WorkspaceBackground),
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
    }
    if (editor.editable) {
      Box(
        modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
      ) {
        BasicTextField(
          value = editor.draftContent ?: editor.content.orEmpty(),
          onValueChange = onDraftChanged,
          enabled = editorInputEnabled && !editor.saveInProgress,
          textStyle = androidx.compose.ui.text.TextStyle(
            color = WorkspaceText.copy(alpha = 0.92f),
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 20.sp,
          ),
          modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    } else {
      val lines =
        (editor.content ?: editor.detail)
          .ifBlank { "No source selected" }
          .lines()
      ReadOnlyBanner(editor)
      Column(
        modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
      ) {
        lines.forEachIndexed { index, line ->
          CodeLine(number = index + 1, line = line, flagged = false)
        }
      }
    }
  }
}

@Composable
private fun EditorCommandBar(editor: EditorPlaceholder, onSave: () -> Unit, onRevert: () -> Unit) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(38.dp)
      .background(WorkspaceRaised)
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = if (editor.dirty) "Modified" else if (editor.editable) "Saved" else "Read-only",
      color = if (editor.dirty) WorkspaceYellow else WorkspaceMuted,
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
  onClick: () -> Unit,
) {
  val background = if (primary && enabled) WorkspaceYellow else WorkspacePanel
  val foreground =
    when {
      !enabled -> WorkspaceSteel
      primary -> Color(0xFF0B0B0D)
      else -> WorkspaceText
    }
  Row(
    modifier =
    Modifier
      .height(26.dp)
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, if (enabled) WorkspaceLine else WorkspacePanel, RoundedCornerShape(6.dp))
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

@Composable
private fun ReadOnlyBanner(editor: EditorPlaceholder) {
  Row(
    modifier = Modifier.fillMaxWidth().background(WorkspaceRaised).padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "ro", tint = WorkspaceYellow)
    Text(
      text = if (editor.kind == "generated artifact") {
        editor.readOnlyReason ?: "Generated artifact is ${editor.readOnlyLabel ?: "read-only"}"
      } else {
        editor.readOnlyReason ?: "Read-only browser"
      },
      color = WorkspaceMuted,
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SaveErrorBanner(message: String) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .heightIn(max = 140.dp)
      .background(WorkspaceRed.copy(alpha = 0.16f))
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "x", tint = WorkspaceRed)
    Text(
      text = message,
      color = WorkspaceText,
      fontSize = 11.sp,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun DirtyEditorPromptBanner(
  prompt: DirtyEditorPrompt,
  onDiscard: () -> Unit,
  onCancel: () -> Unit,
) {
  val message = when (prompt.reason) {
    DirtyEditorPromptReason.SELECTION_CHANGE -> "Discard unsaved edits before changing selection?"
    DirtyEditorPromptReason.REFRESH -> "Discard unsaved edits before refreshing?"
    DirtyEditorPromptReason.REPO_SWITCH -> "Discard unsaved edits before switching repositories?"
    DirtyEditorPromptReason.CHOOSE_DIRECTORY -> "Discard unsaved edits before choosing another repository?"
  }
  Row(
    modifier = Modifier.fillMaxWidth().background(WorkspaceAmber.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "!", tint = WorkspaceAmber)
    Text(
      text = message,
      color = WorkspaceText,
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
private fun CodeLine(number: Int, line: String, flagged: Boolean) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .background(if (flagged) WorkspaceRed.copy(alpha = 0.10f) else Color.Transparent),
  ) {
    Text(
      text = number.toString(),
      color = WorkspaceSteel,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      modifier =
      Modifier
        .width(50.dp)
        .border(BorderStroke(0.dp, Color.Transparent))
        .padding(top = 4.dp, end = 10.dp),
      maxLines = 1,
    )
    Row(
      modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 3.dp, end = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SyntaxText(line = line)
      if (flagged) {
        Row(
          modifier = Modifier.padding(start = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          MiniIcon(text = "x", tint = WorkspaceRed)
          Text(text = "contract: missing field", color = WorkspaceRed, fontSize = 10.5.sp)
        }
      }
    }
  }
}

@Composable
private fun SyntaxText(line: String) {
  val keyMatch = Regex("^(\\s*)([A-Za-z0-9_-]+):(.*)$").matchEntire(line)
  if (line.trimStart().startsWith("#")) {
    Text(
      text = line,
      color = WorkspaceSteel,
      fontSize = 12.5.sp,
      fontFamily = FontFamily.Monospace,
      lineHeight = 20.sp,
      maxLines = 1,
    )
  } else if (keyMatch != null) {
    Row {
      Text(keyMatch.groupValues[1], color = WorkspaceText, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
      Text(keyMatch.groupValues[2], color = WorkspaceYellow, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
      Text(":", color = WorkspaceSteel, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
      Text(
        keyMatch.groupValues[3],
        color = WorkspaceText.copy(alpha = 0.9f),
        fontSize = 12.5.sp,
        fontFamily = FontFamily.Monospace,
      )
    }
  } else {
    Text(
      text = line,
      color = WorkspaceText.copy(alpha = 0.9f),
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
  validation: ValidationSummary,
  render: RenderSummary,
  onValidationIssueSelected: (ValidationIssue) -> Unit,
  onCopyIssueSource: (String) -> Unit,
) {
  Column(
    modifier =
    Modifier
      .width(SkillBillMetrics.inspectorPaneWidth)
      .fillMaxHeight()
      .background(WorkspaceBackground)
      .border(BorderStroke(0.dp, Color.Transparent)),
  ) {
    InspectorHeader(editor)
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      InspectorSection(title = "Metadata", marker = "mt") {
        KeyValueRow("name", editor.skillName ?: editor.title)
        KeyValueRow("kind", editor.kind ?: "none")
        KeyValueRow("authored path", editor.authoredPath ?: "-")
        KeyValueRow("status", editor.status ?: "-", tone = toneForStatus(editor.status))
        KeyValueRow("mode", editor.readOnlyLabel ?: if (editor.editable) "editable" else "read-only")
        KeyValueRow("draft", if (editor.dirty) "dirty" else "clean", tone = if (editor.dirty) Tone.Warning else Tone.Success)
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
      InspectorSection(
        title = "Validation issues",
        marker = "x",
        badge = validation.issues.size.takeIf { it > 0 }?.toString(),
      ) {
        ValidationIssuesInspector(
          validation = validation,
          onValidationIssueSelected = onValidationIssueSelected,
          onCopyIssueSource = onCopyIssueSource,
        )
      }
      val artifactsForInspector: List<GeneratedArtifactDetail> =
        if (render.state == RenderRunState.PASSED || render.state == RenderRunState.FAILED) {
          render.generatedArtifacts
        } else {
          editor.generatedArtifacts
        }
      InspectorSection(
        title = "Generated artifacts",
        marker = "gn",
        badge = artifactsForInspector.size.takeIf { it > 0 }?.toString(),
      ) {
        val renderHeaderLabel = renderHeaderLabelFor(render)
        if (renderHeaderLabel != null) {
          KeyValueRow("render", renderHeaderLabel, tone = renderHeaderToneFor(render))
        }
        if (artifactsForInspector.isEmpty()) {
          KeyValueRow("visible", "none")
        } else {
          artifactsForInspector.forEach { artifact ->
            KeyValueRow(artifact.path, "read-only", tone = Tone.Warning)
          }
        }
      }
    }
  }
}

@Composable
private fun ValidationIssuesInspector(
  validation: ValidationSummary,
  onValidationIssueSelected: (ValidationIssue) -> Unit,
  onCopyIssueSource: (String) -> Unit,
) {
  when (validation.state) {
    ValidationRunState.UNAVAILABLE -> {
      CheckRow(ok = true, label = "Validation has not run for this repo.")
      return
    }
    ValidationRunState.RUNNING -> {
      CheckRow(ok = true, label = "Validation in progress...")
      return
    }
    ValidationRunState.PASSED -> {
      if (validation.issues.isEmpty()) {
        CheckRow(ok = true, label = "Validation passed with no issues.")
        return
      }
    }
    ValidationRunState.FAILED -> {
      val exceptionName = validation.runtimeExceptionName
      if (exceptionName != null) {
        val exceptionMessage = validation.runtimeExceptionMessage
        CheckRow(
          ok = false,
          label = buildString {
            append(exceptionName)
            if (!exceptionMessage.isNullOrBlank()) {
              append(": ")
              append(exceptionMessage)
            }
          },
        )
      } else if (validation.issues.isEmpty()) {
        // F-105: avoid a silent empty inspector when validation failed with no structured signal.
        CheckRow(ok = false, label = "Validation failed - no details available.")
      }
    }
  }
  validation.issues.forEach { issue ->
    ValidationIssueRow(
      issue = issue,
      onValidationIssueSelected = onValidationIssueSelected,
      onCopyIssueSource = onCopyIssueSource,
    )
  }
}

@Composable
private fun ValidationIssueRow(
  issue: ValidationIssue,
  onValidationIssueSelected: (ValidationIssue) -> Unit,
  onCopyIssueSource: (String) -> Unit,
) {
  val sourcePath = issue.sourcePath
  Row(
    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onValidationIssueSelected(issue) }
      .padding(vertical = 3.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(
      text = severityMarker(issue.severity),
      tint = severityTone(issue.severity).color(),
    )
    Column(modifier = Modifier.weight(1f)) {
      // F-104: surface severity text label + code beside the colored marker, so screen readers and
      // colorblind users can still distinguish issue severities.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = issue.severity.name,
          color = severityTone(issue.severity).color(),
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.SemiBold,
        )
        val code = issue.code
        if (!code.isNullOrBlank()) {
          Text(
            text = code,
            color = WorkspaceSteel,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
          )
        }
      }
      Text(text = issue.message, color = WorkspaceText.copy(alpha = 0.9f), fontSize = 12.sp)
      if (!sourcePath.isNullOrBlank()) {
        Text(
          text = sourcePath,
          color = WorkspaceSteel,
          fontSize = 10.5.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (!issue.exceptionName.isNullOrBlank()) {
        Text(
          text = "exception: ${issue.exceptionName}",
          color = WorkspaceRed,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (!sourcePath.isNullOrBlank()) {
      Text(
        text = "copy",
        color = WorkspaceYellow,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.clickable(role = Role.Button) {
          // F-106: clipboard side effect is owned by the route; this is now a pure callback.
          onCopyIssueSource(sourcePath)
        },
      )
    }
  }
}

@Composable
private fun InspectorHeader(editor: EditorPlaceholder) {
  Column(modifier = Modifier.fillMaxWidth().background(WorkspacePanel).padding(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MiniIcon(text = "sk", tint = WorkspaceYellow)
      Text(
        text = editor.skillName ?: editor.title,
        color = WorkspaceText,
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
      color = WorkspaceMuted,
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
      modifier = Modifier.fillMaxWidth().height(32.dp).background(WorkspacePanel).padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MiniIcon(text = marker, tint = WorkspaceYellow)
      Text(
        text = title,
        color = WorkspaceText,
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
    HorizontalDivider(color = WorkspaceLine)
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
      color = tone.color(),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun CheckRow(ok: Boolean, label: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = if (ok) "ok" else "x", tint = if (ok) WorkspaceGreen else WorkspaceRed)
    Text(text = label, color = WorkspaceText.copy(alpha = 0.9f), fontSize = 12.sp)
  }
}

@Composable
private fun DependencyRow(name: String, range: String, resolved: String) {
  Row(
    modifier = Modifier.fillMaxWidth().height(26.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = name,
      color = WorkspaceText,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = range,
      color = WorkspaceSteel,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(54.dp),
    )
    Text(text = resolved, color = WorkspaceGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
  }
}

@Composable
private fun BottomDock(
  editor: EditorPlaceholder,
  validation: ValidationSummary,
  render: RenderSummary,
  activeTab: DockTab,
  onActiveTabSelected: (DockTab) -> Unit,
  changes: ChangesSnapshot,
  changesBusy: Boolean,
  selectedChangedFile: ChangedFile?,
  selectedDiff: String,
  selectedDiffBusy: Boolean,
  history: List<CommitEntry>,
  historyBusy: Boolean,
  historyErrorMessage: String?,
  historyPathFilter: String?,
  publishingBusy: Boolean,
  commitMessage: String,
  canCommit: Boolean,
  commitBusy: Boolean,
  commitErrorMessage: String?,
  commitValidationFailed: Boolean,
  commitValidationRunning: Boolean,
  pushTarget: GitPushTarget?,
  aheadBehind: GitAheadBehind?,
  compareUrl: String?,
  pushBusy: Boolean,
  pushErrorMessage: String?,
  pushStatusErrorMessage: String?,
  canonicalPushConfirmationRequired: Boolean,
  hasRepoOpen: Boolean,
  onChangedFileSelected: (String) -> Unit,
  onStageChangedFile: (String) -> Unit,
  onUnstageChangedFile: (String) -> Unit,
  onRefreshGit: () -> Unit,
  onCommitMessageChanged: (String) -> Unit,
  onCommit: () -> Unit,
  onCommitAfterFailedValidation: () -> Unit,
  onPush: () -> Unit,
  onConfirmCanonicalPush: () -> Unit,
  onCopyChangedFilePath: (String) -> Unit,
  onCopyCommitHash: (String) -> Unit,
  onClearHistoryPathFilter: () -> Unit,
  recentlyCopiedKey: String?,
) {
  Column(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(SkillBillMetrics.bottomDockHeight)
      .background(WorkspacePanel),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().height(33.dp).background(WorkspacePanel),
      verticalAlignment = Alignment.Bottom,
    ) {
      DockTab.entries.forEach { tab ->
        DockTabButton(
          tab = tab,
          badge = badgeForDockTab(tab, validation, changes),
          active = activeTab == tab,
          enabled = !publishingBusy,
          onSelected = { onActiveTabSelected(tab) },
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      Row(
        modifier = Modifier.padding(end = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        MiniIcon(text = "run", tint = WorkspaceMuted)
        Text(text = editor.status ?: "no selection", color = WorkspaceMuted, fontSize = 11.sp)
      }
    }
    HorizontalDivider(color = WorkspaceLine)
    Box(modifier = Modifier.weight(1f).fillMaxWidth().background(WorkspaceBackground)) {
      when (activeTab) {
        DockTab.Validation -> ValidationTable(validation)
        DockTab.Changes -> ChangesPanel(
          changes = changes,
          changesBusy = changesBusy,
          publishingBusy = publishingBusy,
          selectedChangedFile = selectedChangedFile,
          selectedDiff = selectedDiff,
          selectedDiffBusy = selectedDiffBusy,
          commitMessage = commitMessage,
          canCommit = canCommit,
          commitBusy = commitBusy,
          commitErrorMessage = commitErrorMessage,
          commitValidationFailed = commitValidationFailed,
          commitValidationRunning = commitValidationRunning,
          pushTarget = pushTarget,
          aheadBehind = aheadBehind,
          compareUrl = compareUrl,
          pushBusy = pushBusy,
          pushErrorMessage = pushErrorMessage,
          pushStatusErrorMessage = pushStatusErrorMessage,
          canonicalPushConfirmationRequired = canonicalPushConfirmationRequired,
          hasRepoOpen = hasRepoOpen,
          onChangedFileSelected = onChangedFileSelected,
          onStageChangedFile = onStageChangedFile,
          onUnstageChangedFile = onUnstageChangedFile,
          onRefreshGit = onRefreshGit,
          onCommitMessageChanged = onCommitMessageChanged,
          onCommit = onCommit,
          onCommitAfterFailedValidation = onCommitAfterFailedValidation,
          onPush = onPush,
          onConfirmCanonicalPush = onConfirmCanonicalPush,
          onCopyChangedFilePath = onCopyChangedFilePath,
          recentlyCopiedKey = recentlyCopiedKey,
        )
        DockTab.History -> HistoryPanel(
          history = history,
          historyBusy = historyBusy,
          historyErrorMessage = historyErrorMessage,
          historyPathFilter = historyPathFilter,
          hasRepoOpen = hasRepoOpen,
          onCopyCommitHash = onCopyCommitHash,
          onClearHistoryPathFilter = onClearHistoryPathFilter,
          recentlyCopiedKey = recentlyCopiedKey,
        )
        DockTab.Console -> InstallConsole(editor = editor, render = render)
      }
    }
  }
}

private fun badgeForDockTab(tab: DockTab, validation: ValidationSummary, changes: ChangesSnapshot): String? =
  when (tab) {
    DockTab.Validation -> validation.issues.size.takeIf { it > 0 }?.toString()
    DockTab.Changes -> changes.files.size.takeIf { it > 0 }?.toString()
    else -> dockTabMetadata(tab).badge
  }

@Composable
private fun DockTabButton(tab: DockTab, badge: String?, active: Boolean, enabled: Boolean, onSelected: () -> Unit) {
  val meta = dockTabMetadata(tab)
  val labelColor = when {
    active -> WorkspaceText
    enabled -> WorkspaceMuted
    else -> WorkspaceSteel
  }
  Column(
    modifier =
    Modifier
      .height(33.dp)
      .width(meta.width)
      .background(if (active) WorkspaceBackground else WorkspacePanel)
      .clickable(enabled = enabled, role = Role.Button, onClick = onSelected)
      .semantics {
        if (!enabled) {
          disabled()
        }
      },
  ) {
    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (active) WorkspaceYellow else Color.Transparent))
    Row(
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Text(text = meta.label, color = labelColor, fontSize = 12.sp)
      badge?.let { Badge(text = it, tone = meta.tone) }
    }
  }
}

private data class DockTabMetadata(val label: String, val badge: String?, val tone: Tone, val width: Dp)

private fun dockTabMetadata(tab: DockTab): DockTabMetadata = when (tab) {
  DockTab.Validation -> DockTabMetadata("Validation", null, Tone.Error, 118.dp)
  DockTab.Changes -> DockTabMetadata("Changes", null, Tone.Warning, 106.dp)
  DockTab.History -> DockTabMetadata("History", null, Tone.Neutral, 102.dp)
  DockTab.Console -> DockTabMetadata("Install console", null, Tone.Neutral, 132.dp)
}

@Composable
private fun ValidationTable(validation: ValidationSummary) {
  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    TableHeader("Lvl", "Code", "Message", "Source")
    val exceptionName = validation.runtimeExceptionName
    if (exceptionName != null) {
      val exceptionMessage = validation.runtimeExceptionMessage
      TableRow(
        first = "x",
        second = "runtime",
        third = buildString {
          append(exceptionName)
          if (!exceptionMessage.isNullOrBlank()) {
            append(": ")
            append(exceptionMessage)
          }
        },
        fourth = "-",
        tone = Tone.Error,
      )
    } else if (validation.state == ValidationRunState.FAILED && validation.issues.isEmpty()) {
      // F-105: render an explicit fallback row when failure carries no structured signal, so the
      // dock and the inspector stay symmetric instead of showing only the header.
      TableRow(
        first = "x",
        second = "runtime",
        third = "Validation failed - no details available.",
        fourth = "-",
        tone = Tone.Error,
      )
    }
    validation.issues.forEach { issue ->
      val message = buildString {
        append(issue.message)
        // F-104: surface the per-issue exceptionName inline so the dock mirrors the inspector content.
        if (!issue.exceptionName.isNullOrBlank()) {
          append(" [exception: ")
          append(issue.exceptionName)
          append(']')
        }
      }
      TableRow(
        first = severityMarker(issue.severity),
        second = issue.code ?: "-",
        third = message,
        fourth = issue.sourcePath ?: "-",
        tone = severityTone(issue.severity),
      )
    }
  }
}

private fun severityMarker(severity: ValidationSeverity): String = when (severity) {
  ValidationSeverity.ERROR -> "x"
  ValidationSeverity.WARNING -> "wr"
  ValidationSeverity.INFO -> "ok"
}

private fun severityTone(severity: ValidationSeverity): Tone = when (severity) {
  ValidationSeverity.ERROR -> Tone.Error
  ValidationSeverity.WARNING -> Tone.Warning
  ValidationSeverity.INFO -> Tone.Success
}

@Composable
private fun ChangesPanel(
  changes: ChangesSnapshot,
  changesBusy: Boolean,
  publishingBusy: Boolean,
  selectedChangedFile: ChangedFile?,
  selectedDiff: String,
  selectedDiffBusy: Boolean,
  commitMessage: String,
  canCommit: Boolean,
  commitBusy: Boolean,
  commitErrorMessage: String?,
  commitValidationFailed: Boolean,
  commitValidationRunning: Boolean,
  pushTarget: GitPushTarget?,
  aheadBehind: GitAheadBehind?,
  compareUrl: String?,
  pushBusy: Boolean,
  pushErrorMessage: String?,
  pushStatusErrorMessage: String?,
  canonicalPushConfirmationRequired: Boolean,
  hasRepoOpen: Boolean,
  onChangedFileSelected: (String) -> Unit,
  onStageChangedFile: (String) -> Unit,
  onUnstageChangedFile: (String) -> Unit,
  onRefreshGit: () -> Unit,
  onCommitMessageChanged: (String) -> Unit,
  onCommit: () -> Unit,
  onCommitAfterFailedValidation: () -> Unit,
  onPush: () -> Unit,
  onConfirmCanonicalPush: () -> Unit,
  onCopyChangedFilePath: (String) -> Unit,
  recentlyCopiedKey: String?,
) {
  Row(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier.weight(1f).fillMaxHeight().padding(6.dp).verticalScroll(rememberScrollState()),
    ) {
      ChangesHeader(
        changesBusy = changesBusy,
        refreshEnabled = !changesBusy && !publishingBusy,
        errorMessage = changes.errorMessage,
        hasStaleData = changes.files.isNotEmpty(),
        onRefreshGit = onRefreshGit,
      )
      if (!hasRepoOpen) {
        Text(
          text = "Open a Git repository to see local changes.",
          color = WorkspaceSteel,
          fontSize = 11.sp,
          modifier = Modifier.padding(8.dp),
        )
        return@Column
      }
      CommitControls(
        publishingBusy = publishingBusy,
        commitMessage = commitMessage,
        canCommit = canCommit,
        commitBusy = commitBusy,
        commitErrorMessage = commitErrorMessage,
        commitValidationFailed = commitValidationFailed,
        commitValidationRunning = commitValidationRunning,
        onCommitMessageChanged = onCommitMessageChanged,
        onCommit = onCommit,
        onCommitAfterFailedValidation = onCommitAfterFailedValidation,
      )
      PushControls(
        publishingBusy = publishingBusy,
        pushTarget = pushTarget,
        aheadBehind = aheadBehind,
        compareUrl = compareUrl,
        pushBusy = pushBusy,
        pushErrorMessage = pushErrorMessage,
        pushStatusErrorMessage = pushStatusErrorMessage,
        canonicalPushConfirmationRequired = canonicalPushConfirmationRequired,
        onPush = onPush,
        onConfirmCanonicalPush = onConfirmCanonicalPush,
      )
      if (changes.files.isEmpty() && !changesBusy && changes.errorMessage == null) {
        Text(
          text = "No local changes.",
          color = WorkspaceSteel,
          fontSize = 11.sp,
          modifier = Modifier.padding(8.dp),
        )
      }
      // AC9: grouped sections in deterministic order. Generated stays last so authored changes
      // surface first.
      ChangedFileGroupSection(
        title = "Staged",
        group = ChangedFileGroup.STAGED,
        files = changes.files,
        selectedPath = selectedChangedFile?.path,
        stageActionsEnabled = !changesBusy && !publishingBusy,
        onChangedFileSelected = onChangedFileSelected,
        onStageChangedFile = onStageChangedFile,
        onUnstageChangedFile = onUnstageChangedFile,
        onCopyChangedFilePath = onCopyChangedFilePath,
        recentlyCopiedKey = recentlyCopiedKey,
      )
      ChangedFileGroupSection(
        title = "Unstaged",
        group = ChangedFileGroup.UNSTAGED,
        files = changes.files,
        selectedPath = selectedChangedFile?.path,
        stageActionsEnabled = !changesBusy && !publishingBusy,
        onChangedFileSelected = onChangedFileSelected,
        onStageChangedFile = onStageChangedFile,
        onUnstageChangedFile = onUnstageChangedFile,
        onCopyChangedFilePath = onCopyChangedFilePath,
        recentlyCopiedKey = recentlyCopiedKey,
      )
      ChangedFileGroupSection(
        title = "Untracked",
        group = ChangedFileGroup.UNTRACKED,
        files = changes.files,
        selectedPath = selectedChangedFile?.path,
        stageActionsEnabled = !changesBusy && !publishingBusy,
        onChangedFileSelected = onChangedFileSelected,
        onStageChangedFile = onStageChangedFile,
        onUnstageChangedFile = onUnstageChangedFile,
        onCopyChangedFilePath = onCopyChangedFilePath,
        recentlyCopiedKey = recentlyCopiedKey,
      )
      ChangedFileGroupSection(
        title = "Generated",
        group = ChangedFileGroup.GENERATED,
        files = changes.files,
        selectedPath = selectedChangedFile?.path,
        stageActionsEnabled = !changesBusy && !publishingBusy,
        onChangedFileSelected = onChangedFileSelected,
        onStageChangedFile = onStageChangedFile,
        onUnstageChangedFile = onUnstageChangedFile,
        onCopyChangedFilePath = onCopyChangedFilePath,
        recentlyCopiedKey = recentlyCopiedKey,
      )
    }
    VerticalDivider(color = WorkspaceLine)
    // F-U03: the diff column must not collapse below a readable width when the dock is narrow.
    ChangesDiffPane(
      selectedChangedFile = selectedChangedFile,
      selectedDiff = selectedDiff,
      selectedDiffBusy = selectedDiffBusy,
      modifier = Modifier.weight(1f).fillMaxHeight().widthIn(min = 220.dp),
    )
  }
}

@Composable
private fun PushControls(
  publishingBusy: Boolean,
  pushTarget: GitPushTarget?,
  aheadBehind: GitAheadBehind?,
  compareUrl: String?,
  pushBusy: Boolean,
  pushErrorMessage: String?,
  pushStatusErrorMessage: String?,
  canonicalPushConfirmationRequired: Boolean,
  onPush: () -> Unit,
  onConfirmCanonicalPush: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(text = "Push target", color = WorkspaceSteel, fontSize = 10.sp)
      Text(
        text = pushTarget?.displayName ?: "No target",
        color = if (pushTarget == null) WorkspaceSteel else WorkspaceText,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val pushEnabled = pushTarget != null && !publishingBusy
      Text(
        text = if (pushBusy) "pushing" else "push",
        color = if (pushEnabled) WorkspaceYellow else WorkspaceSteel,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
          .iconButtonSemantics(description = "Push current branch")
          .clickable(enabled = pushEnabled, role = Role.Button, onClick = onPush)
          .padding(horizontal = 8.dp, vertical = 5.dp),
      )
    }
    if (aheadBehind != null) {
      Text(
        text = "Ahead ${aheadBehind.ahead} / behind ${aheadBehind.behind}",
        color = WorkspaceSteel,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
      )
    }
    if (canonicalPushConfirmationRequired && pushTarget != null) {
      val confirmEnabled = !publishingBusy
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = pushTarget.canonicalWarning ?: "This may push to a canonical remote.",
          color = Tone.Warning.color(),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "confirm push",
          color = if (confirmEnabled) WorkspaceYellow else WorkspaceSteel,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier
            .iconButtonSemantics(description = "Confirm canonical remote push")
            .clickable(enabled = confirmEnabled, role = Role.Button, onClick = onConfirmCanonicalPush)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        )
      }
    }
    if (compareUrl != null) {
      SelectionContainer {
        Text(
          text = compareUrl,
          color = WorkspaceYellow,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          maxLines = 1,
        )
      }
    }
    val error = pushErrorMessage ?: pushStatusErrorMessage
    if (error != null) {
      Text(
        text = error,
        color = WorkspaceRed,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun CommitControls(
  publishingBusy: Boolean,
  commitMessage: String,
  canCommit: Boolean,
  commitBusy: Boolean,
  commitErrorMessage: String?,
  commitValidationFailed: Boolean,
  commitValidationRunning: Boolean,
  onCommitMessageChanged: (String) -> Unit,
  onCommit: () -> Unit,
  onCommitAfterFailedValidation: () -> Unit,
) {
  val commitInputEnabled = !publishingBusy
  val commitInputDescription =
    if (commitInputEnabled) "Commit message" else "Commit message disabled while publishing is running"
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      BasicTextField(
        value = commitMessage,
        onValueChange = { message ->
          if (commitInputEnabled) {
            onCommitMessageChanged(message)
          }
        },
        enabled = commitInputEnabled,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
          color = WorkspaceText,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier
          .weight(1f)
          .height(30.dp)
          .background(if (commitInputEnabled) WorkspaceRaised else WorkspacePanel, RoundedCornerShape(4.dp))
          .border(1.dp, if (commitInputEnabled) WorkspaceLine else WorkspaceSteel, RoundedCornerShape(4.dp))
          .semantics {
            contentDescription = commitInputDescription
            if (!commitInputEnabled) {
              disabled()
            }
          }
          .padding(horizontal = 8.dp, vertical = 7.dp),
        decorationBox = { innerTextField ->
          if (commitMessage.isBlank()) {
            Text(
              text = "Commit message",
              color = WorkspaceSteel,
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
            )
          }
          innerTextField()
        },
      )
      val commitEnabled = canCommit && !publishingBusy
      Text(
        text = if (commitBusy) "committing" else "commit",
        color = if (commitEnabled) WorkspaceYellow else WorkspaceSteel,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
          .height(30.dp)
          .iconButtonSemantics(description = "Commit staged changes")
          .clickable(enabled = commitEnabled, role = Role.Button, onClick = onCommit)
          .padding(horizontal = 8.dp, vertical = 7.dp),
      )
    }
    if (commitValidationRunning) {
      Text(text = "Running validation before commit...", color = WorkspaceSteel, fontSize = 11.sp)
    }
    if (commitValidationFailed) {
      val overrideEnabled = !publishingBusy
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "Validation failed.",
          color = Tone.Warning.color(),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = "commit anyway",
          color = if (overrideEnabled) WorkspaceYellow else WorkspaceSteel,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier
            .iconButtonSemantics(description = "Commit despite failed validation")
            .clickable(enabled = overrideEnabled, role = Role.Button, onClick = onCommitAfterFailedValidation)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        )
      }
    }
    if (commitErrorMessage != null) {
      Text(
        text = commitErrorMessage,
        color = WorkspaceRed,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun ChangesHeader(
  changesBusy: Boolean,
  refreshEnabled: Boolean,
  errorMessage: String?,
  hasStaleData: Boolean,
  onRefreshGit: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = if (changesBusy) "Refreshing..." else "Changes",
      color = WorkspaceMuted,
      fontSize = 11.sp,
      modifier = Modifier.weight(1f),
    )
    // F-U05 / F-X-501: icon-text actions get a larger hit target and a parameterized
    // contentDescription so screen readers announce intent (not just "button"). The internal
    // padding gives the touch target room without changing the visible glyph size.
    Text(
      text = "refresh",
      color = if (refreshEnabled) WorkspaceYellow else WorkspaceSteel,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier
        .iconButtonSemantics(description = "Refresh repository status")
        .clickable(enabled = refreshEnabled, role = Role.Button, onClick = onRefreshGit)
        .padding(horizontal = 6.dp, vertical = 4.dp),
    )
  }
  if (errorMessage != null && hasStaleData) {
    // F-X-505: when an error is present AND prior data is non-empty, surface a single
    // visually-distinct banner so users see the rows below are stale. Keep the existing error
    // text path for the no-data case (rendered below).
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
      Text(
        text = "Last refresh failed - showing previous snapshot.",
        color = Tone.Warning.color(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
  if (errorMessage != null) {
    // F-601 mirror: long unbreakable error tokens (paths, exception names) should be reachable via
    // horizontal scroll rather than silently clipping. Shared with the install console treatment.
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
    ) {
      Text(
        text = errorMessage,
        color = WorkspaceRed,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        softWrap = false,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun ChangedFileGroupSection(
  title: String,
  group: ChangedFileGroup,
  files: List<ChangedFile>,
  selectedPath: String?,
  stageActionsEnabled: Boolean,
  onChangedFileSelected: (String) -> Unit,
  onStageChangedFile: (String) -> Unit,
  onUnstageChangedFile: (String) -> Unit,
  onCopyChangedFilePath: (String) -> Unit,
  recentlyCopiedKey: String?,
) {
  val groupFiles = files.filter { it.group == group }
  if (groupFiles.isEmpty()) {
    return
  }
  Text(
    text = "$title (${groupFiles.size})",
    color = WorkspaceSteel,
    fontSize = 10.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 6.dp),
  )
  groupFiles.forEach { file ->
    ChangedFileRow(
      file = file,
      selected = file.path == selectedPath,
      stageActionsEnabled = stageActionsEnabled,
      onChangedFileSelected = onChangedFileSelected,
      onStageChangedFile = onStageChangedFile,
      onUnstageChangedFile = onUnstageChangedFile,
      onCopyChangedFilePath = onCopyChangedFilePath,
      recentlyCopiedKey = recentlyCopiedKey,
    )
  }
}

@Composable
private fun ChangedFileRow(
  file: ChangedFile,
  selected: Boolean,
  stageActionsEnabled: Boolean,
  onChangedFileSelected: (String) -> Unit,
  onStageChangedFile: (String) -> Unit,
  onUnstageChangedFile: (String) -> Unit,
  onCopyChangedFilePath: (String) -> Unit,
  recentlyCopiedKey: String?,
) {
  val tone = when (file.group) {
    ChangedFileGroup.STAGED -> Tone.Success
    ChangedFileGroup.UNSTAGED -> Tone.Warning
    ChangedFileGroup.UNTRACKED -> Tone.Neutral
    ChangedFileGroup.GENERATED -> Tone.Warning
  }
  val background = if (selected) WorkspaceYellow.copy(alpha = 0.12f) else Color.Transparent
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(background)
      .semantics { this.selected = selected }
      .clickable(role = Role.Button) { onChangedFileSelected(file.path) }
      .padding(horizontal = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // AC4: generated artifacts get the "RO" label and no Open action; rendered later as a non-clickable
    // marker. The status code uses a monospace badge so single-letter codes line up.
    // F-X-502: wrap the "RO" badge in a Compose Desktop TooltipArea explaining the read-only contract
    // so users understand why the row exposes no stage/unstage actions and that Render refreshes it.
    val statusText: @Composable () -> Unit = {
      Text(
        text = if (file.isGenerated) "RO" else file.statusCode,
        color = tone.color(),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(28.dp),
      )
    }
    if (file.isGenerated) {
      ReadOnlyArtifactTooltip { statusText() }
    } else {
      statusText()
    }
    Text(
      text = file.path,
      color = WorkspaceText.copy(alpha = if (file.isGenerated) 0.7f else 0.92f),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    // F-U05 / F-X-501: each icon-text action gets a min hit target + parameterized contentDescription
    // so screen readers and pointer users can distinguish actions on adjacent rows.
    // F-X-512: when this file's path matches the recently-copied key, briefly render "copied" so the
    // user gets visual confirmation that the clipboard write happened.
    val showCopied = recentlyCopiedKey == file.path
    Text(
      text = if (showCopied) "copied" else "copy",
      color = if (showCopied) Tone.Success.color() else WorkspaceYellow,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier
        .iconButtonSemantics(description = "Copy path: ${file.path}")
        .clickable(role = Role.Button) { onCopyChangedFilePath(file.path) }
        .padding(horizontal = 6.dp, vertical = 4.dp),
    )
    // AC4: generated artifacts must NOT expose stage/unstage actions; users cannot reopen them
    // editable either (handled at the editor level — the read-only banner already enforces this).
    if (!file.isGenerated) {
      when (file.group) {
        ChangedFileGroup.STAGED -> Text(
          text = "unstage",
          color = if (stageActionsEnabled) WorkspaceYellow else WorkspaceSteel,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier
            .iconButtonSemantics(description = "Unstage file: ${file.path}")
            .clickable(enabled = stageActionsEnabled, role = Role.Button) { onUnstageChangedFile(file.path) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        ChangedFileGroup.UNSTAGED, ChangedFileGroup.UNTRACKED -> Text(
          text = "stage",
          color = if (stageActionsEnabled) WorkspaceYellow else WorkspaceSteel,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier
            .iconButtonSemantics(description = "Stage file: ${file.path}")
            .clickable(enabled = stageActionsEnabled, role = Role.Button) { onStageChangedFile(file.path) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        ChangedFileGroup.GENERATED -> Unit
      }
    }
  }
}

// F-X-502: TooltipArea wrapper for the "RO" badge on Generated rows. Mirrors the project tone
// styling so the tooltip surface is consistent with other ambient surfaces.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadOnlyArtifactTooltip(content: @Composable () -> Unit) {
  TooltipArea(
    tooltip = {
      Box(
        modifier = Modifier
          .background(WorkspaceRaised, RoundedCornerShape(4.dp))
          .border(1.dp, WorkspaceLine, RoundedCornerShape(4.dp))
          .padding(horizontal = 8.dp, vertical = 6.dp),
      ) {
        Text(
          text = "Generated artifact - read-only. Re-run Render to refresh.",
          color = WorkspaceText,
          fontSize = 11.sp,
        )
      }
    },
    content = content,
  )
}

@Composable
private fun ChangesDiffPane(
  selectedChangedFile: ChangedFile?,
  selectedDiff: String,
  selectedDiffBusy: Boolean,
  modifier: Modifier = Modifier,
) {
  // F-U04: padding lives on the outer column so it stays fixed while content scrolls. The inner
  // scrolled column inherits the padded viewport without the padding scrolling out of view.
  Column(modifier = modifier.background(WorkspaceBackground).padding(10.dp)) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
      if (selectedChangedFile == null) {
        Text(
          text = "Select a changed file to view its diff.",
          color = WorkspaceSteel,
          fontSize = 11.sp,
        )
        return@Column
      }
      Text(
        text = selectedChangedFile.path,
        color = WorkspaceMuted,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (selectedDiffBusy) {
        Text(text = "Loading diff...", color = WorkspaceSteel, fontSize = 11.sp)
        return@Column
      }
      if (selectedDiff.isBlank()) {
        Text(text = "(no diff available)", color = WorkspaceSteel, fontSize = 11.sp)
        return@Column
      }
      // F-601 mirror: shared horizontalScroll lets long diff lines stay reachable without clipping.
      // F-U01: softWrap=false + maxLines=1 so long unbreakable tokens (paths, hashes) push out to
      // the right and become reachable via the horizontal scroll instead of silently wrapping.
      Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        selectedDiff.lines().forEach { line ->
          Text(
            text = line,
            color = diffLineColor(line),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            maxLines = 1,
          )
        }
      }
    }
  }
}

private fun diffLineColor(line: String): Color = when {
  line.startsWith("+++") || line.startsWith("---") -> WorkspaceMuted
  line.startsWith("@@") -> WorkspaceAmber
  line.startsWith("+") -> WorkspaceGreen
  line.startsWith("-") -> WorkspaceRed
  else -> WorkspaceText.copy(alpha = 0.85f)
}

@Composable
private fun HistoryPanel(
  history: List<CommitEntry>,
  historyBusy: Boolean,
  historyErrorMessage: String?,
  historyPathFilter: String?,
  hasRepoOpen: Boolean,
  onCopyCommitHash: (String) -> Unit,
  onClearHistoryPathFilter: () -> Unit,
  recentlyCopiedKey: String?,
) {
  Column(modifier = Modifier.fillMaxSize().padding(6.dp).verticalScroll(rememberScrollState())) {
    if (!hasRepoOpen) {
      // AC5: history empty-state when no Git repo is open.
      Text(
        text = "Open a Git repository to see recent commits.",
        color = WorkspaceSteel,
        fontSize = 11.sp,
        modifier = Modifier.padding(8.dp),
      )
      return@Column
    }
    if (historyPathFilter != null) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "Filtered by",
          color = WorkspaceSteel,
          fontSize = 10.sp,
        )
        Text(
          text = historyPathFilter,
          color = WorkspaceYellow,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        // F-U05 / F-X-501: a11y treatment for the clear-filter chip.
        Text(
          text = "clear",
          color = WorkspaceYellow,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier
            .iconButtonSemantics(description = "Clear history path filter")
            .clickable(role = Role.Button, onClick = onClearHistoryPathFilter)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        )
      }
    }
    // F-X-503: when an error is present AND prior history is non-empty, surface a single
    // visually-distinct banner so users see the rows below are stale. Keep the existing error
    // text path for the no-data case.
    if (historyErrorMessage != null && history.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
      ) {
        Text(
          text = "Showing previous results - refresh failed.",
          color = Tone.Warning.color(),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
    if (historyErrorMessage != null) {
      // AC11 / F-601 mirror: surface errors without mutating other state. Shared horizontalScroll
      // so long error lines stay reachable.
      // F-U01: softWrap=false + maxLines=1 so long unbreakable tokens are reachable via horizontal
      // scroll rather than silently clipping.
      Column(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
        Text(
          text = historyErrorMessage,
          color = WorkspaceRed,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          softWrap = false,
          maxLines = 1,
        )
      }
    }
    if (historyBusy) {
      Text(text = "Loading recent commits...", color = WorkspaceSteel, fontSize = 11.sp)
      return@Column
    }
    if (history.isEmpty() && historyErrorMessage == null) {
      // F-X-504: filter-aware empty-state — when a path filter is active and produced zero results,
      // tell the user what's filtered and offer a clickable Clear filter affordance. Otherwise show
      // the generic "No commits to show." text.
      if (historyPathFilter != null) {
        Column(modifier = Modifier.padding(8.dp)) {
          Text(
            text = "No commits for `$historyPathFilter`. Clear filter to see all commits.",
            color = WorkspaceSteel,
            fontSize = 11.sp,
          )
          Text(
            text = "Clear filter",
            color = WorkspaceYellow,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
              .padding(top = 4.dp)
              .iconButtonSemantics(description = "Clear history path filter")
              .clickable(role = Role.Button, onClick = onClearHistoryPathFilter)
              .padding(horizontal = 6.dp, vertical = 4.dp),
          )
        }
      } else {
        Text(text = "No commits to show.", color = WorkspaceSteel, fontSize = 11.sp)
      }
      return@Column
    }
    history.forEach { entry ->
      CommitRow(
        entry = entry,
        onCopyCommitHash = onCopyCommitHash,
        recentlyCopiedKey = recentlyCopiedKey,
      )
    }
  }
}

@Composable
private fun CommitRow(entry: CommitEntry, onCopyCommitHash: (String) -> Unit, recentlyCopiedKey: String?) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = entry.shortHash,
      color = WorkspaceYellow,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(72.dp),
      maxLines = 1,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.subject,
        color = WorkspaceText.copy(alpha = 0.9f),
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = entry.author,
          color = WorkspaceMuted,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
        )
        Text(
          text = entry.isoDate,
          color = WorkspaceSteel,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
    }
    // F-U05 / F-X-501: a11y treatment + larger hit target.
    // F-X-512: brief "copied" feedback when the key matches this row's full hash.
    val showCopied = recentlyCopiedKey == entry.fullHash
    Text(
      text = if (showCopied) "copied" else "copy hash",
      color = if (showCopied) Tone.Success.color() else WorkspaceYellow,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier
        .iconButtonSemantics(description = "Copy commit hash: ${entry.shortHash}")
        .clickable(role = Role.Button) { onCopyCommitHash(entry.fullHash) }
        .padding(horizontal = 6.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun InstallConsole(editor: EditorPlaceholder, render: RenderSummary) {
  // F-601: long unbreakable tokens (paths, exception class names) in line content can clip silently
  // at narrow dock widths. One shared horizontalScroll state on the inner column keeps all lines
  // aligned and lets the user scroll right to reach any clipped failure text. softWrap stays at its
  // default `true` so wrappable content still wraps. Do NOT use maxLines/Ellipsis here — that would
  // hide AC5 failure text.
  Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
      val lines = buildInstallConsoleLines(editor = editor, render = render)
      lines.forEachIndexed { index, line ->
        Row(modifier = Modifier.padding(vertical = 2.dp)) {
          Text(
            text = (index + 1).toString().padStart(2, '0'),
            color = WorkspaceSteel,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(34.dp),
          )
          Text(text = line.text, color = line.tone.color(), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
      }
    }
  }
}

private fun buildInstallConsoleLines(editor: EditorPlaceholder, render: RenderSummary): List<ConsoleLine> {
  val target = editor.skillName ?: editor.authoredPath ?: editor.title
  return when (render.state) {
    RenderRunState.UNAVAILABLE -> listOf(
      ConsoleLine("Selected: ${editor.title}", Tone.Neutral),
      ConsoleLine("Authored path: ${editor.authoredPath ?: "-"}", Tone.Neutral),
      ConsoleLine("render: not run for this selection", Tone.Warning),
    )
    RenderRunState.RUNNING -> listOf(
      ConsoleLine("> render $target", Tone.Neutral),
      ConsoleLine("  resolving target...", Tone.Neutral),
      ConsoleLine("render: rendering...", Tone.Warning),
    )
    RenderRunState.PASSED, RenderRunState.FAILED -> buildList {
      add(ConsoleLine("> render $target", Tone.Neutral))
      add(ConsoleLine("  resolving target...", Tone.Neutral))
      render.blocks.forEachIndexed { index, block ->
        val phase = phaseHeaderForBlock(block.header, index)
        if (phase != null) {
          add(ConsoleLine("  $phase", Tone.Neutral))
        }
        add(ConsoleLine(block.header, Tone.Neutral))
        block.content.lines().forEach { line -> add(ConsoleLine(line, Tone.Neutral)) }
      }
      add(terminalRenderLine(render))
    }
  }
}

private fun phaseHeaderForBlock(header: String, index: Int): String? = when {
  header.startsWith("===== SKILL.md:") -> "rendering wrapper"
  header.startsWith("===== pointer:") -> "rendering pointer $index"
  header.startsWith("===== native-agent:") -> "rendering native agent"
  header.startsWith("===== addon:") -> "rendering add-on"
  else -> null
}

private fun terminalRenderLine(render: RenderSummary): ConsoleLine = when (render.state) {
  RenderRunState.PASSED ->
    ConsoleLine("render: passed in ${render.durationMillis} ms", Tone.Success)
  RenderRunState.FAILED -> {
    val suffix = formatRenderExceptionSuffix(render)
    ConsoleLine("render: failed in ${render.durationMillis} ms$suffix", Tone.Error)
  }
  RenderRunState.RUNNING -> ConsoleLine("render: rendering...", Tone.Warning)
  RenderRunState.UNAVAILABLE -> ConsoleLine("render: not run for this selection", Tone.Warning)
}

private fun formatRenderExceptionSuffix(render: RenderSummary): String {
  val name = render.runtimeExceptionName ?: return ""
  val message = render.runtimeExceptionMessage
  return if (message.isNullOrBlank()) " - $name" else " - $name: $message"
}

@Composable
private fun TableHeader(a: String, b: String, c: String, d: String) {
  Row(
    modifier = Modifier.fillMaxWidth().height(28.dp).background(WorkspaceBackground),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    HeaderCell(a, 54.dp)
    HeaderCell(b, 78.dp)
    HeaderCell(c, null, Modifier.weight(1f))
    HeaderCell(d, 260.dp)
  }
}

@Composable
private fun HeaderCell(text: String, width: Dp?, modifier: Modifier = Modifier) {
  Text(
    text = text,
    color = WorkspaceSteel,
    fontSize = 10.5.sp,
    fontFamily = FontFamily.Monospace,
    modifier = (width?.let { Modifier.width(it) } ?: modifier).padding(start = 12.dp),
    maxLines = 1,
  )
}

@Composable
private fun TableRow(first: String, second: String, third: String, fourth: String, tone: Tone) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(30.dp)
      .border(BorderStroke(0.dp, Color.Transparent)),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      first,
      color = tone.color(),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(54.dp).padding(start = 12.dp),
    )
    Text(
      second,
      color = tone.color(),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(78.dp),
    )
    Text(
      third,
      color = WorkspaceText.copy(alpha = 0.9f),
      fontSize = 12.sp,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      fourth,
      color = WorkspaceMuted,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.width(260.dp),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun WorkspaceStatusBar(state: SkillBillState) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .background(WorkspacePanel)
      .padding(horizontal = 12.dp)
      .horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    StatusItem("br", state.statusBar.branchLabel, Tone.Neutral)
    StatusItem("rp", state.statusBar.repoPathLabel, Tone.Neutral)
    StatusItem("tr", "${state.statusBar.targetCount} targets", Tone.Neutral)
    val validationStatus = describeValidationStatus(state.validation)
    StatusItem("vl", validationStatus.label, validationStatus.tone)
    val renderStatus = describeRenderStatus(state.render)
    StatusItem("rn", renderStatus.label, renderStatus.tone)
    Spacer(modifier = Modifier.weight(1f))
    StatusItem("ro", state.statusBar.readOnlyModeLabel, Tone.Warning)
    StatusItem("lk", state.statusBar.policyLabel, Tone.Neutral)
  }
}

private data class ValidationStatusDescription(val label: String, val tone: Tone)

private data class RenderStatusDescription(val label: String, val tone: Tone)

private fun renderHeaderLabelFor(render: RenderSummary): String? = when (render.state) {
  RenderRunState.PASSED -> "passed in ${render.durationMillis} ms"
  RenderRunState.FAILED -> "failed in ${render.durationMillis} ms"
  RenderRunState.RUNNING -> "running"
  RenderRunState.UNAVAILABLE -> null
}

private fun renderHeaderToneFor(render: RenderSummary): Tone = when (render.state) {
  RenderRunState.PASSED -> Tone.Success
  RenderRunState.FAILED -> Tone.Error
  RenderRunState.RUNNING -> Tone.Warning
  RenderRunState.UNAVAILABLE -> Tone.Neutral
}

private fun describeRenderStatus(render: RenderSummary): RenderStatusDescription = when (render.state) {
  RenderRunState.UNAVAILABLE -> RenderStatusDescription("render: unavailable", Tone.Neutral)
  RenderRunState.RUNNING -> RenderStatusDescription("render: running", Tone.Warning)
  RenderRunState.PASSED -> RenderStatusDescription("render: passed", Tone.Success)
  RenderRunState.FAILED -> RenderStatusDescription("render: failed", Tone.Error)
}

private fun describeValidationStatus(validation: ValidationSummary): ValidationStatusDescription =
  when (validation.state) {
    ValidationRunState.UNAVAILABLE -> ValidationStatusDescription("validation: unavailable", Tone.Neutral)
    ValidationRunState.RUNNING -> ValidationStatusDescription("validation: running", Tone.Warning)
    ValidationRunState.PASSED -> ValidationStatusDescription("validation: passed", Tone.Success)
    ValidationRunState.FAILED -> {
      val count = validation.issues.size
      val label = if (count == 0) "validation: failed" else "validation: failed - $count issue(s)"
      ValidationStatusDescription(label, Tone.Error)
    }
  }

@Composable
private fun StatusItem(marker: String, text: String, tone: Tone) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    MiniIcon(text = marker, tint = if (tone == Tone.Neutral) WorkspaceYellow else tone.color())
    Text(text = text, color = tone.color(), fontSize = 11.sp, maxLines = 1)
  }
}

@Composable
private fun LabelText(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    color = WorkspaceSteel,
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
  Text(
    text = text,
    color = tone.color(),
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    modifier =
    Modifier
      .border(1.dp, tone.color().copy(alpha = 0.45f), RoundedCornerShape(4.dp))
      .background(tone.color().copy(alpha = 0.16f), RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 1.dp),
    maxLines = 1,
  )
}

@Composable
private fun StatusDot(level: ValidationLevel?) {
  val color = when (level) {
    ValidationLevel.Ok -> WorkspaceGreen
    ValidationLevel.Warn -> WorkspaceAmber
    ValidationLevel.Error -> WorkspaceRed
    null -> WorkspaceSteel
  }
  Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
}

@Composable
private fun MiniIcon(text: String, tint: Color) {
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

private enum class Tone {
  Neutral,
  Success,
  Warning,
  Error,
}

@Composable
private fun Tone.color(): Color = when (this) {
  Tone.Neutral -> WorkspaceMuted
  Tone.Success -> WorkspaceGreen
  Tone.Warning -> WorkspaceAmber
  Tone.Error -> WorkspaceRed
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

private data class WorkspaceGroup(
  val id: String,
  val label: String,
  val marker: String,
  val nodes: List<WorkspaceNode>,
)

private data class WorkspaceNode(
  val id: String,
  val label: String,
  val marker: String,
  val status: ValidationLevel,
  val changed: Boolean = false,
)

private data class ValidationRow(
  val level: ValidationLevel,
  val code: String,
  val message: String,
  val source: String,
)

private data class ConsoleLine(val text: String, val tone: Tone)

private const val DEFAULT_SELECTED_NODE_ID = "s-meeting"

private val WorkspaceGroups = listOf(
  WorkspaceGroup(
    id = "skills",
    label = "Skills",
    marker = "sk",
    nodes = listOf(
      WorkspaceNode(id = "s-invoice", label = "invoice-extractor", marker = "sk", status = ValidationLevel.Ok),
      WorkspaceNode(
        id = "s-meeting",
        label = "meeting-summarizer",
        marker = "sk",
        status = ValidationLevel.Warn,
        changed = true,
      ),
      WorkspaceNode(
        id = "s-router",
        label = "intent-router",
        marker = "sk",
        status = ValidationLevel.Error,
        changed = true,
      ),
      WorkspaceNode(id = "s-csv", label = "csv-normalizer", marker = "sk", status = ValidationLevel.Ok),
      WorkspaceNode(id = "s-pii", label = "pii-redactor", marker = "sk", status = ValidationLevel.Ok),
      WorkspaceNode(
        id = "s-trans",
        label = "transcript-cleaner",
        marker = "sk",
        status = ValidationLevel.Ok,
        changed = true,
      ),
    ),
  ),
  WorkspaceGroup(
    id = "packs",
    label = "Platform Packs",
    marker = "pk",
    nodes = listOf(
      WorkspaceNode(id = "p-zen", label = "zendesk-pack", marker = "pk", status = ValidationLevel.Ok),
      WorkspaceNode(id = "p-sf", label = "salesforce-pack", marker = "pk", status = ValidationLevel.Warn),
      WorkspaceNode(id = "p-slack", label = "slack-pack", marker = "pk", status = ValidationLevel.Ok),
    ),
  ),
  WorkspaceGroup(
    id = "addons",
    label = "Add-ons",
    marker = "ad",
    nodes = listOf(
      WorkspaceNode(id = "a-trace", label = "tracing-otel", marker = "ad", status = ValidationLevel.Ok),
      WorkspaceNode(id = "a-eval", label = "eval-harness", marker = "ad", status = ValidationLevel.Ok),
    ),
  ),
  WorkspaceGroup(
    id = "agents",
    label = "Native Agents",
    marker = "ag",
    nodes = listOf(
      WorkspaceNode(id = "n-triage", label = "support-triage", marker = "ag", status = ValidationLevel.Ok),
      WorkspaceNode(id = "n-onboard", label = "onboarding-bot", marker = "ag", status = ValidationLevel.Warn),
    ),
  ),
)

private val SkillSourceLines = """
  # meeting-summarizer
  # contract: v3.2 - governed authoring
  name: meeting-summarizer
  version: 1.4.0
  owner: ai-platform@skillbill
  visibility: internal

  inputs:
    transcript:
      type: text
      required: true
      max_tokens: 32000
    participants:
      type: list<string>
      required: false

  routing:
    intents: ["meeting.summary", "notes.action_items"]
    confidence_floor: 0.62
    fallback: ai-platform.passthrough

  dependencies:
    - skill: pii-redactor   ^2.0
    - addon: tracing-otel   ^1.1

  contract:
    output_schema: ./schemas/summary.v3.json
    must_emit: ["summary", "action_items", "decisions"]
    forbid_pii: true

  install:
    generated_from: ./src/skill.ts
    artifacts:
      - dist/skill.bundle.mjs
      - dist/contract.lock.json

  audit:
    last_publish: 2025-04-30T14:22:00Z
    last_publisher: nadia.k
    signed: true
""".trimIndent().lines()

private val ValidationRows = listOf(
  ValidationRow(
    ValidationLevel.Error,
    "C-204",
    "Output schema missing field 'decisions[].owner'",
    "schemas/summary.v3.json",
  ),
  ValidationRow(ValidationLevel.Warn, "R-118", "Confidence floor below pack baseline (0.70)", "skill.yaml"),
  ValidationRow(
    ValidationLevel.Warn,
    "G-041",
    "Generated artifact older than source by 3 commits",
    "dist/skill.bundle.mjs",
  ),
  ValidationRow(ValidationLevel.Ok, "S-001", "Signature verified for owner ai-platform@skillbill", "skill.yaml"),
  ValidationRow(ValidationLevel.Ok, "D-022", "All declared dependencies resolved at compatible versions", "-"),
)

private val ConsoleLines = listOf(
  ConsoleLine("> skillbill build --skill meeting-summarizer", Tone.Neutral),
  ConsoleLine("  resolving dependencies... 2/2 ok", Tone.Neutral),
  ConsoleLine("  emitting dist/skill.bundle.mjs (18.4 kb)", Tone.Neutral),
  ConsoleLine("  generated artifact older than source by 3 commits", Tone.Warning),
  ConsoleLine("  validating contract v3.2 against output_schema", Tone.Neutral),
  ConsoleLine("  schema field missing: decisions[].owner (C-204)", Tone.Error),
  ConsoleLine("  rendering install preview...", Tone.Neutral),
  ConsoleLine("build failed in 14.2s - 1 error, 2 warnings", Tone.Error),
)
