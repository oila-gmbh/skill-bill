@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.domain.model.SkillBillTreeItem

@Composable
internal fun NavGroup(
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
internal fun RepositoryStatusItem(label: String, statusText: String, marker: String, enabled: Boolean = true) {
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
