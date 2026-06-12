@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.contentColorFor
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.GeneratedArtifactDetail
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.designsystem.SkillBillStatusTone as Tone

@Composable
internal fun InspectorPane(
  editor: EditorPlaceholder,
  repoStatus: RepoLoadStatus,
  onGeneratedArtifactResolvable: (String) -> Boolean,
  onGeneratedArtifactSelected: (String) -> Unit,
) {
  Column(
    modifier =
    Modifier
      .width(SkillBillMetrics.inspectorPaneWidth)
      .fillMaxHeight()
      .background(SkillBillTheme.frameTokens.background)
      .border(BorderStroke(0.dp, SkillBillTheme.frameTokens.transparent)),
  ) {
    InspectorHeader(editor = editor)
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      InspectorSection(title = "Metadata", marker = "mt") {
        KeyValueRow("name", editor.skillName ?: editor.title)
        KeyValueRow("kind", editor.kind ?: "none")
        KeyValueRow("authored path", editor.authoredPath ?: "-")
        KeyValueRow("status", editor.status ?: "-", tone = toneForStatus(editor.status))
        KeyValueRow("mode", editor.readOnlyLabel ?: if (editor.editable) "editable" else "read-only")
        KeyValueRow(
          "draft",
          if (editor.dirty) "dirty" else "clean",
          tone = if (editor.dirty) Tone.Warning else Tone.Success,
        )
        KeyValueRow(
          "editable",
          if (editor.editable) "yes" else "no",
          tone = if (editor.editable) Tone.Success else Tone.Warning,
        )
      }
      InspectorSection(
        title = "Repository validation",
        marker = "vl",
        badge = repoStatus.issueCount.takeIf { it > 0 }?.toString(),
      ) {
        KeyValueRow("state", repoStatus.state.name.lowercase())
        KeyValueRow("skills", repoStatus.skillCount.toString())
        KeyValueRow("platform packs", repoStatus.platformPackCount.toString())
        KeyValueRow("add-ons", repoStatus.addonCount.toString())
        KeyValueRow("native agents", repoStatus.nativeAgentCount.toString())
      }
      val artifactsForInspector: List<GeneratedArtifactDetail> = editor.generatedArtifacts
      InspectorSection(
        title = "Generated artifacts",
        marker = "gn",
        badge = artifactsForInspector.size.takeIf { it > 0 }?.toString(),
      ) {
        if (artifactsForInspector.isEmpty()) {
          KeyValueRow("visible", "none")
        } else {
          artifactsForInspector.forEach { artifact ->
            GeneratedArtifactRow(
              artifact = artifact,
              enabled = onGeneratedArtifactResolvable(artifact.path),
              onGeneratedArtifactSelected = onGeneratedArtifactSelected,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun InspectorHeader(editor: EditorPlaceholder) {
  Column(modifier = Modifier.fillMaxWidth().background(SkillBillTheme.frameTokens.panel).padding(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MiniIcon(text = "sk", tint = SkillBillTheme.frameTokens.primary)
      Text(
        text = editor.skillName ?: editor.title,
        color = SkillBillTheme.frameTokens.text,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Badge(text = if (editor.editable) "EDIT" else "RO", tone = if (editor.editable) Tone.Success else Tone.Warning)
    }
    Text(
      text = editor.kind ?: editor.detail,
      color = SkillBillTheme.frameTokens.muted,
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
      modifier = Modifier
        .fillMaxWidth()
        .height(32.dp)
        .background(SkillBillTheme.frameTokens.panel)
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MiniIcon(text = marker, tint = SkillBillTheme.frameTokens.primary)
      Text(
        text = title,
        color = SkillBillTheme.frameTokens.text,
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
    HorizontalDivider(color = SkillBillTheme.frameTokens.line)
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
      color = SkillBillTheme.frameTokens.status.contentColorFor(tone),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun GeneratedArtifactRow(
  artifact: GeneratedArtifactDetail,
  enabled: Boolean,
  onGeneratedArtifactSelected: (String) -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  val rowBackground =
    if (enabled && hovered) {
      SkillBillTheme.frameTokens.raised.copy(alpha = 0.65f)
    } else {
      SkillBillTheme.frameTokens.transparent
    }
  val labelAlpha = if (enabled) 1f else 0.55f
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(rowBackground)
      .iconButtonSemantics(description = generatedArtifactRowContentDescription(artifact))
      .semantics(mergeDescendants = true) {
        if (!enabled) disabled()
      }
      .onPreviewKeyEvent { event ->
        if (enabled && event.type == KeyEventType.KeyDown && event.isActivationKey()) {
          onGeneratedArtifactSelected(artifact.path)
          true
        } else {
          false
        }
      }
      .hoverable(interactionSource = interactionSource, enabled = enabled)
      .clickable(enabled = enabled, role = Role.Button) { onGeneratedArtifactSelected(artifact.path) }
      .focusable(enabled = enabled),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = artifact.path,
      color = SkillBillTheme.frameTokens.subtle.copy(alpha = labelAlpha),
      fontSize = 10.sp,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.sp,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = "read-only",
      color = SkillBillTheme.frameTokens.status.contentColorFor(Tone.Warning).copy(alpha = labelAlpha),
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

internal fun generatedArtifactRowContentDescription(artifact: GeneratedArtifactDetail): String =
  "Open artifact: ${artifact.path}"

internal fun treeSingleClickSwitchesToOpenTab(itemId: String, openEditorTabIds: Set<String>): Boolean =
  itemId in openEditorTabIds

internal fun treeRowStateDescription(open: Boolean): String =
  if (open) "Open in editor tab" else "Not open in editor tab"

@Composable
private fun Badge(text: String, tone: Tone) {
  val toneColor = SkillBillTheme.frameTokens.status.contentColorFor(tone)
  Text(
    text = text,
    color = toneColor,
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    modifier =
    Modifier
      .border(1.dp, toneColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
      .background(toneColor.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 1.dp),
    maxLines = 1,
  )
}
