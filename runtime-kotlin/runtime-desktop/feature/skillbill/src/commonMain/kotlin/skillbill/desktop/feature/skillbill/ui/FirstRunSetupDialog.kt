@file:Suppress("FunctionName", "MagicNumber", "LongMethod")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillSurfaceTone
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.domain.model.FirstRunInstallDetail
import skillbill.desktop.core.domain.model.FirstRunInstallDetailSeverity
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.FirstRunSetupStep
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel

data class FirstRunSetupCallbacks(
  val onAgentSelectionChanged: (String, Boolean) -> Unit,
  val onPlatformSelectionChanged: (String, Boolean) -> Unit,
  val onTelemetryChanged: (FirstRunTelemetryLevel) -> Unit,
  val onBack: () -> Unit,
  val onNext: () -> Unit,
  val onApply: () -> Unit,
  val onRetry: () -> Unit,
  val onFinish: () -> Unit,
  val onDismiss: () -> Unit,
)

@Composable
fun FirstRunSetupDialog(state: FirstRunSetupState, callbacks: FirstRunSetupCallbacks) {
  val semanticTones = SkillBillTheme.semanticTones
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(semanticTones.scrim)
      .semantics { contentDescription = "First-run setup wizard" }
      .clickable(enabled = !state.busy, role = Role.Button, onClick = callbacks.onDismiss),
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .widthIn(min = 620.dp, max = 820.dp)
        .heightIn(max = 700.dp)
        .clip(SkillBillTheme.shapes.medium)
        .border(1.dp, semanticTones.dialog.border, SkillBillTheme.shapes.medium)
        .background(semanticTones.dialog.container)
        // Block dismiss-on-outside-tap when the user interacts inside the panel.
        .clickable(enabled = false, onClick = {}),
    ) {
      SetupHeader(state = state, onDismiss = callbacks.onDismiss)
      HorizontalDivider(color = semanticTones.dialog.border)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        state.errorMessage?.let { message ->
          SetupBanner(title = "Setup issue", message = message, tone = semanticTones.warningBanner)
        }
        when (state.step) {
          FirstRunSetupStep.AGENTS -> AgentSelectionStep(state, callbacks)
          FirstRunSetupStep.PLATFORM_PACKS -> PlatformPackStep(state, callbacks)
          FirstRunSetupStep.PREFERENCES -> PreferencesStep(state, callbacks)
          FirstRunSetupStep.APPLY -> ApplyStep(state)
          FirstRunSetupStep.RESULT -> OutcomeStep(state)
        }
      }
      HorizontalDivider(color = semanticTones.dialog.border)
      SetupFooter(state, callbacks)
    }
  }
}

@Composable
private fun SetupHeader(state: FirstRunSetupState, onDismiss: () -> Unit) {
  val dialogTone = SkillBillTheme.semanticTones.dialog
  val colors = SkillBillTheme.colors
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp, vertical = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(text = "Skill Bill setup", color = dialogTone.content, style = MaterialTheme.typography.bodyLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FirstRunSetupStep.entries.forEach { step ->
          StepPill(step = step, selected = step == state.step)
        }
      }
    }
    Text(
      text = "x",
      color = if (state.busy) colors.onSurfaceVariant.copy(alpha = 0.55f) else colors.onSurfaceVariant,
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier
        .semantics { contentDescription = "Dismiss setup wizard" }
        .clickable(enabled = !state.busy, role = Role.Button, onClick = onDismiss)
        .padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun StepPill(step: FirstRunSetupStep, selected: Boolean) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Text(
    text = step.label(),
    color = if (selected) colors.onPrimary else dialogTone.content,
    style = SkillBillTypeStyles.caption,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier
      .clip(SkillBillComponentShapes.control)
      .background(if (selected) colors.primary else colors.surfaceVariant)
      .border(1.dp, dialogTone.border, SkillBillComponentShapes.control)
      .padding(horizontal = 8.dp, vertical = 5.dp),
  )
}

@Composable
private fun AgentSelectionStep(state: FirstRunSetupState, callbacks: FirstRunSetupCallbacks) {
  SectionTitle("Agents")
  state.agentOptions.forEach { option ->
    ToggleRow(
      label = option.displayName,
      selected = option.agentId in state.selectedAgentIds,
      enabled = !state.busy,
      detail = if (option.detected) {
        "Detected at ${option.detectedPath.orEmpty()}"
      } else {
        "Manual selection"
      },
      onClick = { callbacks.onAgentSelectionChanged(option.agentId, option.agentId !in state.selectedAgentIds) },
    )
  }
}

@Composable
private fun PlatformPackStep(state: FirstRunSetupState, callbacks: FirstRunSetupCallbacks) {
  val colors = SkillBillTheme.colors
  SectionTitle("Platform packs")
  Text(
    text = "Base skills install automatically.",
    color = colors.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  if (state.platformPacks.isEmpty()) {
    Text(
      text = "No platform packs discovered.",
      color = colors.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  } else {
    state.platformPacks.forEach { pack ->
      ToggleRow(
        label = pack.slug,
        selected = pack.slug in state.selectedPlatformSlugs,
        enabled = !state.busy,
        detail = pack.packRoot,
        onClick = { callbacks.onPlatformSelectionChanged(pack.slug, pack.slug !in state.selectedPlatformSlugs) },
      )
    }
  }
}

@Composable
private fun PreferencesStep(state: FirstRunSetupState, callbacks: FirstRunSetupCallbacks) {
  val colors = SkillBillTheme.colors
  SectionTitle("Telemetry")
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    FirstRunTelemetryLevel.entries.forEach { level ->
      SelectPill(
        label = level.id,
        selected = state.telemetryLevel == level,
        enabled = !state.busy,
        onClick = { callbacks.onTelemetryChanged(level) },
      )
    }
  }
  Spacer(modifier = Modifier.height(4.dp))
  SectionTitle("MCP")
  Text(
    text = if (state.registerMcp) {
      "Skill Bill MCP server will be registered for selected agents."
    } else {
      "Skill Bill MCP server registration is skipped."
    },
    color = colors.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun ApplyStep(state: FirstRunSetupState) {
  SectionTitle("Ready")
  SummaryLine(label = "Agents", value = state.selectedAgentIds.sorted().joinToString(", "))
  SummaryLine(
    label = "Platform packs",
    value = state.selectedPlatformSlugs.sorted().joinToString(", ").ifBlank {
      "none"
    },
  )
  SummaryLine(label = "Telemetry", value = state.telemetryLevel.id)
  SummaryLine(label = "MCP", value = if (state.registerMcp) "register" else "skip")
}

@Composable
private fun OutcomeStep(state: FirstRunSetupState) {
  val outcome = state.outcome
  if (outcome == null) {
    Text(
      text = "Setup has not run yet.",
      color = SkillBillTheme.colors.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    return
  }
  val tone = when (outcome.status) {
    FirstRunInstallStatus.SUCCESS -> SkillBillTheme.semanticTones.successBanner
    FirstRunInstallStatus.WARNING -> SkillBillTheme.semanticTones.warningBanner
    FirstRunInstallStatus.FAILURE -> SkillBillTheme.semanticTones.errorBanner
  }
  SetupBanner(title = outcome.title, message = outcome.status.name.lowercase(), tone = tone)
  outcome.details.forEach { detail -> DetailRow(detail) }
}

@Composable
private fun SetupFooter(state: FirstRunSetupState, callbacks: FirstRunSetupCallbacks) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val showBack = state.step != FirstRunSetupStep.AGENTS && state.step != FirstRunSetupStep.RESULT
    if (showBack) {
      SetupButton(label = "Back", enabled = !state.busy, onClick = callbacks.onBack)
    }
    when (state.step) {
      FirstRunSetupStep.AGENTS,
      FirstRunSetupStep.PLATFORM_PACKS,
      FirstRunSetupStep.PREFERENCES,
      -> SetupButton(label = "Next", enabled = state.canContinue, primary = true, onClick = callbacks.onNext)
      FirstRunSetupStep.APPLY -> SetupButton(
        label = if (state.busy) "Installing..." else "Install",
        enabled = state.canContinue,
        primary = true,
        onClick = callbacks.onApply,
      )
      FirstRunSetupStep.RESULT -> {
        if (state.outcome?.status == FirstRunInstallStatus.FAILURE) {
          SetupButton(label = "Retry", enabled = !state.busy, primary = true, onClick = callbacks.onRetry)
        } else {
          SetupButton(label = "Done", enabled = !state.busy, primary = true, onClick = callbacks.onFinish)
        }
      }
    }
  }
}

@Composable
private fun ToggleRow(label: String, selected: Boolean, enabled: Boolean, detail: String, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(1.dp, dialogTone.border, SkillBillComponentShapes.control)
      .background(colors.surfaceVariant)
      .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = if (selected) "[x]" else "[ ]",
      color = if (selected) colors.primary else colors.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(text = label, color = dialogTone.content, style = MaterialTheme.typography.bodySmall)
      Text(
        text = detail,
        color = colors.onSurfaceVariant,
        style = SkillBillTypeStyles.caption,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun SelectPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Text(
    text = label,
    color = if (selected) colors.onPrimary else dialogTone.content,
    style = MaterialTheme.typography.bodySmall,
    modifier = Modifier
      .clip(SkillBillComponentShapes.control)
      .background(if (selected) colors.primary else colors.surfaceVariant)
      .border(1.dp, dialogTone.border, SkillBillComponentShapes.control)
      .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 7.dp),
  )
}

@Composable
private fun SetupButton(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Text(
    text = label,
    color = when {
      !enabled -> colors.onSurfaceVariant
      primary -> colors.onPrimary
      else -> dialogTone.content
    },
    style = MaterialTheme.typography.bodySmall,
    modifier = Modifier
      .clip(SkillBillComponentShapes.control)
      .background(if (primary && enabled) colors.primary else colors.surfaceVariant)
      .border(1.dp, dialogTone.border, SkillBillComponentShapes.control)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  )
}

@Composable
private fun SetupBanner(title: String, message: String, tone: SkillBillSurfaceTone) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(1.dp, tone.border, SkillBillComponentShapes.control)
      .background(tone.container)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(text = title, color = tone.content, style = MaterialTheme.typography.bodySmall)
    Text(text = message, color = tone.content, style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
private fun DetailRow(detail: FirstRunInstallDetail) {
  val colors = SkillBillTheme.colors
  val semanticTones = SkillBillTheme.semanticTones
  val color = when (detail.severity) {
    FirstRunInstallDetailSeverity.INFO -> semanticTones.dialog.content
    FirstRunInstallDetailSeverity.WARNING -> semanticTones.warningBanner.content
    FirstRunInstallDetailSeverity.ERROR -> semanticTones.errorBanner.content
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(1.dp, semanticTones.dialog.border, SkillBillComponentShapes.control)
      .background(colors.surfaceVariant)
      .padding(horizontal = 12.dp, vertical = 9.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Text(text = detail.label, color = color, style = MaterialTheme.typography.labelSmall)
    Text(text = detail.message, color = semanticTones.dialog.content, style = MaterialTheme.typography.labelSmall)
    detail.path?.let { path ->
      Text(
        text = path,
        color = colors.onSurfaceVariant,
        style = SkillBillTypeStyles.caption,
        maxLines = 1,
      )
    }
    detail.guidance?.let { guidance ->
      Text(
        text = guidance,
        color = semanticTones.warningBanner.content,
        style = SkillBillTypeStyles.caption,
      )
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text = text,
    color = SkillBillTheme.semanticTones.dialog.content,
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun SummaryLine(label: String, value: String) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text = label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    Text(text = value, color = dialogTone.content, style = MaterialTheme.typography.labelSmall)
  }
}

private fun FirstRunSetupStep.label(): String = when (this) {
  FirstRunSetupStep.AGENTS -> "Agents"
  FirstRunSetupStep.PLATFORM_PACKS -> "Packs"
  FirstRunSetupStep.PREFERENCES -> "Prefs"
  FirstRunSetupStep.APPLY -> "Install"
  FirstRunSetupStep.RESULT -> "Result"
}
