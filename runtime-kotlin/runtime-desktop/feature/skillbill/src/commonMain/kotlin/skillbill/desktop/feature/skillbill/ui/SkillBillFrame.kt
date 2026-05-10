@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import skillbill.desktop.core.domain.model.SkillBillState

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
  onRepoSelected: (String) -> Unit,
  onTreeItemSelected: (String) -> Unit,
) {
  var selectedNodeId by remember(state.selectedTreeItemId) {
    mutableStateOf(state.selectedTreeItemId ?: DEFAULT_SELECTED_NODE_ID)
  }
  var repoPath by remember(state.selectedRepoPath) {
    mutableStateOf(state.selectedRepoPath ?: "~/code/skillbill/acme-ai-platform")
  }

  Column(modifier = Modifier.fillMaxSize().background(WorkspaceBackground)) {
    WorkspaceToolbar(
      canNavigateBack = canNavigateBack,
      onNavigateBack = onNavigateBack,
    )
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
      NavigationPane(
        repoPath = repoPath,
        selectedNodeId = selectedNodeId,
        onRepoSelected = {
          repoPath = it
          onRepoSelected(it)
        },
        onNodeSelected = {
          selectedNodeId = it
          onTreeItemSelected(it)
        },
      )
      VerticalDivider(color = WorkspaceLine)
      CenterWorkspace(modifier = Modifier.weight(1f).fillMaxHeight())
      InspectorPane(selectedNodeId = selectedNodeId)
    }
    WorkspaceStatusBar()
  }
}

@Composable
private fun WorkspaceToolbar(canNavigateBack: Boolean, onNavigateBack: () -> Unit) {
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
      ToolbarButton(label = "Back", marker = "<", onClick = onNavigateBack)
      Spacer(modifier = Modifier.width(8.dp))
      ToolbarDivider()
    }
    ToolbarButton(label = "main · governed", marker = "br")
    ToolbarDivider()
    ToolbarButton(label = "Validate", marker = "ok")
    ToolbarButton(label = "Build install", marker = "bd")
    ToolbarButton(label = "Render check", marker = "rc")
    ToolbarDivider()
    ToolbarButton(label = "Publish", marker = "up", primary = true)
    Spacer(modifier = Modifier.weight(1f))
    SearchBox()
  }
}

@Composable
private fun ToolbarButton(label: String, marker: String, primary: Boolean = false, onClick: () -> Unit = {}) {
  val background = if (primary) WorkspaceYellow else WorkspaceRaised
  val foreground = if (primary) Color(0xFF0B0B0D) else WorkspaceText
  val border = if (primary) WorkspaceYellow else WorkspaceLine
  Row(
    modifier =
    Modifier
      .height(28.dp)
      .padding(end = 6.dp)
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, border, RoundedCornerShape(6.dp))
      .background(background)
      .clickable(role = Role.Button, onClick = onClick)
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
  selectedNodeId: String,
  onRepoSelected: (String) -> Unit,
  onNodeSelected: (String) -> Unit,
) {
  Column(
    modifier =
    Modifier
      .width(SkillBillMetrics.treePaneWidth)
      .fillMaxHeight()
      .background(WorkspaceSidebar),
  ) {
    RepositorySelector(repoPath = repoPath, onRepoSelected = onRepoSelected)
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
      WorkspaceGroups.forEach { group ->
        NavGroup(group = group, selectedNodeId = selectedNodeId, onNodeSelected = onNodeSelected)
      }
      HorizontalDivider(modifier = Modifier.padding(top = 10.dp, bottom = 8.dp), color = WorkspaceLine)
      RepositoryAction(label = "Validation", marker = "vl", badge = "2")
      RepositoryAction(label = "History", marker = "hi")
      RepositoryAction(label = "Publishing", marker = "pb")
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
      Text(text = "strict", color = WorkspaceText, fontSize = 11.sp)
    }
  }
}

@Composable
private fun RepositorySelector(repoPath: String, onRepoSelected: (String) -> Unit) {
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
        .clickable(role = Role.Button) { onRepoSelected(repoPath) }
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MiniIcon(text = "db", tint = WorkspaceYellow)
      Text(
        text = "acme-ai-platform",
        color = WorkspaceText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(text = "⌄", color = WorkspaceSteel, fontSize = 13.sp)
    }
    Text(
      text = repoPath,
      color = WorkspaceSteel,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.padding(top = 6.dp),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun NavGroup(group: WorkspaceGroup, selectedNodeId: String, onNodeSelected: (String) -> Unit) {
  Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().height(27.dp).padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(text = "⌄", color = WorkspaceSteel, fontSize = 12.sp)
      MiniIcon(text = group.marker, tint = WorkspaceSteel)
      Text(
        text = group.label,
        color = WorkspaceSteel,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        modifier = Modifier.weight(1f),
      )
      Text(text = group.nodes.size.toString(), color = WorkspaceSteel, fontSize = 11.sp)
    }
    group.nodes.forEach { node ->
      NavNodeRow(
        node = node,
        selected = selectedNodeId == node.id,
        onNodeSelected = onNodeSelected,
      )
    }
  }
}

@Composable
private fun NavNodeRow(node: WorkspaceNode, selected: Boolean, onNodeSelected: (String) -> Unit) {
  val rowBackground = if (selected) WorkspaceYellow.copy(alpha = 0.15f) else Color.Transparent
  val iconTint = if (selected) WorkspaceYellow else WorkspaceSteel
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .padding(start = 2.dp, end = 4.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(rowBackground)
      .semantics { this.selected = selected }
      .clickable(role = Role.Button) { onNodeSelected(node.id) },
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
    MiniIcon(text = node.marker, tint = iconTint)
    Text(
      text = node.label,
      color = WorkspaceText.copy(alpha = if (selected) 1f else 0.86f),
      fontSize = 12.5.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.padding(start = 8.dp).weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (node.changed) {
      Text(
        text = "M",
        color = WorkspaceAmber,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(end = 8.dp),
      )
    }
    StatusDot(level = node.status)
    Spacer(modifier = Modifier.width(8.dp))
  }
}

@Composable
private fun RepositoryAction(label: String, marker: String, badge: String? = null) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .padding(horizontal = 6.dp)
      .clip(RoundedCornerShape(3.dp))
      .clickable(role = Role.Button) {}
      .padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = marker, tint = WorkspaceSteel)
    Text(
      text = label,
      color = WorkspaceText.copy(alpha = 0.86f),
      fontSize = 12.5.sp,
      modifier = Modifier.weight(1f),
    )
    if (badge != null) {
      Badge(text = badge, tone = Tone.Error)
    }
  }
}

@Composable
private fun CenterWorkspace(modifier: Modifier) {
  Column(modifier = modifier.background(WorkspaceBackground)) {
    EditorTabs()
    CodeEditor(modifier = Modifier.weight(1f))
    BottomDock()
  }
}

@Composable
private fun EditorTabs() {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(36.dp)
      .background(WorkspacePanel)
      .horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.Bottom,
  ) {
    EditorTab("skill.yaml", active = true, dirty = true)
    EditorTab("schemas/summary.v3.json", active = false, dirty = true)
    EditorTab("src/skill.ts", active = false, dirty = false)
    EditorTab("README.md", active = false, dirty = false)
  }
}

@Composable
private fun EditorTab(name: String, active: Boolean, dirty: Boolean) {
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
    }
  }
}

@Composable
private fun CodeEditor(modifier: Modifier = Modifier) {
  Column(
    modifier =
    modifier
      .fillMaxWidth()
      .background(WorkspaceBackground)
      .verticalScroll(rememberScrollState()),
  ) {
    SkillSourceLines.forEachIndexed { index, line ->
      CodeLine(number = index + 1, line = line, flagged = index == 11 || index == 24)
    }
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
private fun InspectorPane(selectedNodeId: String) {
  val selectedNode = WorkspaceGroups.flatMap { it.nodes }.firstOrNull { it.id == selectedNodeId }
  Column(
    modifier =
    Modifier
      .width(SkillBillMetrics.inspectorPaneWidth)
      .fillMaxHeight()
      .background(WorkspaceBackground)
      .border(BorderStroke(0.dp, Color.Transparent)),
  ) {
    InspectorHeader(selectedNode?.label ?: "meeting-summarizer")
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      InspectorSection(title = "Metadata", marker = "mt") {
        KeyValueRow("owner", "ai-platform@skillbill")
        KeyValueRow("visibility", "internal")
        KeyValueRow("contract", "v3.2")
        KeyValueRow("signed", "verified", tone = Tone.Success)
      }
      InspectorSection(title = "Contract status", marker = "vl", badge = "FAIL") {
        CheckRow(ok = true, label = "output_schema present")
        CheckRow(ok = true, label = "must_emit fields declared")
        CheckRow(ok = false, label = "schema field missing: decisions[].owner")
        CheckRow(ok = true, label = "forbid_pii enforced")
      }
      InspectorSection(title = "Routing signals", marker = "rt") {
        KeyValueRow("intents", "meeting.summary, notes.action_items")
        KeyValueRow("confidence", "0.62")
        KeyValueRow("fallback", "ai-platform.passthrough")
        KeyValueRow("pack baseline", "below 0.70", tone = Tone.Warning)
      }
      InspectorSection(title = "Dependencies", marker = "dp") {
        DependencyRow("pii-redactor", "^2.0", "2.4.1")
        DependencyRow("tracing-otel", "^1.1", "1.3.0")
      }
      InspectorSection(title = "Generated artifacts", marker = "gn") {
        KeyValueRow("skill.bundle.mjs", "stale +3", tone = Tone.Warning)
        KeyValueRow("contract.lock.json", "fresh")
        KeyValueRow("generated_from", "src/skill.ts")
      }
      InspectorSection(title = "Audit", marker = "hi") {
        KeyValueRow("last publish", "apr 30, 14:22")
        KeyValueRow("publisher", "nadia.k")
        KeyValueRow("installs (30d)", "2,481")
      }
    }
  }
}

@Composable
private fun InspectorHeader(label: String) {
  Column(modifier = Modifier.fillMaxWidth().background(WorkspacePanel).padding(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MiniIcon(text = "sk", tint = WorkspaceYellow)
      Text(
        text = label,
        color = WorkspaceText,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Badge(text = "v1.4.0", tone = Tone.Neutral)
    }
    Text(
      text = "Internal · governed authoring · skill",
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
private fun BottomDock() {
  var activeTab by remember { mutableStateOf(DockTab.Validation) }
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
        DockTabButton(tab = tab, active = activeTab == tab, onSelected = { activeTab = tab })
      }
      Spacer(modifier = Modifier.weight(1f))
      Row(
        modifier = Modifier.padding(end = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        MiniIcon(text = "run", tint = WorkspaceMuted)
        Text(text = "last run · 14s ·", color = WorkspaceMuted, fontSize = 11.sp)
        Text(text = "1 error", color = WorkspaceRed, fontSize = 11.sp)
      }
    }
    HorizontalDivider(color = WorkspaceLine)
    Box(modifier = Modifier.weight(1f).fillMaxWidth().background(WorkspaceBackground)) {
      when (activeTab) {
        DockTab.Validation -> ValidationTable()
        DockTab.Changes -> ChangesTable()
        DockTab.History -> HistoryTable()
        DockTab.Console -> InstallConsole()
      }
    }
  }
}

@Composable
private fun DockTabButton(tab: DockTab, active: Boolean, onSelected: () -> Unit) {
  Column(
    modifier =
    Modifier
      .height(33.dp)
      .width(tab.width)
      .background(if (active) WorkspaceBackground else WorkspacePanel)
      .clickable(role = Role.Button, onClick = onSelected),
  ) {
    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (active) WorkspaceYellow else Color.Transparent))
    Row(
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Text(text = tab.label, color = if (active) WorkspaceText else WorkspaceMuted, fontSize = 12.sp)
      tab.badge?.let { Badge(text = it, tone = tab.tone) }
    }
  }
}

@Composable
private fun ValidationTable() {
  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    TableHeader("Lvl", "Code", "Message", "Source")
    ValidationRows.forEach { row ->
      TableRow(
        first = row.level.marker,
        second = row.code,
        third = row.message,
        fourth = row.source,
        tone = row.level.tone,
      )
    }
  }
}

@Composable
private fun ChangesTable() {
  Column(modifier = Modifier.fillMaxSize().padding(6.dp).verticalScroll(rememberScrollState())) {
    ChangedFiles.forEach { file ->
      Row(
        modifier =
        Modifier
          .fillMaxWidth()
          .height(28.dp)
          .clip(RoundedCornerShape(3.dp))
          .clickable(role = Role.Button) {},
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = file.state,
          color = if (file.state == "A") WorkspaceGreen else WorkspaceAmber,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.width(32.dp),
        )
        Text(
          text = file.path,
          color = WorkspaceText.copy(alpha = 0.9f),
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
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
private fun InstallConsole() {
  Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
    ConsoleLines.forEachIndexed { index, line ->
      Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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
private fun WorkspaceStatusBar() {
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
    StatusItem("br", "main", Tone.Neutral)
    StatusItem("cm", "8f3a912", Tone.Neutral)
    StatusItem("wr", "5 changes", Tone.Warning)
    StatusItem("x", "validation: 1 error / 2 warn", Tone.Error)
    StatusItem("bd", "install: not built", Tone.Neutral)
    StatusItem("vl", "contract v3.2", Tone.Neutral)
    Spacer(modifier = Modifier.weight(1f))
    StatusItem("tm", "publish blocked · resolve C-204 to unlock", Tone.Neutral)
    Text(text = "UTF-8", color = WorkspaceSteel, fontSize = 11.sp)
    Text(text = "YAML", color = WorkspaceSteel, fontSize = 11.sp)
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

private enum class DockTab(val label: String, val badge: String?, val tone: Tone, val width: Dp) {
  Validation("Validation", "5", Tone.Error, 118.dp),
  Changes("Changes", "5", Tone.Warning, 106.dp),
  History("History", "5", Tone.Neutral, 102.dp),
  Console("Install console", null, Tone.Neutral, 132.dp),
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
