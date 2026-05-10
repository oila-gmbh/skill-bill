@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.model.TreeItemKind

@Composable
fun SkillBillFrame(
  state: SkillBillState,
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onRepoSelected: (String) -> Unit,
  onTreeItemSelected: (String) -> Unit,
) {
  val colors = SkillBillTheme.extendedColors
  Column(modifier = Modifier.fillMaxSize().background(SkillBillTheme.colors.background)) {
    TopRepoToolbar(
      state = state,
      canNavigateBack = canNavigateBack,
      onNavigateBack = onNavigateBack,
      onRepoSelected = onRepoSelected,
    )
    HorizontalDivider(color = colors.separator)
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
      TreePane(
        items = state.treeItems,
        selectedTreeItemId = state.selectedTreeItemId,
        onTreeItemSelected = onTreeItemSelected,
      )
      VerticalDivider(modifier = Modifier.fillMaxHeight(), color = colors.separator)
      EditorPane(editor = state.editor)
    }
    HorizontalDivider(color = colors.separator)
    SourceControlPane(sourceControl = state.sourceControl)
  }
}

@Composable
private fun TopRepoToolbar(
  state: SkillBillState,
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onRepoSelected: (String) -> Unit,
) {
  var repoInput by remember(state.selectedRepoPath) { mutableStateOf(state.selectedRepoPath.orEmpty()) }

  Row(
    modifier = Modifier.fillMaxWidth().height(SkillBillMetrics.toolbarHeight).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (canNavigateBack) {
      Button(onClick = onNavigateBack) {
        Text(SkillBillUiText.BACK)
      }
      Spacer(modifier = Modifier.width(12.dp))
    }
    Text(
      text = SkillBillUiText.REPOSITORY,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      color = SkillBillTheme.colors.onSurface,
    )
    Spacer(modifier = Modifier.width(12.dp))
    OutlinedTextField(
      value = repoInput,
      onValueChange = { repoInput = it },
      modifier = Modifier.weight(1f),
      singleLine = true,
      colors = SkillBillTheme.outlinedTextFieldColors,
      label = { Text(SkillBillUiText.REPOSITORY_PATH) },
      placeholder = { Text(SkillBillUiText.LOCAL_CHECKOUT_PATH) },
    )
    Spacer(modifier = Modifier.width(12.dp))
    Button(
      onClick = { repoInput.trim().takeIf(String::isNotEmpty)?.let(onRepoSelected) },
      enabled = repoInput.isNotBlank(),
    ) {
      Text(SkillBillUiText.OPEN)
    }
  }
}

@Composable
private fun TreePane(
  items: List<SkillBillTreeItem>,
  selectedTreeItemId: String?,
  onTreeItemSelected: (String) -> Unit,
) {
  val treeRows = remember(items) { items.flattenTreeRows() }
  LazyColumn(
    modifier = Modifier.width(
      SkillBillMetrics.treePaneWidth,
    ).fillMaxHeight().background(SkillBillTheme.colors.surfaceVariant)
      .padding(vertical = 10.dp),
    contentPadding = PaddingValues(vertical = 0.dp),
  ) {
    items(
      items = treeRows,
      key = { row -> row.item.id },
      contentType = { row -> row.item.kind },
    ) { row ->
      TreeItemRow(
        item = row.item,
        depth = row.depth,
        selectedTreeItemId = selectedTreeItemId,
        onTreeItemSelected = onTreeItemSelected,
      )
    }
  }
}

@Composable
private fun TreeItemRow(
  item: SkillBillTreeItem,
  depth: Int,
  selectedTreeItemId: String?,
  onTreeItemSelected: (String) -> Unit,
) {
  val colors = SkillBillTheme.extendedColors
  val isSelected = item.id == selectedTreeItemId
  val isSelectable = item.kind == TreeItemKind.PLACEHOLDER
  val background = if (isSelected) SkillBillTheme.colors.primary else Color.Transparent
  val rowTextStyle: TextStyle
  val rowTextWeight: FontWeight
  if (item.kind == TreeItemKind.GROUP) {
    rowTextStyle = MaterialTheme.typography.titleSmall
    rowTextWeight = FontWeight.SemiBold
  } else {
    rowTextStyle = MaterialTheme.typography.bodyMedium
    rowTextWeight = FontWeight.Normal
  }
  val textColor =
    if (isSelected) {
      SkillBillTheme.colors.onPrimary
    } else if (item.kind == TreeItemKind.GROUP) {
      SkillBillTheme.colors.onSurface
    } else {
      colors.onSurface
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
      .padding(start = 14.dp + SkillBillMetrics.treeIndent * depth, top = 7.dp, end = 10.dp, bottom = 7.dp)

  Text(
    text = item.label,
    modifier = rowModifier,
    style = rowTextStyle,
    fontWeight = rowTextWeight,
    color = textColor,
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun EditorPane(editor: EditorPlaceholder) {
  val colors = SkillBillTheme.extendedColors
  Column(modifier = Modifier.fillMaxSize().background(SkillBillTheme.colors.surface).padding(20.dp)) {
    Text(
      text = editor.title,
      style = MaterialTheme.typography.titleLarge,
      color = SkillBillTheme.colors.onSurface,
    )
    Spacer(modifier = Modifier.height(10.dp))
    HorizontalDivider(color = colors.separator)
    Box(modifier = Modifier.fillMaxSize().padding(top = 18.dp)) {
      Text(
        text = editor.detail,
        style = MaterialTheme.typography.bodyLarge,
        color = SkillBillTheme.colors.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SourceControlPane(sourceControl: SourceControlStatus) {
  val colors = SkillBillTheme.extendedColors
  Row(
    modifier = Modifier.fillMaxWidth().height(
      SkillBillMetrics.statusPaneHeight,
    ).background(SkillBillTheme.colors.surfaceVariant)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = sourceControl.branchLabel,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = SkillBillTheme.colors.onSurface,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = sourceControl.summary,
        style = MaterialTheme.typography.bodyMedium,
        color = SkillBillTheme.colors.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = SkillBillUiText.SOURCE_CONTROL,
      style = MaterialTheme.typography.labelSmall,
      color = colors.warning,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

private data class TreeRow(val item: SkillBillTreeItem, val depth: Int)

private fun List<SkillBillTreeItem>.flattenTreeRows(depth: Int = 0): List<TreeRow> =
  flatMap { item -> listOf(TreeRow(item, depth)) + item.children.flattenTreeRows(depth + 1) }

private object SkillBillUiText {
  const val BACK = "Back"
  const val REPOSITORY = "Repository"
  const val REPOSITORY_PATH = "Repository path"
  const val LOCAL_CHECKOUT_PATH = "Local Skill Bill checkout path"
  const val OPEN = "Open"
  const val SOURCE_CONTROL = "Source Control"
}
