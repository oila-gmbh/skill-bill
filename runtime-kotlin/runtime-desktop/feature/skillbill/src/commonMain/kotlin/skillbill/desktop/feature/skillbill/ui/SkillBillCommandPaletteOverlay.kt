@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.CommandPaletteResultKind
import skillbill.desktop.core.domain.model.CommandPaletteState

@Composable
internal fun CommandPaletteOverlay(
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
