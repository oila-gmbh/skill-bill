@file:Suppress("FunctionName", "MagicNumber")

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
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.SkillBillAcceleratorLabels
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
  Box(
    modifier = Modifier.semantics {
      stateDescription = if (menuOpen) "Expanded" else "Collapsed"
    },
  ) {
    ToolbarButton(
      label = "NEW...",
      marker = "nw",
      enabled = enabled,
      onClick = { menuOpen = true },
      contentDescription = "Open new scaffold menu",
    )
    DropdownMenu(
      expanded = menuOpen,
      onDismissRequest = { menuOpen = false },
      modifier = Modifier
        .background(SkillBillTheme.frameTokens.panel)
        .border(1.dp, SkillBillTheme.frameTokens.line, SkillBillComponentShapes.control),
    ) {
      ScaffoldKind.activeCreationValues().forEach { kind ->
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
            .widthIn(min = 220.dp)
            .semantics { contentDescription = "Open ${kind.displayLabel} wizard" },
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
    SkillBillBusyOperation.OPEN_REPO -> "Opening..."
    SkillBillBusyOperation.REFRESH -> "Refreshing..."
    SkillBillBusyOperation.CHOOSE_DIRECTORY -> "Choosing..."
    SkillBillBusyOperation.SAVE -> "Saving..."
    SkillBillBusyOperation.SCAFFOLD -> "Scaffolding..."
    SkillBillBusyOperation.FIRST_RUN_SETUP -> "Setting up..."
    SkillBillBusyOperation.DELETE -> "Deleting..."
    SkillBillBusyOperation.VALIDATE_AGENT_CONFIGS -> "Validating agent configs..."
  }
  Row(
    modifier = Modifier.padding(start = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
      .padding(horizontal = 5.dp)
      .width(1.dp)
      .height(20.dp)
      .background(SkillBillTheme.frameTokens.line),
  )
}

@Composable
internal fun CommandSearchButton(onClick: () -> Unit) {
  Row(
    modifier =
    Modifier
      .width(288.dp)
      .height(28.dp)
      .border(1.dp, SkillBillTheme.frameTokens.line, SkillBillComponentShapes.control)
      .background(SkillBillTheme.frameTokens.raised, SkillBillComponentShapes.control)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MiniIcon(text = "sr", tint = SkillBillTheme.frameTokens.muted)
    Text(
      text = "Find skill, intent, or command...",
      color = SkillBillTheme.frameTokens.subtle,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = SkillBillAcceleratorLabels.COMMAND_PALETTE,
      color = SkillBillTheme.frameTokens.subtle,
      style = SkillBillTypeStyles.caption.copy(fontFamily = FontFamily.Monospace),
    )
  }
}
