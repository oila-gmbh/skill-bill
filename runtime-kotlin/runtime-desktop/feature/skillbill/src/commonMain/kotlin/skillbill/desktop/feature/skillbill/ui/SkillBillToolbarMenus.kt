@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.accelerator_command_palette
import dev.skillbill.designsystem.generated.resources.busy_choosing
import dev.skillbill.designsystem.generated.resources.busy_deleting
import dev.skillbill.designsystem.generated.resources.busy_opening
import dev.skillbill.designsystem.generated.resources.busy_refreshing
import dev.skillbill.designsystem.generated.resources.busy_saving
import dev.skillbill.designsystem.generated.resources.busy_scaffolding
import dev.skillbill.designsystem.generated.resources.busy_setting_up
import dev.skillbill.designsystem.generated.resources.busy_validating
import dev.skillbill.designsystem.generated.resources.command_search_placeholder
import dev.skillbill.designsystem.generated.resources.toolbar_menu_collapsed
import dev.skillbill.designsystem.generated.resources.toolbar_menu_expanded
import dev.skillbill.designsystem.generated.resources.toolbar_new_menu
import dev.skillbill.designsystem.generated.resources.toolbar_new_menu_cd
import dev.skillbill.designsystem.generated.resources.toolbar_new_menu_wizard_cd
import org.jetbrains.compose.resources.stringResource
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.SkillBillBusyOperation

/**
 * F-X-901 (AC7): toolbar "NEW..." entry point. Renders as a [ToolbarButton] and, when activated,
 * opens a kind picker over every active creation [ScaffoldKind] reachable from the wizard's
 * [KindPicker]. Each entry invokes [onOpenScaffoldWizard] with the corresponding kind.
 *
 * F-X-901-C: migrated from a bare `Popup` to Material3 [DropdownMenu] so keyboard navigation,
 * focus management, and Escape-to-close are provided by the framework rather than reimplemented.
 *
 * F-X-901-D: the parent ToolbarButton announces a distinct `contentDescription` ("Open new
 * scaffold menu") plus a `stateDescription` reflecting expansion, so screen readers don't recite
 * the verbatim label ("NEW dot dot dot") and users hear when the menu opens or closes.
 *
 * F-X-901-F: closes the menu automatically when the parent's `enabled` flag flips to false, so
 * items cannot remain selectable after the toolbar disables.
 */
@Composable
internal fun NewScaffoldMenuButton(enabled: Boolean, onOpenScaffoldWizard: (ScaffoldKind) -> Unit) {
  var menuOpen by remember { mutableStateOf(false) }
  LaunchedEffect(enabled) {
    if (!enabled) menuOpen = false
  }
  val expandedDescription = stringResource(Res.string.toolbar_menu_expanded)
  val collapsedDescription = stringResource(Res.string.toolbar_menu_collapsed)
  Box(
    modifier = Modifier.semantics {
      stateDescription = if (menuOpen) expandedDescription else collapsedDescription
    },
  ) {
    ToolbarButton(
      label = stringResource(Res.string.toolbar_new_menu),
      marker = "nw",
      enabled = enabled,
      onClick = { menuOpen = true },
      contentDescription = stringResource(Res.string.toolbar_new_menu_cd),
    )
    DropdownMenu(
      expanded = menuOpen,
      onDismissRequest = { menuOpen = false },
      modifier = Modifier
        .background(SkillBillTheme.frameTokens.panel)
        .border(SkillBillDimens.hairline, SkillBillTheme.frameTokens.line, SkillBillComponentShapes.control),
    ) {
      ScaffoldKind.activeCreationValues().forEach { kind ->
        val wizardCd = stringResource(Res.string.toolbar_new_menu_wizard_cd, kind.displayLabel)
        DropdownMenuItem(
          text = {
            Text(
              text = kind.displayLabel,
              color = SkillBillTheme.frameTokens.text,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 1,
            )
          },
          colors = MenuDefaults.itemColors(textColor = SkillBillTheme.frameTokens.text),
          modifier = Modifier
            .widthIn(min = SkillBillDimens.menuMinWidth)
            .semantics { contentDescription = wizardCd },
          onClick = {
            menuOpen = false
            onOpenScaffoldWizard(kind)
          },
        )
      }
    }
  }
}

@Composable
internal fun BusyIndicator(busyOperation: SkillBillBusyOperation) {
  val label = when (busyOperation) {
    SkillBillBusyOperation.OPEN_REPO -> stringResource(Res.string.busy_opening)
    SkillBillBusyOperation.REFRESH -> stringResource(Res.string.busy_refreshing)
    SkillBillBusyOperation.CHOOSE_DIRECTORY -> stringResource(Res.string.busy_choosing)
    SkillBillBusyOperation.SAVE -> stringResource(Res.string.busy_saving)
    SkillBillBusyOperation.SCAFFOLD -> stringResource(Res.string.busy_scaffolding)
    SkillBillBusyOperation.FIRST_RUN_SETUP -> stringResource(Res.string.busy_setting_up)
    SkillBillBusyOperation.DELETE -> stringResource(Res.string.busy_deleting)
    SkillBillBusyOperation.VALIDATE_AGENT_CONFIGS -> stringResource(Res.string.busy_validating)
  }
  Row(
    modifier = Modifier.padding(start = SkillBillDimens.padSm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
  ) {
    MiniIcon(text = "..", tint = SkillBillTheme.frameTokens.primary)
    Text(
      text = label,
      color = SkillBillTheme.frameTokens.muted,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
    )
  }
}

@Composable
internal fun ToolbarDivider() {
  Box(
    modifier =
    Modifier
      .padding(horizontal = SkillBillDimens.space5)
      .width(SkillBillDimens.hairline)
      .height(SkillBillDimens.menuSeparatorHeight)
      .background(SkillBillTheme.frameTokens.line),
  )
}

@Composable
internal fun CommandSearchButton(onClick: () -> Unit) {
  Row(
    modifier =
    Modifier
      .width(SkillBillMetrics.toolbarMenuWidth)
      .height(SkillBillDimens.controlHeightMd)
      .border(SkillBillDimens.hairline, SkillBillTheme.frameTokens.line, SkillBillComponentShapes.control)
      .background(SkillBillTheme.frameTokens.raised, SkillBillComponentShapes.control)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = SkillBillDimens.space9),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    MiniIcon(text = "sr", tint = SkillBillTheme.frameTokens.muted)
    Text(
      text = stringResource(Res.string.command_search_placeholder),
      color = SkillBillTheme.frameTokens.subtle,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = stringResource(Res.string.accelerator_command_palette),
      color = SkillBillTheme.frameTokens.subtle,
      style = SkillBillTypeStyles.caption.copy(fontFamily = FontFamily.Monospace),
    )
  }
}
