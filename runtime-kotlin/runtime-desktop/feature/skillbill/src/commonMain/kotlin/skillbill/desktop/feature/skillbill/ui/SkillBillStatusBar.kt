@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.contentColorFor
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillStatusBar
import skillbill.desktop.core.designsystem.SkillBillStatusTone as Tone

@Composable
internal fun WorkspaceStatusBar(state: SkillBillState) {
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(28.dp)
      .background(SkillBillTheme.frameTokens.panel)
      .padding(horizontal = 12.dp)
      .horizontalScroll(rememberScrollState()),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    StatusItem("rp", state.statusBar.repoPathLabel, Tone.Neutral)
    StatusItem("tr", "${state.statusBar.targetCount} targets", Tone.Neutral)
    Spacer(modifier = Modifier.weight(1f))
    StatusItem(
      fileModeMarker(state.statusBar.readOnlyModeLabel),
      state.statusBar.readOnlyModeLabel,
      fileModeTone(state.statusBar.readOnlyModeLabel),
    )
    StatusItem("lk", state.statusBar.policyLabel, Tone.Neutral)
  }
}

private fun fileModeTone(label: String): Tone = when (label) {
  SkillBillStatusBar.EDITABLE_MODE_LABEL -> Tone.Success
  "dirty" -> Tone.Warning
  else -> Tone.Warning
}

@Composable
private fun StatusItem(marker: String, text: String, tone: Tone) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    val markerTint = if (tone == Tone.Neutral) {
      SkillBillTheme.frameTokens.primary
    } else {
      SkillBillTheme.frameTokens.status.contentColorFor(tone)
    }
    MiniIcon(text = marker, tint = markerTint)
    Text(
      text = text,
      color = SkillBillTheme.frameTokens.status.contentColorFor(tone),
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}
