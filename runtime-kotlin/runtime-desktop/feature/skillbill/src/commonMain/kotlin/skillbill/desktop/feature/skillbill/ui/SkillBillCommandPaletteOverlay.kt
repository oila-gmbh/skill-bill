@file:Suppress("FunctionName")

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.command_palette_kind_command
import dev.skillbill.designsystem.generated.resources.command_palette_kind_source
import dev.skillbill.designsystem.generated.resources.command_palette_no_matches
import dev.skillbill.designsystem.generated.resources.command_palette_search_placeholder
import org.jetbrains.compose.resources.stringResource
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
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
        .padding(top = SkillBillDimens.commandPaletteTopOffset)
        .widthIn(min = SkillBillDimens.commandPaletteMinWidth, max = SkillBillDimens.commandPaletteMaxWidth)
        .heightIn(max = SkillBillDimens.commandPaletteMaxHeight)
        .clip(SkillBillTheme.shapes.medium)
        .border(SkillBillDimens.hairline, SkillBillTheme.frameTokens.line, SkillBillTheme.shapes.medium)
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
        modifier = Modifier.fillMaxWidth().heightIn(
          max = SkillBillDimens.commandPaletteListHeight,
        ).verticalScroll(rememberScrollState()),
      )
    }
  }
}

@Composable
private fun CommandPaletteInput(query: String, onQueryChanged: (String) -> Unit, focusRequester: FocusRequester) {
  val textFieldTokens = SkillBillTheme.textFieldTokens
  Row(
    modifier = Modifier.fillMaxWidth().height(
      SkillBillDimens.commandRowHeight,
    ).padding(horizontal = SkillBillDimens.pad3xl),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
  ) {
    MiniIcon(text = "cmd", tint = SkillBillTheme.frameTokens.primary)
    Box(modifier = Modifier.weight(1f)) {
      if (query.isBlank()) {
        Text(
          text = stringResource(Res.string.command_palette_search_placeholder),
          color = textFieldTokens.placeholder,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      BasicTextField(
        value = query,
        onValueChange = onQueryChanged,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleSmall.copy(
          fontFamily = FontFamily.Monospace,
          color = textFieldTokens.text,
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
  Column(modifier = modifier.padding(vertical = SkillBillDimens.padMd)) {
    if (palette.results.isEmpty()) {
      Text(
        text = stringResource(Res.string.command_palette_no_matches),
        color = SkillBillTheme.frameTokens.subtle,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = SkillBillDimens.pad4xl, vertical = SkillBillDimens.pad2xl),
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
      .heightIn(min = SkillBillDimens.commandRowHeight)
      .padding(horizontal = SkillBillDimens.padLg, vertical = SkillBillDimens.padXs)
      .clip(SkillBillComponentShapes.badge)
      .background(background)
      .semantics {
        this.selected = selected
        this.role = Role.Button
        if (!enabled) {
          disabled()
        }
      }
      .clickable(enabled = enabled, role = Role.Button, onClick = onExecute)
      .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.space7),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
  ) {
    val markerTint = if (selected && enabled) {
      SkillBillTheme.frameTokens.primary
    } else {
      SkillBillTheme.frameTokens.subtle
    }
    MiniIcon(text = result.marker, tint = markerTint)
    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
      ) {
        val titleText = result.titleRes?.let { stringResource(it) } ?: result.title
        Text(
          text = titleText,
          color = titleColor,
          style = SkillBillTypeStyles.body13,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        result.acceleratorLabelRes?.let { res ->
          CommandPaletteAcceleratorLabel(stringResource(res))
        }
        CommandPaletteKindLabel(result.kind)
      }
      val subtitleText = result.subtitleRes?.let { stringResource(it) } ?: result.subtitle
      val disabledText = result.disabledReasonRes?.let { stringResource(it) }
      Text(
        text = disabledText ?: subtitleText,
        color = if (result.disabledReasonRes == null) subtitleColor else SkillBillTheme.frameTokens.status.warning,
        style = MaterialTheme.typography.labelSmall,
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
    style = SkillBillTypeStyles.caption.copy(fontFamily = FontFamily.Monospace),
    maxLines = 1,
  )
}

@Composable
private fun CommandPaletteKindLabel(kind: CommandPaletteResultKind) {
  val kindLabel = when (kind) {
    CommandPaletteResultKind.COMMAND -> stringResource(Res.string.command_palette_kind_command)
    CommandPaletteResultKind.TREE_ITEM -> stringResource(Res.string.command_palette_kind_source)
  }
  Text(
    text = kindLabel,
    color = SkillBillTheme.frameTokens.subtle,
    style = SkillBillTypeStyles.caption.copy(fontFamily = FontFamily.Monospace),
    maxLines = 1,
  )
}
