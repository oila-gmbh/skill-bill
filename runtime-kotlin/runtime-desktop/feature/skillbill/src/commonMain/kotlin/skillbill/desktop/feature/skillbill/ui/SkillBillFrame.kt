@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.domain.model.DockTab
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
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
  onTreeItemSelected: (String) -> Unit,
  onTreeItemExpandedToggled: (String) -> Unit,
  onMoveTreeSelection: (Int) -> Unit,
  onValidationIssueSelected: (ValidationIssue) -> Unit,
  onCopyIssueSource: (String) -> Unit,
  onActiveDockTabChanged: (DockTab) -> Unit,
) {
  val validateEnabled =
    state.selectedRepoPath != null &&
      state.repoStatus.state == RepoLoadState.LOADED &&
      state.busyOperation == null
  val renderEnabled =
    state.renderable &&
      state.repoStatus.state == RepoLoadState.LOADED &&
      state.busyOperation == null
  Column(modifier = Modifier.fillMaxSize().background(WorkspaceBackground)) {
    WorkspaceToolbar(
      canNavigateBack = canNavigateBack,
      onNavigateBack = onNavigateBack,
      onRefresh = onRefresh,
      onValidate = onValidate,
      onRender = onRender,
      validateEnabled = validateEnabled,
      renderEnabled = renderEnabled,
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
  sourceControlLabel: String,
  readOnlyModeLabel: String,
  busyOperation: SkillBillBusyOperation?,
) {
  val busy = busyOperation != null
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
  policyLabel: String,
  validationIssueCount: Int,
  onRepoPathChanged: (String) -> Unit,
  onRepoSelected: (String) -> Unit,
  onChooseRepoDirectory: () -> Unit,
  onNodeSelected: (String) -> Unit,
  onNodeExpandedToggled: (String) -> Unit,
  onMoveSelection: (Int) -> Unit,
) {
  val busy = busyOperation != null
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
      Text(
        text = if (busy) "Busy" else "Open",
        color = if (busy) WorkspaceSteel else WorkspaceYellow,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { onRepoSelected(repoPath) },
      )
      Text(
        text = "...",
        color = if (busy) WorkspaceSteel else WorkspaceYellow,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onChooseRepoDirectory),
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
        .height(27.dp)
        .padding(start = 2.dp, end = 4.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(rowBackground)
        .semantics { this.selected = selected }
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
  modifier: Modifier,
) {
  Column(modifier = modifier.background(WorkspaceBackground)) {
    EditorTabs(editor)
    CodeEditor(editor = editor, modifier = Modifier.weight(1f))
    BottomDock(
      editor = editor,
      validation = validation,
      render = render,
      activeTab = activeDockTab,
      onActiveTabSelected = onActiveDockTabChanged,
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
      dirty = false,
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
private fun CodeEditor(editor: EditorPlaceholder, modifier: Modifier = Modifier) {
  val lines =
    (editor.content ?: editor.detail)
      .ifBlank { "No source selected" }
      .lines()
  Column(
    modifier =
    modifier
      .fillMaxWidth()
      .background(WorkspaceBackground)
      .verticalScroll(rememberScrollState()),
  ) {
    if (!editor.editable) {
      ReadOnlyBanner(editor)
    }
    lines.forEachIndexed { index, line ->
      CodeLine(number = index + 1, line = line, flagged = false)
    }
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
        "Generated artifact is ${editor.readOnlyLabel ?: "read-only"}"
      } else {
        "Read-only browser"
      },
      color = WorkspaceMuted,
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
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
          badge = badgeForDockTab(tab, validation),
          active = activeTab == tab,
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
        DockTab.Changes -> ChangesTable(editor)
        DockTab.History -> HistoryTable()
        DockTab.Console -> InstallConsole(editor = editor, render = render)
      }
    }
  }
}

private fun badgeForDockTab(tab: DockTab, validation: ValidationSummary): String? = when (tab) {
  DockTab.Validation -> validation.issues.size.takeIf { it > 0 }?.toString()
  else -> dockTabMetadata(tab).badge
}

@Composable
private fun DockTabButton(tab: DockTab, badge: String?, active: Boolean, onSelected: () -> Unit) {
  val meta = dockTabMetadata(tab)
  Column(
    modifier =
    Modifier
      .height(33.dp)
      .width(meta.width)
      .background(if (active) WorkspaceBackground else WorkspacePanel)
      .clickable(role = Role.Button, onClick = onSelected),
  ) {
    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (active) WorkspaceYellow else Color.Transparent))
    Row(
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Text(text = meta.label, color = if (active) WorkspaceText else WorkspaceMuted, fontSize = 12.sp)
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
private fun ChangesTable(editor: EditorPlaceholder) {
  Column(modifier = Modifier.fillMaxSize().padding(6.dp).verticalScroll(rememberScrollState())) {
    editor.generatedArtifacts.forEach { artifact ->
      TableRow("RO", "generated", artifact.reason, artifact.path, Tone.Warning)
    }
  }
}

@Composable
private fun HistoryTable() {
  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    HistoryRows.forEach { row ->
      TableRow(
        first = row.sha,
        second = row.who,
        third = row.message,
        fourth = row.timeAgo,
        tone = if (row.ok) Tone.Success else Tone.Warning,
      )
    }
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

private data class ChangedFile(val path: String, val state: String)

private data class HistoryRow(
  val sha: String,
  val who: String,
  val timeAgo: String,
  val message: String,
  val ok: Boolean,
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

private val ChangedFiles = listOf(
  ChangedFile("skills/meeting-summarizer/skill.yaml", "M"),
  ChangedFile("skills/meeting-summarizer/schemas/summary.v3.json", "M"),
  ChangedFile("skills/intent-router/routes.yaml", "M"),
  ChangedFile("skills/transcript-cleaner/skill.yaml", "A"),
  ChangedFile("packs/zendesk-pack/manifest.yaml", "M"),
)

private val HistoryRows = listOf(
  HistoryRow("8f3a912", "nadia.k", "12 min ago", "tighten contract on summarizer outputs", true),
  HistoryRow("1d04e77", "ravi.p", "1 h ago", "add transcript-cleaner skill", true),
  HistoryRow("44b9ce2", "marko.s", "3 h ago", "raise router confidence floor", false),
  HistoryRow("9aa2204", "nadia.k", "yesterday", "regen install artifacts", true),
  HistoryRow("0b71ee8", "ci-bot", "yesterday", "rotate signing key", true),
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
