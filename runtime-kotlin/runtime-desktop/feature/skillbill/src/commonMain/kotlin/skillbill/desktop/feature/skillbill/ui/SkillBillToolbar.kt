@file:Suppress("FunctionName")

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.accelerator_refresh
import dev.skillbill.designsystem.generated.resources.toolbar_back
import dev.skillbill.designsystem.generated.resources.toolbar_hide_details
import dev.skillbill.designsystem.generated.resources.toolbar_install
import dev.skillbill.designsystem.generated.resources.toolbar_open_installed
import dev.skillbill.designsystem.generated.resources.toolbar_open_installed_cd
import dev.skillbill.designsystem.generated.resources.toolbar_panel_hidden
import dev.skillbill.designsystem.generated.resources.toolbar_panel_visible
import dev.skillbill.designsystem.generated.resources.toolbar_refresh
import dev.skillbill.designsystem.generated.resources.toolbar_show_details
import org.jetbrains.compose.resources.stringResource
import skillbill.desktop.core.designsystem.SkillBillColor
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillStatusBar

private const val DISABLED_BUTTON_ALPHA = 0.4f
private const val SIDE_PANEL_WIDTH_RATIO = 0.34f

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
      .height(SkillBillMetrics.toolbarHeight)
      .background(SkillBillTheme.frameTokens.background)
      .border(BorderStroke(SkillBillDimens.borderNone, SkillBillTheme.frameTokens.transparent))
      .padding(horizontal = SkillBillDimens.pad2xl),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (canNavigateBack) {
      ToolbarButton(
        label = stringResource(Res.string.toolbar_back),
        marker = "<",
        enabled = !busy,
        onClick = onNavigateBack,
      )
      Spacer(modifier = Modifier.width(SkillBillDimens.spacingLg))
      ToolbarDivider()
    }
    ToolbarButton(
      label = stringResource(Res.string.toolbar_refresh),
      marker = "rf",
      enabled = !busy,
      acceleratorLabel = stringResource(Res.string.accelerator_refresh),
      onClick = onRefresh,
    )
    ToolbarButton(
      label = stringResource(Res.string.toolbar_install),
      marker = "in",
      enabled = installSetupEnabled,
      onClick = onInstallSetup,
    )
    ToolbarButton(
      label = stringResource(Res.string.toolbar_open_installed),
      marker = "iw",
      contentDescription = stringResource(Res.string.toolbar_open_installed_cd),
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
    Spacer(modifier = Modifier.width(SkillBillDimens.spacingXl))
    ToolbarSidePanelButton(
      contentDescription = if (inspectorVisible) {
        stringResource(
          Res.string.toolbar_hide_details,
        )
      } else {
        stringResource(Res.string.toolbar_show_details)
      },
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
        .height(SkillBillDimens.controlHeightMd)
        .padding(end = SkillBillDimens.padMd)
        .alpha(if (enabled) 1f else DISABLED_BUTTON_ALPHA)
        .clip(SkillBillComponentShapes.control)
        .border(SkillBillDimens.hairline, border, SkillBillComponentShapes.control)
        .background(background)
        // F-X-901-A: merge child Text/icon semantics into the clickable node so screen readers
        // announce a single actionable element instead of three, and `disabled()` propagates to the
        // merged node when `enabled = false`.
        .semantics(mergeDescendants = true) {
          this.contentDescription = contentDescription
          if (!enabled) this.disabled()
        }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = SkillBillDimens.space9),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
    ) {
      MiniIcon(text = marker, tint = foreground)
      Text(
        text = label,
        color = foreground,
        style = MaterialTheme.typography.bodySmall,
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
  val panelVisibleDescription = stringResource(Res.string.toolbar_panel_visible)
  val panelHiddenDescription = stringResource(Res.string.toolbar_panel_hidden)
  Box(
    modifier = Modifier
      .size(width = SkillBillDimens.controlHeightLg, height = SkillBillDimens.controlHeightMd)
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, border, SkillBillComponentShapes.control)
      .background(SkillBillTheme.frameTokens.raised)
      .semantics(mergeDescendants = true) {
        this.contentDescription = contentDescription
        this.stateDescription = if (selected) panelVisibleDescription else panelHiddenDescription
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
  Canvas(modifier = Modifier.size(width = SkillBillDimens.iconMd, height = SkillBillDimens.iconSm)) {
    val strokeWidth = SkillBillDimens.divider.toPx()
    val cornerInset = strokeWidth / 2f
    val bodyWidth = size.width - strokeWidth
    val bodyHeight = size.height - strokeWidth
    val panelWidth = bodyWidth * SIDE_PANEL_WIDTH_RATIO
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
      .height(SkillBillDimens.controlHeightMd)
      .padding(end = SkillBillDimens.padMd)
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, border, SkillBillComponentShapes.control)
      .background(background)
      // F-X-901-G: merge the inner Text/MiniIcon semantics into one node so screen readers
      // announce the chip as a single status string rather than three separate elements.
      .semantics(mergeDescendants = true) { this.contentDescription = label }
      .padding(horizontal = SkillBillDimens.space9),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
  ) {
    MiniIcon(text = marker, tint = foreground)
    Text(
      text = label,
      color = foreground,
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
