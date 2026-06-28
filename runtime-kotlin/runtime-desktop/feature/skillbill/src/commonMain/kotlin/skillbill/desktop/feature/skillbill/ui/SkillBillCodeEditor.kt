@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.domain.model.DirtyEditorPrompt
import skillbill.desktop.core.domain.model.DirtyEditorPromptReason
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.SkillBillAcceleratorLabels

@Composable
internal fun CodeEditor(
  editor: EditorPlaceholder,
  dirtyEditorPrompt: DirtyEditorPrompt?,
  editorInputEnabled: Boolean,
  onDraftChanged: (String) -> Unit,
  onSave: () -> Unit,
  onRevert: () -> Unit,
  onDirtyPromptDiscard: () -> Unit,
  onDirtyPromptCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var dismissedSaveErrorDialogKey by remember { mutableStateOf<String?>(null) }
  val codePaneColors = codePaneColors()
  Column(
    modifier =
    modifier
      .fillMaxWidth()
      .background(SkillBillTheme.frameTokens.background),
  ) {
    EditorCommandBar(editor = editor, onSave = onSave, onRevert = onRevert)
    if (dirtyEditorPrompt != null) {
      DirtyEditorPromptBanner(
        prompt = dirtyEditorPrompt,
        onDiscard = onDirtyPromptDiscard,
        onCancel = onDirtyPromptCancel,
      )
    }
    editor.saveErrorMessage?.let { message ->
      SaveErrorBanner(message)
      val dialogKey = "${editor.draftContent.hashCode()}:$message"
      if (dismissedSaveErrorDialogKey != dialogKey) {
        SaveErrorDialog(
          message = message,
          onDismiss = {
            dismissedSaveErrorDialogKey = dialogKey
          },
        )
      }
    }
    if (editor.editable) {
      val editorInputActive = editorInputEnabled && !editor.saveInProgress
      Box(
        modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .background(codePaneColors.background)
          .verticalScroll(rememberScrollState()),
      ) {
        BasicTextField(
          value = editor.draftContent ?: editor.content.orEmpty(),
          onValueChange = onDraftChanged,
          enabled = editorInputActive,
          textStyle = SkillBillTypeStyles.code.copy(
            lineHeight = 20.sp,
            color = if (editorInputActive) codePaneColors.editorText else codePaneColors.editorDisabledText,
          ),
          cursorBrush = SolidColor(codePaneColors.editorCursor),
          modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = SkillBillDimens.pad4xl, vertical = SkillBillDimens.pad2xl),
        )
      }
    } else {
      val rawText = (editor.content ?: editor.detail).ifBlank { "No source selected" }
      val lines = rawText.lines()
      ReadOnlyBanner(editor)
      Column(
        modifier =
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .background(codePaneColors.background)
          .verticalScroll(rememberScrollState()),
      ) {
        lines.forEachIndexed { index, line ->
          CodeLine(number = index + 1, line = line, flagged = false, colors = codePaneColors)
        }
      }
    }
  }
}

@Composable
private fun SaveErrorDialog(message: String, onDismiss: () -> Unit) {
  val dialogTone = SkillBillTheme.semanticTones.dialog
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(text = "Save blocked", color = dialogTone.content)
    },
    text = {
      Text(text = message, color = dialogTone.content)
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = "OK")
      }
    },
    containerColor = dialogTone.container,
    titleContentColor = dialogTone.content,
    textContentColor = dialogTone.content,
  )
}

@Composable
private fun EditorCommandBar(editor: EditorPlaceholder, onSave: () -> Unit, onRevert: () -> Unit) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(SkillBillMetrics.editorCommandBarHeight)
      .background(SkillBillTheme.frameTokens.raised)
      .padding(horizontal = SkillBillDimens.pad2xl),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    Text(
      text = if (editor.dirty) {
        "Modified"
      } else if (editor.editable) {
        "Saved"
      } else {
        "Read-only"
      },
      color = if (editor.dirty) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.muted,
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    EditorActionButton(
      label = if (editor.saveInProgress) "Saving..." else "Save",
      marker = "sv",
      enabled = editor.editable && editor.dirty && !editor.saveInProgress,
      primary = editor.dirty,
      acceleratorLabel = SkillBillAcceleratorLabels.SAVE,
      onClick = onSave,
    )
    EditorActionButton(
      label = "Revert",
      marker = "rv",
      enabled = editor.editable && editor.dirty && !editor.saveInProgress,
      onClick = onRevert,
    )
  }
}

@Composable
private fun EditorActionButton(
  label: String,
  marker: String,
  enabled: Boolean,
  primary: Boolean = false,
  acceleratorLabel: String? = null,
  onClick: () -> Unit,
) {
  val background = if (primary && enabled) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.panel
  val foreground =
    when {
      !enabled -> SkillBillTheme.frameTokens.subtle
      primary -> workspacePrimaryControlForeground()
      else -> SkillBillTheme.frameTokens.text
    }
  AcceleratorTooltip(label = label, acceleratorLabel = acceleratorLabel) {
    Row(
      modifier =
      Modifier
        .height(SkillBillDimens.controlHeightSm)
        .clip(SkillBillComponentShapes.control)
        .border(
          SkillBillDimens.hairline,
          if (enabled) SkillBillTheme.frameTokens.line else SkillBillTheme.frameTokens.panel,
          SkillBillComponentShapes.control,
        )
        .background(background, SkillBillComponentShapes.control)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = SkillBillDimens.space9),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
    ) {
      MiniIcon(text = marker, tint = foreground)
      Text(
        text = label,
        color = foreground,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ReadOnlyBanner(editor: EditorPlaceholder) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(SkillBillTheme.frameTokens.raised)
      .padding(horizontal = SkillBillDimens.pad3xl, vertical = SkillBillDimens.padLg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    MiniIcon(text = "ro", tint = SkillBillTheme.frameTokens.primary)
    Text(
      text = if (editor.kind == "generated artifact") {
        editor.readOnlyReason ?: "Generated artifact is ${editor.readOnlyLabel ?: "read-only"}"
      } else {
        editor.readOnlyReason ?: "Read-only browser"
      },
      color = SkillBillTheme.frameTokens.muted,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun SaveErrorBanner(message: String) {
  val errorTone = SkillBillTheme.semanticTones.errorBanner
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .heightIn(max = SkillBillDimens.codeCompletionMaxHeight)
      .background(errorTone.container)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = SkillBillDimens.pad3xl, vertical = SkillBillDimens.padLg),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    MiniIcon(text = "x", tint = errorTone.content)
    Text(
      text = message,
      color = errorTone.content,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun DirtyEditorPromptBanner(prompt: DirtyEditorPrompt, onDiscard: () -> Unit, onCancel: () -> Unit) {
  val warningTone = SkillBillTheme.semanticTones.warningBanner
  val message = when (prompt.reason) {
    DirtyEditorPromptReason.SELECTION_CHANGE -> "Discard unsaved edits before changing selection?"
    DirtyEditorPromptReason.REFRESH -> "Discard unsaved edits before refreshing?"
    DirtyEditorPromptReason.REPO_SWITCH -> "Discard unsaved edits before switching repositories?"
    DirtyEditorPromptReason.CHOOSE_DIRECTORY -> "Discard unsaved edits before choosing another repository?"
    DirtyEditorPromptReason.RETURN_TO_INSTALLED_WORKSPACE ->
      "Discard unsaved edits before opening the installed workspace?"
  }
  Row(
    modifier = Modifier.fillMaxWidth().background(
      warningTone.container,
    ).padding(horizontal = SkillBillDimens.pad3xl, vertical = SkillBillDimens.padLg),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    MiniIcon(text = "!", tint = warningTone.content)
    Text(
      text = message,
      color = warningTone.content,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    EditorActionButton(label = "Discard", marker = "ds", enabled = true, primary = true, onClick = onDiscard)
    EditorActionButton(label = "Cancel", marker = "cn", enabled = true, onClick = onCancel)
  }
}

@Composable
private fun CodeLine(number: Int, line: String, flagged: Boolean, colors: CodePaneColors) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .background(if (flagged) colors.flaggedBackground else SkillBillTheme.frameTokens.transparent),
  ) {
    Text(
      text = number.toString(),
      color = colors.lineNumber,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      modifier =
      Modifier
        .width(SkillBillDimens.lineNumberWidth)
        .border(BorderStroke(SkillBillDimens.borderNone, SkillBillTheme.frameTokens.transparent))
        .padding(top = SkillBillDimens.padSm, end = SkillBillDimens.padXl),
      maxLines = 1,
    )
    Row(
      modifier = Modifier.padding(
        start = SkillBillDimens.pad2xl,
        top = SkillBillDimens.padSm,
        bottom = SkillBillDimens.space3,
        end = SkillBillDimens.pad4xl,
      ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SyntaxText(line = line, colors = colors)
      if (flagged) {
        Row(
          modifier = Modifier.padding(start = SkillBillDimens.pad2xl),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm),
        ) {
          MiniIcon(text = "x", tint = SkillBillTheme.frameTokens.status.error)
          Text(
            text = "contract: missing field",
            color = SkillBillTheme.frameTokens.status.error,
            style = SkillBillTypeStyles.codeCaption,
          )
        }
      }
    }
  }
}

@Composable
private fun SyntaxText(line: String, colors: CodePaneColors) {
  val keyMatch = Regex("^(\\s*)([A-Za-z0-9_-]+):(.*)$").matchEntire(line)
  if (line.trimStart().startsWith("#")) {
    Text(
      text = line,
      color = colors.yaml.comment,
      style = SkillBillTypeStyles.code.copy(lineHeight = 20.sp),
      maxLines = 1,
    )
  } else if (keyMatch != null) {
    Row {
      Text(keyMatch.groupValues[1], color = colors.yaml.scalar, style = SkillBillTypeStyles.code)
      Text(keyMatch.groupValues[2], color = colors.yaml.key, style = SkillBillTypeStyles.code)
      Text(":", color = colors.yaml.marker, style = SkillBillTypeStyles.code)
      Text(
        keyMatch.groupValues[3],
        color = colors.yaml.scalar,
        style = SkillBillTypeStyles.code,
      )
    }
  } else {
    Text(
      text = line,
      color = colors.yaml.scalar,
      style = SkillBillTypeStyles.code.copy(lineHeight = 20.sp),
      maxLines = 1,
    )
  }
}
