@file:Suppress("FunctionName", "MagicNumber")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.designsystem.SkillBillColor
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.SkillBillAcceleratorLabels
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillStatusBar

private const val DISABLED_BUTTON_ALPHA = 0.4f

@Composable
internal fun WorkspaceToolbar(
  canNavigateBack: Boolean,
  onNavigateBack: () -> Unit,
  onRefresh: () -> Unit,
  onInstallSetup: () -> Unit,
  onReturnToInstalledWorkspace: () -> Unit,
  inspectorVisible: Boolean,
  onInspectorVisibilityToggle: () -> Unit,
  onCommandPaletteOpen: () -> Unit,
  onOpenScaffoldWizard: (ScaffoldKind) -> Unit,
  installSetupEnabled: Boolean,
  returnToInstalledWorkspaceEnabled: Boolean,
  readOnlyModeLabel: String,
  busyOperation: SkillBillBusyOperation?,
  scaffoldEnabled: Boolean,
) {
  val busy = busyOperation != null
  Row(
    modifier =
    Modifier
      .fillMaxWidth()
      .height(40.dp)
      .background(SkillBillTheme.frameTokens.background)
      .border(BorderStroke(0.dp, SkillBillTheme.frameTokens.transparent))
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (canNavigateBack) {
      ToolbarButton(label = "Back", marker = "<", enabled = !busy, onClick = onNavigateBack)
      Spacer(modifier = Modifier.width(8.dp))
      ToolbarDivider()
    }
    ToolbarButton(
      label = "Refresh",
      marker = "rf",
      enabled = !busy,
      acceleratorLabel = SkillBillAcceleratorLabels.REFRESH,
      onClick = onRefresh,
    )
    ToolbarButton(
      label = "Install",
      marker = "in",
      enabled = installSetupEnabled,
      onClick = onInstallSetup,
    )
    ToolbarButton(
      label = "Open installed",
      marker = "iw",
      contentDescription = "Open installed workspace",
      enabled = returnToInstalledWorkspaceEnabled,
      onClick = onReturnToInstalledWorkspace,
    )
    NewScaffoldMenuButton(enabled = scaffoldEnabled, onOpenScaffoldWizard = onOpenScaffoldWizard)
    ToolbarDivider()
    // F-X-901 (AC6): file editability is a status indicator, not a toggle. Render as a status chip
    // without click semantics.
    ToolbarStatusItem(
      label = readOnlyModeLabel,
      marker = fileModeMarker(readOnlyModeLabel),
      primary = readOnlyModeLabel != SkillBillStatusBar.READ_ONLY_MODE_LABEL,
    )
    if (busyOperation != null) {
      BusyIndicator(busyOperation)
    }
    Spacer(modifier = Modifier.weight(1f))
    CommandSearchButton(onClick = onCommandPaletteOpen)
    Spacer(modifier = Modifier.width(10.dp))
    ToolbarSidePanelButton(
      contentDescription = if (inspectorVisible) "Hide details panel" else "Show details panel",
      selected = inspectorVisible,
      enabled = !busy,
      onClick = onInspectorVisibilityToggle,
    )
  }
}

// F-X-901 (AC1/AC5): no default `onClick = {}` — every call site must pass an explicit handler so
// dead affordances cannot be silently inherited. `contentDescription` defaults to `label` but may
// be overridden when the visible label and announced intent differ (e.g. the "NEW..." disclosure
// button, which announces "Open new scaffold menu").
@Composable
internal fun ToolbarButton(
  label: String,
  marker: String,
  onClick: () -> Unit,
  primary: Boolean = false,
  enabled: Boolean = true,
  contentDescription: String = label,
  acceleratorLabel: String? = null,
) {
  val background = if (primary) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.raised
  val foreground =
    when {
      !enabled -> SkillBillTheme.frameTokens.subtle
      primary -> workspacePrimaryControlForeground()
      else -> SkillBillTheme.frameTokens.text
    }
  val border = if (primary) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.line
  AcceleratorTooltip(label = label, acceleratorLabel = acceleratorLabel) {
    Row(
      modifier =
      Modifier
        .height(28.dp)
        .padding(end = 6.dp)
        .alpha(if (enabled) 1f else DISABLED_BUTTON_ALPHA)
        .clip(SkillBillComponentShapes.control)
        .border(1.dp, border, SkillBillComponentShapes.control)
        .background(background)
        // F-X-901-A: merge child Text/icon semantics into the clickable node so screen readers
        // announce a single actionable element instead of three, and `disabled()` propagates to the
        // merged node when `enabled = false`.
        .semantics(mergeDescendants = true) {
          this.contentDescription = contentDescription
          if (!enabled) this.disabled()
        }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      MiniIcon(text = marker, tint = foreground)
      Text(
        text = label,
        color = foreground,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ToolbarSidePanelButton(
  contentDescription: String,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val foreground =
    when {
      !enabled -> SkillBillTheme.frameTokens.subtle
      selected -> SkillBillTheme.frameTokens.primary
      else -> SkillBillTheme.frameTokens.text
    }
  val border = if (selected) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.line
  Box(
    modifier = Modifier
      .size(width = 30.dp, height = 28.dp)
      .clip(SkillBillComponentShapes.control)
      .border(1.dp, border, SkillBillComponentShapes.control)
      .background(SkillBillTheme.frameTokens.raised)
      .semantics(mergeDescendants = true) {
        this.contentDescription = contentDescription
        this.stateDescription = if (selected) "visible" else "hidden"
        if (!enabled) this.disabled()
      }
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    SidePanelIcon(tint = foreground, panelVisible = selected)
  }
}

@Composable
private fun SidePanelIcon(tint: SkillBillColor, panelVisible: Boolean) {
  Canvas(modifier = Modifier.size(width = 15.dp, height = 14.dp)) {
    val strokeWidth = 1.4.dp.toPx()
    val cornerInset = strokeWidth / 2f
    val bodyWidth = size.width - strokeWidth
    val bodyHeight = size.height - strokeWidth
    val panelWidth = bodyWidth * 0.34f
    drawRect(
      color = tint,
      topLeft = Offset(cornerInset, cornerInset),
      size = Size(bodyWidth, bodyHeight),
      style = Stroke(width = strokeWidth),
    )
    if (panelVisible) {
      drawRect(
        color = tint.copy(alpha = 0.28f),
        topLeft = Offset(size.width - panelWidth, cornerInset + strokeWidth),
        size = Size(panelWidth - strokeWidth, bodyHeight - strokeWidth * 2f),
      )
    }
    val dividerX = size.width - panelWidth
    drawLine(
      color = tint,
      start = Offset(dividerX, cornerInset),
      end = Offset(dividerX, size.height - cornerInset),
      strokeWidth = strokeWidth,
    )
  }
}

/**
 * F-X-901 (AC5/AC6): toolbar chip used for status indicators (branch label, file editability).
 * Has the same visual treatment as [ToolbarButton] but no .clickable, no Role.Button, no
 * hover/press affordance. Accessibility announces the value as plain text so screen readers do not
 * advertise a tappable affordance.
 */
@Composable
private fun ToolbarStatusItem(label: String, marker: String, primary: Boolean = false) {
  val background = if (primary) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.raised
  val foreground = if (primary) workspacePrimaryControlForeground() else SkillBillTheme.frameTokens.text
  val border = if (primary) SkillBillTheme.frameTokens.primary else SkillBillTheme.frameTokens.line
  Row(
    modifier =
    Modifier
      .height(28.dp)
      .padding(end = 6.dp)
      .clip(SkillBillComponentShapes.control)
      .border(1.dp, border, SkillBillComponentShapes.control)
      .background(background)
      // F-X-901-G: merge the inner Text/MiniIcon semantics into one node so screen readers
      // announce the chip as a single status string rather than three separate elements.
      .semantics(mergeDescendants = true) { this.contentDescription = label }
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    MiniIcon(text = marker, tint = foreground)
    Text(
      text = label,
      color = foreground,
      fontSize = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
