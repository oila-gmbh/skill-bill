@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.SkillBillAcceleratorLabels
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillTreeItem

@Composable
internal fun NavigationPane(
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
internal fun NavigationPaneResizeHandle(onResize: (Dp) -> Unit) {
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
