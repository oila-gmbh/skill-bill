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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val SetupBackdrop = Color.Black.copy(alpha = 0.72f)
private val SetupPanel = Color(0xFF15151A)
private val SetupRaised = Color(0xFF1B1B22)
private val SetupLine = Color(0xFF2A2A31)
private val SetupText = Color(0xFFF6F3E7)
private val SetupMuted = Color(0xFFB7B1A0)
private val SetupSteel = Color(0xFF6F7882)
private val SetupYellow = Color(0xFFF4C430)
private val SetupGreen = Color(0xFF60D394)
private val SetupAmber = Color(0xFFFFBD2E)
private val SetupRed = Color(0xFFFF5F57)

@Composable
fun FirstRunSetupDialog(state: FirstRunSetupState, callbacks: FirstRunSetupCallbacks) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(SetupBackdrop)
      .semantics { contentDescription = "First-run setup wizard" }
      .clickable(enabled = !state.busy, role = Role.Button, onClick = callbacks.onDismiss),
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .widthIn(min = 620.dp, max = 820.dp)
        .heightIn(max = 700.dp)
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, SetupLine, RoundedCornerShape(8.dp))
        .background(SetupPanel)
        // Block dismiss-on-outside-tap when the user interacts inside the panel.
        .clickable(enabled = false, onClick = {}),
    ) {
      SetupHeader(state = state, onDismiss = callbacks.onDismiss)
      HorizontalDivider(color = SetupLine)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        state.errorMessage?.let { message ->
          SetupBanner(title = "Setup issue", message = message, color = SetupAmber)
        }
        when (state.step) {
          FirstRunSetupStep.AGENTS -> AgentSelectionStep(state, callbacks)
          FirstRunSetupStep.PLATFORM_PACKS -> PlatformPackStep(state, callbacks)
          FirstRunSetupStep.PREFERENCES -> PreferencesStep(state, callbacks)
          FirstRunSetupStep.APPLY -> ApplyStep(state)
          FirstRunSetupStep.RESULT -> OutcomeStep(state)
        }
      }
      HorizontalDivider(color = SetupLine)
      SetupFooter(state, callbacks)
    }
  }
}

@Composable
private fun SetupHeader(state: FirstRunSetupState, onDismiss: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp, vertical = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(text = "Skill Bill setup", color = SetupText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FirstRunSetupStep.entries.forEach { step ->
          StepPill(step = step, selected = step == state.step)
        }
      }
    }
    Text(
      text = "x",
      color = if (state.busy) SetupSteel.copy(alpha = 0.55f) else SetupSteel,
      fontSize = 14.sp,
      modifier = Modifier
        .semantics { contentDescription = "Dismiss setup wizard" }
        .clickable(enabled = !state.busy, role = Role.Button, onClick = onDismiss)
        .padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun StepPill(step: FirstRunSetupStep, selected: Boolean) {
  Text(
    text = step.label(),
    color = if (selected) Color(0xFF0B0B0D) else SetupText,
    fontSize = 10.sp,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(if (selected) SetupYellow else SetupRaised)
      .border(1.dp, SetupLine, RoundedCornerShape(6.dp))
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
  SectionTitle("Platform packs")
  Text(text = "Base skills install automatically.", color = SetupMuted, fontSize = 11.sp)
  if (state.platformPacks.isEmpty()) {
    Text(text = "No platform packs discovered.", color = SetupSteel, fontSize = 12.sp)
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
    text = "Skill Bill MCP server will be registered for selected agents.",
    color = SetupMuted,
    fontSize = 12.sp,
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
  SummaryLine(label = "MCP", value = "register")
}

@Composable
private fun OutcomeStep(state: FirstRunSetupState) {
  val outcome = state.outcome
  if (outcome == null) {
    Text(text = "Setup has not run yet.", color = SetupSteel, fontSize = 12.sp)
    return
  }
  val color = when (outcome.status) {
    FirstRunInstallStatus.SUCCESS -> SetupGreen
    FirstRunInstallStatus.WARNING -> SetupAmber
    FirstRunInstallStatus.FAILURE -> SetupRed
  }
  SetupBanner(title = outcome.title, message = outcome.status.name.lowercase(), color = color)
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
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, SetupLine, RoundedCornerShape(6.dp))
      .background(SetupRaised)
      .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = if (selected) "[x]" else "[ ]", color = if (selected) SetupYellow else SetupSteel, fontSize = 12.sp)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(text = label, color = SetupText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
      Text(text = detail, color = SetupMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun SelectPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Text(
    text = label,
    color = if (selected) Color(0xFF0B0B0D) else SetupText,
    fontSize = 12.sp,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(if (selected) SetupYellow else SetupRaised)
      .border(1.dp, SetupLine, RoundedCornerShape(6.dp))
      .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 7.dp),
  )
}

@Composable
private fun SetupButton(label: String, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
  Text(
    text = label,
    color = when {
      !enabled -> SetupSteel
      primary -> Color(0xFF0B0B0D)
      else -> SetupText
    },
    fontSize = 12.sp,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(if (primary && enabled) SetupYellow else SetupRaised)
      .border(1.dp, SetupLine, RoundedCornerShape(6.dp))
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  )
}

@Composable
private fun SetupBanner(title: String, message: String, color: Color) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, color, RoundedCornerShape(6.dp))
      .background(color.copy(alpha = 0.08f))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(text = title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Text(text = message, color = SetupText, fontSize = 11.sp)
  }
}

@Composable
private fun DetailRow(detail: FirstRunInstallDetail) {
  val color = when (detail.severity) {
    FirstRunInstallDetailSeverity.INFO -> SetupText
    FirstRunInstallDetailSeverity.WARNING -> SetupAmber
    FirstRunInstallDetailSeverity.ERROR -> SetupRed
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, SetupLine, RoundedCornerShape(6.dp))
      .background(SetupRaised)
      .padding(horizontal = 12.dp, vertical = 9.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Text(text = detail.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Text(text = detail.message, color = SetupText, fontSize = 11.sp)
    detail.path?.let { path -> Text(text = path, color = SetupMuted, fontSize = 10.sp, maxLines = 1) }
    detail.guidance?.let { guidance -> Text(text = guidance, color = SetupAmber, fontSize = 10.sp) }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(text = text, color = SetupText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun SummaryLine(label: String, value: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text = label, color = SetupMuted, fontSize = 11.sp)
    Text(text = value, color = SetupText, fontSize = 11.sp)
  }
}

private fun FirstRunSetupStep.label(): String = when (this) {
  FirstRunSetupStep.AGENTS -> "Agents"
  FirstRunSetupStep.PLATFORM_PACKS -> "Packs"
  FirstRunSetupStep.PREFERENCES -> "Prefs"
  FirstRunSetupStep.APPLY -> "Install"
  FirstRunSetupStep.RESULT -> "Result"
}
