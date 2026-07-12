@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.accelerator_repo_open
import dev.skillbill.designsystem.generated.resources.nav_choose_repo_dir_cd
import dev.skillbill.designsystem.generated.resources.nav_contract_policy_label
import dev.skillbill.designsystem.generated.resources.nav_file_mode
import dev.skillbill.designsystem.generated.resources.nav_open_repo_tooltip
import dev.skillbill.designsystem.generated.resources.nav_repo_busy
import dev.skillbill.designsystem.generated.resources.nav_repo_open
import dev.skillbill.designsystem.generated.resources.nav_repository
import dev.skillbill.designsystem.generated.resources.work_section_empty
import dev.skillbill.designsystem.generated.resources.work_section_error
import dev.skillbill.designsystem.generated.resources.work_section_estimated
import dev.skillbill.designsystem.generated.resources.work_section_expanded
import dev.skillbill.designsystem.generated.resources.work_section_collapsed
import dev.skillbill.designsystem.generated.resources.work_section_loaded
import dev.skillbill.designsystem.generated.resources.work_section_loading
import dev.skillbill.designsystem.generated.resources.work_section_refresh
import dev.skillbill.designsystem.generated.resources.work_section_refresh_cd
import dev.skillbill.designsystem.generated.resources.work_section_subtitle
import dev.skillbill.designsystem.generated.resources.work_section_title
import dev.skillbill.designsystem.generated.resources.work_section_toggle_cd
import dev.skillbill.designsystem.generated.resources.work_section_unknown_issue
import dev.skillbill.designsystem.generated.resources.work_field_issue
import dev.skillbill.designsystem.generated.resources.work_field_issue_cd
import dev.skillbill.designsystem.generated.resources.work_field_kind
import dev.skillbill.designsystem.generated.resources.work_field_kind_cd
import dev.skillbill.designsystem.generated.resources.work_field_started
import dev.skillbill.designsystem.generated.resources.work_field_started_cd
import dev.skillbill.designsystem.generated.resources.work_field_state
import dev.skillbill.designsystem.generated.resources.work_field_state_cd
import dev.skillbill.designsystem.generated.resources.work_field_state_since
import dev.skillbill.designsystem.generated.resources.work_field_state_since_cd
import dev.skillbill.designsystem.generated.resources.work_field_state_since_estimated_cd
import dev.skillbill.designsystem.generated.resources.work_field_workflow
import dev.skillbill.designsystem.generated.resources.work_field_workflow_cd
import org.jetbrains.compose.resources.stringResource
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.WorkListLoadState
import skillbill.desktop.core.domain.model.WorkListState

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
  workList: WorkListState = WorkListState(),
  workEnabled: Boolean = true,
  onWorkToggled: () -> Unit = {},
  onWorkRefreshed: () -> Unit = {},
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
        .padding(vertical = SkillBillDimens.padMd),
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
      WorkSection(
        state = workList,
        enabled = workEnabled && !busy,
        onToggle = onWorkToggled,
        onRefresh = onWorkRefreshed,
      )
      HorizontalDivider(
        modifier = Modifier.padding(top = SkillBillDimens.spacingXl, bottom = SkillBillDimens.spacingLg),
        color = SkillBillTheme.frameTokens.line,
      )
      // F-X-901: File editability is a workspace-wide status indicator, not an action. Render it
      // as a labeled status row (no clickable, no Role.Button) mirroring StatusItem in the bottom
      // status bar, so accessibility semantics match real behavior.
      RepositoryStatusItem(
        label = stringResource(Res.string.nav_file_mode),
        statusText = readOnlyModeLabel,
        marker = fileModeMarker(readOnlyModeLabel),
        enabled = !busy,
      )
    }
    Row(
      modifier =
      Modifier
        .fillMaxWidth()
        .height(SkillBillDimens.navPaneHeaderHeight)
        .border(BorderStroke(SkillBillDimens.borderNone, SkillBillTheme.frameTokens.transparent))
        .background(SkillBillTheme.frameTokens.sidebar)
        .padding(horizontal = SkillBillDimens.pad2xl),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
    ) {
      MiniIcon(text = "lk", tint = SkillBillTheme.frameTokens.subtle)
      Text(
        text = stringResource(Res.string.nav_contract_policy_label),
        color = SkillBillTheme.frameTokens.subtle,
        style = MaterialTheme.typography.labelSmall,
      )
      Text(text = policyLabel, color = SkillBillTheme.frameTokens.text, style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun WorkSection(
  state: WorkListState,
  enabled: Boolean,
  onToggle: () -> Unit,
  onRefresh: () -> Unit,
) {
  val expanded = state.expanded
  val expandedDescription = stringResource(
    if (expanded) Res.string.work_section_expanded else Res.string.work_section_collapsed,
  )
  val toggleDescription = stringResource(Res.string.work_section_toggle_cd)
  val refreshDescription = stringResource(Res.string.work_section_refresh_cd)
  Column(modifier = Modifier.fillMaxWidth().padding(top = SkillBillDimens.spacingLg)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .testTag(WORK_SECTION_TOGGLE_TAG)
        .semantics {
          contentDescription = toggleDescription
          stateDescription = expandedDescription
        }
        .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
        .padding(horizontal = SkillBillDimens.padLg, vertical = SkillBillDimens.padMd),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(stringResource(Res.string.work_section_title), style = MaterialTheme.typography.labelLarge, color = SkillBillTheme.frameTokens.text)
      Text(if (expanded) "−" else "+", color = SkillBillTheme.frameTokens.primary)
    }
    if (!expanded) return
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = SkillBillDimens.padLg),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(stringResource(Res.string.work_section_subtitle), style = MaterialTheme.typography.labelSmall, color = SkillBillTheme.frameTokens.subtle)
      Text(
        stringResource(Res.string.work_section_refresh),
        modifier = Modifier
          .testTag(WORK_SECTION_REFRESH_TAG)
          .semantics { contentDescription = refreshDescription }
          .clickable(enabled = enabled && state.loadState != WorkListLoadState.LOADING, role = Role.Button) {
            onRefresh()
          }
          .padding(SkillBillDimens.padSm),
        color = if (enabled) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.subtle,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    when (state.loadState) {
      WorkListLoadState.LOADING -> WorkText(stringResource(Res.string.work_section_loading))
      WorkListLoadState.EMPTY -> WorkText(stringResource(Res.string.work_section_empty))
      WorkListLoadState.ERROR -> WorkText(state.errorMessage ?: stringResource(Res.string.work_section_error))
      WorkListLoadState.POPULATED -> WorkRows(state)
      WorkListLoadState.COLLAPSED -> Unit
    }
  }
}

@Composable
private fun WorkText(text: String) {
  Text(
    text = text,
    modifier = Modifier
      .testTag(WORK_SECTION_STATUS_TAG)
      .semantics { liveRegion = LiveRegionMode.Polite }
      .padding(horizontal = SkillBillDimens.padLg, vertical = SkillBillDimens.padMd),
    color = SkillBillTheme.frameTokens.subtle,
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun WorkRows(state: WorkListState) {
  val horizontal = rememberScrollState()
  val fields = workFields()
  WorkText(stringResource(Res.string.work_section_loaded, state.items.size))
  Box(
    modifier = Modifier
      .height(SkillBillDimens.navPaneHeaderHeight * 3)
      .horizontalScroll(horizontal)
      .testTag(WORK_SECTION_VIEWPORT_TAG),
  ) {
    LazyColumn(
      modifier = Modifier
        .width(WORK_SECTION_CONTENT_WIDTH)
        .fillMaxHeight()
        .padding(horizontal = SkillBillDimens.padLg, vertical = SkillBillDimens.padSm),
    ) {
      item(key = "work-field-headers") {
        WorkFieldRow(
          fields = fields,
          values = fields.map(WorkField::label),
          modifier = Modifier.testTag(WORK_SECTION_HEADERS_TAG),
        )
      }
      items(state.items, key = { item -> "${item.workflowKind}:${item.workflowId}" }) { item ->
        WorkItemRow(item, fields)
      }
    }
  }
}

@Composable
private fun WorkItemRow(item: skillbill.desktop.core.domain.model.DesktopWorkItem, fields: List<WorkField>) {
  val issue = item.issueKey ?: stringResource(Res.string.work_section_unknown_issue)
  val since = item.stateEnteredAt + if (item.stateEnteredAtEstimated) {
    " ~ ${stringResource(Res.string.work_section_estimated)}"
  } else {
    ""
  }
  val descriptions = listOf(
    stringResource(Res.string.work_field_issue_cd, issue),
    stringResource(Res.string.work_field_kind_cd, item.workflowKind),
    stringResource(Res.string.work_field_workflow_cd, item.workflowId),
    stringResource(Res.string.work_field_started_cd, item.startedAt),
    stringResource(Res.string.work_field_state_cd, item.currentState),
    stringResource(
      if (item.stateEnteredAtEstimated) {
        Res.string.work_field_state_since_estimated_cd
      } else {
        Res.string.work_field_state_since_cd
      },
      item.stateEnteredAt,
    ),
  )
  WorkFieldRow(
    fields = fields,
    values = listOf(issue, item.workflowKind, item.workflowId, item.startedAt, item.currentState, since),
    modifier = Modifier
      .testTag("$WORK_SECTION_ROW_TAG-${item.workflowId}")
      .semantics(mergeDescendants = true) { contentDescription = descriptions.joinToString(". ") },
  )
}

@Composable
private fun WorkFieldRow(fields: List<WorkField>, values: List<String>, modifier: Modifier = Modifier) {
  Row(modifier = modifier.padding(vertical = SkillBillDimens.padSm)) {
    fields.zip(values).forEach { (field, value) ->
      Text(
        text = value,
        modifier = Modifier.width(field.width),
        color = SkillBillTheme.frameTokens.text,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun workFields(): List<WorkField> = listOf(
  WorkField(stringResource(Res.string.work_field_issue), 112.dp),
  WorkField(stringResource(Res.string.work_field_kind), 156.dp),
  WorkField(stringResource(Res.string.work_field_workflow), 248.dp),
  WorkField(stringResource(Res.string.work_field_started), 188.dp),
  WorkField(stringResource(Res.string.work_field_state), 112.dp),
  WorkField(stringResource(Res.string.work_field_state_since), 196.dp),
)

private data class WorkField(val label: String, val width: Dp)

private val WORK_SECTION_CONTENT_WIDTH = 1_012.dp
private const val WORK_SECTION_TOGGLE_TAG = "work-section-toggle"
private const val WORK_SECTION_REFRESH_TAG = "work-section-refresh"
private const val WORK_SECTION_STATUS_TAG = "work-section-status"
private const val WORK_SECTION_VIEWPORT_TAG = "work-section-list-viewport"
private const val WORK_SECTION_HEADERS_TAG = "work-section-field-headers"
private const val WORK_SECTION_ROW_TAG = "work-section-row"

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
      .border(BorderStroke(SkillBillDimens.borderNone, SkillBillTheme.frameTokens.transparent))
      .padding(horizontal = SkillBillDimens.pad2xl, vertical = SkillBillDimens.spacingXl),
  ) {
    LabelText(stringResource(Res.string.nav_repository))
    Row(
      modifier =
      Modifier
        .fillMaxWidth()
        .height(SkillBillDimens.listItemHeight)
        .border(
          SkillBillDimens.hairline,
          when {
            busy -> textFieldTokens.disabledBorder
            repoPathFocused -> textFieldTokens.focusedBorder
            else -> textFieldTokens.border
          },
          SkillBillComponentShapes.control,
        )
        .background(
          if (busy) textFieldTokens.disabledContainer else textFieldTokens.container,
          SkillBillComponentShapes.control,
        )
        .padding(horizontal = SkillBillDimens.padLg),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
    ) {
      MiniIcon(text = "db", tint = SkillBillTheme.frameTokens.primary)
      BasicTextField(
        value = repoPath,
        onValueChange = onRepoPathChanged,
        enabled = !busy,
        textStyle = MaterialTheme.typography.bodySmall.copy(
          fontFamily = FontFamily.Monospace,
          color = if (busy) textFieldTokens.disabledText else textFieldTokens.text,
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
      AcceleratorTooltip(
        label = stringResource(Res.string.nav_open_repo_tooltip),
        acceleratorLabel = stringResource(Res.string.accelerator_repo_open),
      ) {
        Text(
          text = if (busy) stringResource(Res.string.nav_repo_busy) else stringResource(Res.string.nav_repo_open),
          color = if (busy) SkillBillTheme.frameTokens.subtle else SkillBillTheme.frameTokens.primary,
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier
            .iconButtonSemantics(description = stringResource(Res.string.nav_open_repo_tooltip))
            .clickable(enabled = !busy, role = Role.Button) { onRepoSelected(repoPath) }
            .padding(horizontal = SkillBillDimens.padMd, vertical = SkillBillDimens.padSm),
        )
      }
      Text(
        text = "...",
        color = if (busy) SkillBillTheme.frameTokens.subtle else SkillBillTheme.frameTokens.primary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
          .iconButtonSemantics(description = stringResource(Res.string.nav_choose_repo_dir_cd))
          .clickable(enabled = !busy, role = Role.Button, onClick = onChooseRepoDirectory)
          .padding(horizontal = SkillBillDimens.padMd, vertical = SkillBillDimens.padSm),
      )
    }
    Text(
      text = repoStatus.message,
      color = if (repoStatus.state == RepoLoadState.INVALID) {
        SkillBillTheme.frameTokens.status.error
      } else {
        SkillBillTheme.frameTokens.subtle
      },
      style = SkillBillTypeStyles.caption,
      modifier = Modifier.padding(top = SkillBillDimens.padMd),
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
    style = MaterialTheme.typography.bodySmall,
    modifier = Modifier.padding(SkillBillDimens.pad2xl),
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
        .width(SkillBillDimens.spacingXs)
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
