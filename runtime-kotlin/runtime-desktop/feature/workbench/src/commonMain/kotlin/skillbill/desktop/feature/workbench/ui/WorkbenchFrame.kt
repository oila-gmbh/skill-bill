@file:Suppress("FunctionName")

package skillbill.desktop.feature.workbench.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.WorkbenchMetrics
import skillbill.desktop.core.designsystem.WorkbenchPalette
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.WorkbenchState
import skillbill.desktop.core.domain.model.WorkbenchTreeItem

@Composable
fun WorkbenchFrame(state: WorkbenchState, onRepoSelected: (String) -> Unit, onTreeItemSelected: (String) -> Unit) {
  Column(modifier = Modifier.fillMaxSize().background(WorkbenchPalette.canvas)) {
    TopRepoToolbar(state = state, onRepoSelected = onRepoSelected)
    Divider(color = WorkbenchPalette.rule)
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
      TreePane(
        items = state.treeItems,
        selectedTreeItemId = state.selectedTreeItemId,
        onTreeItemSelected = onTreeItemSelected,
      )
      Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = WorkbenchPalette.rule)
      EditorPane(editor = state.editor)
    }
    Divider(color = WorkbenchPalette.rule)
    SourceControlPane(sourceControl = state.sourceControl)
  }
}

@Composable
private fun TopRepoToolbar(state: WorkbenchState, onRepoSelected: (String) -> Unit) {
  var repoInput by remember(state.selectedRepoPath) { mutableStateOf(state.selectedRepoPath.orEmpty()) }

  Row(
    modifier = Modifier.fillMaxWidth().height(WorkbenchMetrics.toolbarHeight).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "Repository",
      style = MaterialTheme.typography.subtitle2,
      fontWeight = FontWeight.SemiBold,
      color = WorkbenchPalette.text,
    )
    Spacer(modifier = Modifier.width(12.dp))
    OutlinedTextField(
      value = repoInput,
      onValueChange = { repoInput = it },
      modifier = Modifier.weight(1f),
      singleLine = true,
      label = { Text("Repository path") },
      placeholder = { Text("Local Skill Bill checkout path") },
    )
    Spacer(modifier = Modifier.width(12.dp))
    Button(
      onClick = { repoInput.trim().takeIf(String::isNotEmpty)?.let(onRepoSelected) },
      enabled = repoInput.isNotBlank(),
    ) {
      Text("Open")
    }
  }
}

@Composable
private fun TreePane(
  items: List<WorkbenchTreeItem>,
  selectedTreeItemId: String?,
  onTreeItemSelected: (String) -> Unit,
) {
  Column(
    modifier = Modifier.width(WorkbenchMetrics.treePaneWidth).fillMaxHeight().background(WorkbenchPalette.sidebar)
      .verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
  ) {
    items.forEach { item ->
      TreeItemRow(
        item = item,
        depth = 0,
        selectedTreeItemId = selectedTreeItemId,
        onTreeItemSelected = onTreeItemSelected,
      )
    }
  }
}

@Composable
private fun TreeItemRow(
  item: WorkbenchTreeItem,
  depth: Int,
  selectedTreeItemId: String?,
  onTreeItemSelected: (String) -> Unit,
) {
  val isSelected = item.id == selectedTreeItemId
  val isSelectable = item.kind == TreeItemKind.PLACEHOLDER
  val background = if (isSelected) WorkbenchPalette.selection else Color.Transparent
  val textColor =
    if (isSelected) {
      WorkbenchPalette.selectionText
    } else if (item.kind == TreeItemKind.GROUP) {
      WorkbenchPalette.text
    } else {
      WorkbenchPalette.mutedText
    }
  val selectionModifier =
    if (isSelectable) {
      Modifier.semantics { selected = isSelected }
    } else {
      Modifier
    }
  val rowModifier =
    Modifier.fillMaxWidth().background(background).then(selectionModifier)
      .then(
        if (isSelectable) {
          Modifier.clickable(role = Role.Button) { onTreeItemSelected(item.id) }
        } else {
          Modifier
        },
      )
      .padding(start = 14.dp + WorkbenchMetrics.treeIndent * depth, top = 7.dp, end = 10.dp, bottom = 7.dp)

  Text(
    text = item.label,
    modifier = rowModifier,
    style = if (item.kind == TreeItemKind.GROUP) MaterialTheme.typography.subtitle2 else MaterialTheme.typography.body2,
    fontWeight = if (item.kind == TreeItemKind.GROUP) FontWeight.SemiBold else FontWeight.Normal,
    color = textColor,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
  item.children.forEach { child ->
    TreeItemRow(
      item = child,
      depth = depth + 1,
      selectedTreeItemId = selectedTreeItemId,
      onTreeItemSelected = onTreeItemSelected,
    )
  }
}

@Composable
private fun EditorPane(editor: EditorPlaceholder) {
  Column(modifier = Modifier.fillMaxSize().background(WorkbenchPalette.editor).padding(20.dp)) {
    Text(
      text = editor.title,
      style = MaterialTheme.typography.h6,
      color = WorkbenchPalette.text,
    )
    Spacer(modifier = Modifier.height(10.dp))
    Divider(color = WorkbenchPalette.rule)
    Box(modifier = Modifier.fillMaxSize().padding(top = 18.dp)) {
      Text(
        text = editor.detail,
        style = MaterialTheme.typography.body1,
        color = WorkbenchPalette.mutedText,
      )
    }
  }
}

@Composable
private fun SourceControlPane(sourceControl: SourceControlStatus) {
  Row(
    modifier = Modifier.fillMaxWidth().height(WorkbenchMetrics.statusPaneHeight).background(WorkbenchPalette.status)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = sourceControl.branchLabel,
        style = MaterialTheme.typography.subtitle2,
        fontWeight = FontWeight.SemiBold,
        color = WorkbenchPalette.text,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = sourceControl.summary,
        style = MaterialTheme.typography.body2,
        color = WorkbenchPalette.mutedText,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = "Source Control",
      style = MaterialTheme.typography.caption,
      color = WorkbenchPalette.accent,
      fontWeight = FontWeight.SemiBold,
    )
  }
}
