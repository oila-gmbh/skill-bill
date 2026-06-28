@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.EditorPlaceholder

@Composable
internal fun CenterWorkspace(
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
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (tab.dirty) {
        Box(
          modifier = Modifier.size(
            6.dp,
          ).clip(SkillBillComponentShapes.pill).background(SkillBillTheme.frameTokens.primary),
        )
      }
      if (tab.readOnly) {
        Text(
          text = tab.readOnlyLabel ?: "RO",
          color = SkillBillTheme.frameTokens.subtle,
          style = SkillBillTypeStyles.caption.copy(fontFamily = FontFamily.Monospace),
        )
      }
      if (closeEnabled) {
        Text(
          text = "x",
          color = if (active) SkillBillTheme.frameTokens.muted else SkillBillTheme.frameTokens.subtle,
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          modifier = Modifier
            .size(18.dp)
            .clip(SkillBillComponentShapes.previewConsole)
            .clickable(role = Role.Button, onClick = onClosed)
            .semantics { contentDescription = "Close ${tab.title}" },
          maxLines = 1,
        )
      }
    }
  }
}
