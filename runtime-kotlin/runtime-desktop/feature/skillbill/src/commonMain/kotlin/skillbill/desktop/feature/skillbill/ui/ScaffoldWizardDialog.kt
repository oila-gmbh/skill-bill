@file:Suppress("FunctionName", "MagicNumber", "LongMethod")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.model.ScaffoldWizardFormFields
import skillbill.desktop.core.domain.model.ScaffoldWizardState

/**
 * Public callbacks the dialog needs to drive the view model. Hoisting the callbacks here keeps
 * `SkillBillFrame` callers from passing >40 parameters to the frame; the route builds this struct
 * once and forwards.
 */
data class ScaffoldWizardCallbacks(
  val onSelectKind: (ScaffoldKind) -> Unit,
  val onFormChanged: ((ScaffoldWizardFormFields) -> ScaffoldWizardFormFields) -> Unit,
  val onDirtyOverrideChanged: (Boolean) -> Unit,
  val onPlan: () -> Unit,
  val onRun: () -> Unit,
  val onAcknowledgeFailure: () -> Unit,
  val onDismiss: () -> Unit,
)

private val ScaffoldDialogBackdrop = Color.Black.copy(alpha = 0.5f)
private val ScaffoldDialogPanel = Color(0xFF15151A)
private val ScaffoldDialogLine = Color(0xFF2A2A31)
private val ScaffoldDialogText = Color(0xFFF6F3E7)
private val ScaffoldDialogMuted = Color(0xFFB7B1A0)
private val ScaffoldDialogSteel = Color(0xFF6F7882)
private val ScaffoldDialogYellow = Color(0xFFF4C430)
private val ScaffoldDialogRed = Color(0xFFFF5F57)
private val ScaffoldDialogGreen = Color(0xFF60D394)
private val ScaffoldDialogAmber = Color(0xFFFFBD2E)
private val ScaffoldDialogRaised = Color(0xFF1B1B22)

@Composable
fun ScaffoldWizardDialog(
  state: ScaffoldWizardState,
  canStartScaffoldAction: Boolean,
  callbacks: ScaffoldWizardCallbacks,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ScaffoldDialogBackdrop)
      .semantics { contentDescription = "Scaffold wizard" }
      .clickable(role = Role.Button, onClick = callbacks.onDismiss),
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .widthIn(min = 560.dp, max = 760.dp)
        .heightIn(max = 640.dp)
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, ScaffoldDialogLine, RoundedCornerShape(8.dp))
        .background(ScaffoldDialogPanel)
        // Block dismiss-on-outside-tap when the user interacts inside the panel.
        .clickable(enabled = false, onClick = {}),
    ) {
      WizardHeader(kind = state.kind, onDismiss = callbacks.onDismiss)
      HorizontalDivider(color = ScaffoldDialogLine)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        KindPicker(selected = state.kind, onSelect = callbacks.onSelectKind, enabled = !state.busy)
        if (state.dirtyRepoWarning) {
          DirtyRepoWarning(
            override = state.overrideDirtyRepo,
            enabled = !state.busy,
            onOverrideChanged = callbacks.onDirtyOverrideChanged,
          )
        }
        WizardForm(state = state, callbacks = callbacks)
        // F-102: a Failed result supersedes the (now-stale) plan; render the failure console only
        // so the user is not led to believe Run is ready to fire. Preview/Success continue to
        // show the plan alongside the banner when relevant.
        val hideStalePlan = state.executionResult is ScaffoldRunResult.Failed
        if (!hideStalePlan) {
          state.dryRunPreview?.let { plan ->
            PlanPreview(plan)
          }
        }
        state.executionResult?.let { result ->
          ResultBanner(result)
        }
      }
      HorizontalDivider(color = ScaffoldDialogLine)
      WizardFooter(
        state = state,
        canStartScaffoldAction = canStartScaffoldAction,
        onPlan = callbacks.onPlan,
        onRun = callbacks.onRun,
        onAcknowledgeFailure = callbacks.onAcknowledgeFailure,
        onDismiss = callbacks.onDismiss,
      )
    }
  }
}

@Composable
private fun WizardHeader(kind: ScaffoldKind, onDismiss: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(44.dp)
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = "New ${kind.displayLabel}",
      color = ScaffoldDialogText,
      fontSize = 14.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = "x",
      color = ScaffoldDialogSteel,
      fontSize = 14.sp,
      modifier = Modifier
        .semantics { contentDescription = "Dismiss scaffold wizard" }
        .clickable(role = Role.Button, onClick = onDismiss)
        .padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun KindPicker(selected: ScaffoldKind, onSelect: (ScaffoldKind) -> Unit, enabled: Boolean) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    SectionLabel("Wizard kind")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      ScaffoldKind.values().forEach { kind ->
        val isSelected = kind == selected
        val backgroundColor = if (isSelected) ScaffoldDialogYellow else ScaffoldDialogRaised
        val foregroundColor = when {
          !enabled -> ScaffoldDialogSteel
          isSelected -> Color(0xFF0B0B0D)
          else -> ScaffoldDialogText
        }
        Text(
          text = kind.displayLabel,
          color = foregroundColor,
          fontSize = 11.sp,
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, ScaffoldDialogLine, RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .semantics { contentDescription = "Select wizard kind ${kind.displayLabel}" }
            .clickable(enabled = enabled, role = Role.Button) { onSelect(kind) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun DirtyRepoWarning(override: Boolean, enabled: Boolean, onOverrideChanged: (Boolean) -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, ScaffoldDialogAmber, RoundedCornerShape(6.dp))
      .background(ScaffoldDialogAmber.copy(alpha = 0.08f))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = "Repository has uncommitted non-generated changes",
      color = ScaffoldDialogAmber,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
    )
    Text(
      text = "The scaffolder rolls back transactionally on failure, but dirty content may make a " +
        "partial commit ambiguous. Acknowledge to proceed.",
      color = ScaffoldDialogMuted,
      fontSize = 10.5.sp,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier
        .semantics { contentDescription = "Acknowledge dirty repository warning" }
        .clickable(enabled = enabled, role = Role.Checkbox) { onOverrideChanged(!override) },
    ) {
      Text(text = if (override) "[x]" else "[ ]", color = ScaffoldDialogAmber, fontSize = 12.sp)
      Text(text = "I understand, scaffold anyway", color = ScaffoldDialogText, fontSize = 11.sp)
    }
  }
}

@Composable
private fun WizardForm(state: ScaffoldWizardState, callbacks: ScaffoldWizardCallbacks) {
  val fields = state.formFields
  when (state.kind) {
    ScaffoldKind.HORIZONTAL_SKILL -> {
      TextFieldRow(
        label = "Skill name",
        value = fields.name,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(name = value) }
        },
      )
      TextFieldRow(
        label = "Description",
        value = fields.description,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(description = value) }
        },
      )
    }
    ScaffoldKind.PLATFORM_PACK -> {
      PresetPicker(
        label = "Platform preset",
        options = state.optionCatalog.platformPackPresets.map { it.platform to it.displayName },
        selected = fields.platform,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(platform = value, displayName = it.displayName) }
        },
      )
      TextFieldRow(
        label = "Platform slug",
        value = fields.platform,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(platform = value) }
        },
      )
      TextFieldRow(
        label = "Display name",
        value = fields.displayName,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(displayName = value) }
        },
      )
      PresetPicker(
        label = "Skeleton mode",
        options = listOf("full" to "Full skeleton", "starter" to "Starter skeleton"),
        selected = fields.skeletonMode,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(skeletonMode = value) }
        },
      )
    }
    ScaffoldKind.PLATFORM_OVERRIDE_PILOTED -> {
      PresetPicker(
        label = "Platform",
        options = state.optionCatalog.pilotedPlatformPacks.map { it.platform to it.displayName },
        selected = fields.platform,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(platform = value) }
        },
      )
      PresetPicker(
        label = "Family",
        options = (state.optionCatalog.shelledFamilies + state.optionCatalog.preShellFamilies)
          .map { family -> family to family },
        selected = fields.family,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(family = value) }
        },
      )
      TextFieldRow(
        label = "Description",
        value = fields.description,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(description = value) }
        },
      )
    }
    ScaffoldKind.CODE_REVIEW_AREA -> {
      PresetPicker(
        label = "Platform",
        options = state.optionCatalog.pilotedPlatformPacks.map { it.platform to it.displayName },
        selected = fields.platform,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(platform = value) }
        },
      )
      PresetPicker(
        label = "Code-review area",
        options = state.optionCatalog.approvedCodeReviewAreas.map { area -> area to area },
        selected = fields.area,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(area = value) }
        },
      )
      TextFieldRow(
        label = "Description",
        value = fields.description,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(description = value) }
        },
      )
    }
    ScaffoldKind.ADD_ON -> {
      TextFieldRow(
        label = "Add-on name",
        value = fields.name,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(name = value) }
        },
      )
      PresetPicker(
        label = "Owning platform pack",
        options = state.optionCatalog.pilotedPlatformPacks.map { it.platform to it.displayName },
        selected = fields.platform,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(platform = value) }
        },
      )
      TextFieldRow(
        label = "Body",
        value = fields.addonBody,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(addonBody = value) }
        },
      )
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    color = ScaffoldDialogSteel,
    fontSize = 10.5.sp,
    fontFamily = FontFamily.Monospace,
  )
}

@Composable
private fun TextFieldRow(label: String, value: String, enabled: Boolean, onValueChanged: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SectionLabel(label)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(30.dp)
        .clip(RoundedCornerShape(6.dp))
        .border(1.dp, ScaffoldDialogLine, RoundedCornerShape(6.dp))
        .background(ScaffoldDialogRaised)
        .padding(horizontal = 8.dp, vertical = 6.dp)
        .semantics { contentDescription = "$label input" },
    ) {
      BasicTextField(
        value = value,
        onValueChange = onValueChanged,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
          color = ScaffoldDialogText,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
        ),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun PresetPicker(
  label: String,
  options: List<Pair<String, String>>,
  selected: String,
  enabled: Boolean,
  onSelected: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SectionLabel(label)
    if (options.isEmpty()) {
      Text(
        text = "(no options available)",
        color = ScaffoldDialogSteel,
        fontSize = 11.sp,
      )
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, display) ->
          val isSelected = value == selected
          val backgroundColor = if (isSelected) ScaffoldDialogYellow else ScaffoldDialogRaised
          val foregroundColor = when {
            !enabled -> ScaffoldDialogSteel
            isSelected -> Color(0xFF0B0B0D)
            else -> ScaffoldDialogText
          }
          Text(
            text = display,
            color = foregroundColor,
            fontSize = 11.sp,
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .border(1.dp, ScaffoldDialogLine, RoundedCornerShape(6.dp))
              .background(backgroundColor)
              .semantics { contentDescription = "$label option $display" }
              .clickable(enabled = enabled, role = Role.Button) { onSelected(value) }
              .padding(horizontal = 10.dp, vertical = 6.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun PlanPreview(plan: skillbill.desktop.core.domain.model.ScaffoldPlan) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, ScaffoldDialogGreen, RoundedCornerShape(6.dp))
      .background(ScaffoldDialogGreen.copy(alpha = 0.06f))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = "Dry-run plan",
      color = ScaffoldDialogGreen,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
    )
    PreviewSection(label = "Planned files", lines = plan.createdFiles)
    PreviewSection(label = "Manifest edits", lines = plan.manifestEdits)
    PreviewSection(label = "Symlinks", lines = plan.symlinks)
    PreviewSection(label = "Install targets", lines = plan.installTargets)
    PreviewSection(label = "Notes", lines = plan.notes)
  }
}

@Composable
private fun PreviewSection(label: String, lines: List<String>) {
  if (lines.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      text = label,
      color = ScaffoldDialogSteel,
      fontSize = 10.5.sp,
      fontFamily = FontFamily.Monospace,
    )
    lines.forEach { line ->
      Text(
        text = line,
        color = ScaffoldDialogText,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ResultBanner(result: ScaffoldRunResult) {
  when (result) {
    is ScaffoldRunResult.Success -> SuccessBanner(result)
    is ScaffoldRunResult.Failed -> FailureConsole(result)
    is ScaffoldRunResult.Preview -> Unit
  }
}

@Composable
private fun SuccessBanner(result: ScaffoldRunResult.Success) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, ScaffoldDialogGreen, RoundedCornerShape(6.dp))
      .background(ScaffoldDialogGreen.copy(alpha = 0.08f))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = "Scaffold succeeded: ${result.result.skillName}",
      color = ScaffoldDialogGreen,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
    )
    Text(
      text = result.result.skillPath,
      color = ScaffoldDialogText,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
    )
  }
}

@Composable
private fun FailureConsole(result: ScaffoldRunResult.Failed) {
  val borderColor = if (result.rollbackComplete) ScaffoldDialogRed else ScaffoldDialogAmber
  // F-103: partial mutation MUST be conveyed without depending on color alone. We add a visible
  // text badge, a leading warning glyph, and an explicit semantics contentDescription so the
  // banner is readable to color-blind users and assistive tech.
  val bannerSemantics = if (result.rollbackComplete) {
    "Scaffold failed; runtime rolled back the repository cleanly"
  } else {
    "Scaffold failed with partial mutation; repository may be in an inconsistent state"
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, borderColor, RoundedCornerShape(6.dp))
      .background(borderColor.copy(alpha = 0.08f))
      .padding(horizontal = 12.dp, vertical = 10.dp)
      .semantics { contentDescription = bannerSemantics },
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (!result.rollbackComplete) {
      // Visible badge + leading glyph so the banner reads as visually distinct without depending
      // on color. Placed above the title so it is the first text scanned.
      Text(
        text = "⚠ [REPO PARTIALLY MUTATED]",
        color = ScaffoldDialogAmber,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
      )
    }
    Text(
      text = if (result.rollbackComplete) "Scaffold failed" else "Scaffold failed - partial mutation",
      color = borderColor,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
    )
    if (!result.rollbackComplete) {
      Text(
        text = "Runtime rollback did not complete. Inspect the repo and revert manually before retrying.",
        color = ScaffoldDialogAmber,
        fontSize = 11.sp,
      )
    }
    // Console-styled exception block matches DockTab.Console: monospace, horizontalScroll-friendly.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(4.dp))
        .background(Color(0xFF0B0B0D))
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
      Text(
        text = "${result.exceptionName}: ${result.exceptionMessage}".trim(),
        color = ScaffoldDialogText,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
      )
    }
  }
}

@Composable
private fun WizardFooter(
  state: ScaffoldWizardState,
  canStartScaffoldAction: Boolean,
  onPlan: () -> Unit,
  onRun: () -> Unit,
  onAcknowledgeFailure: () -> Unit,
  onDismiss: () -> Unit,
) {
  // F-102: after a Failed result with rollbackComplete=false, BOTH Plan and Run are disabled
  // until the user acknowledges the partial-mutation state via the "Dismiss error" button.
  // After any Failed result, `dryRunPreview` is null (cleared in the view model), so `runEnabled`
  // is already false until the user re-Plans with fresh inputs.
  val failure = state.executionResult as? ScaffoldRunResult.Failed
  val partialMutationLock = failure?.rollbackComplete == false
  val planEnabled = !state.busy && canStartScaffoldAction && !partialMutationLock
  val runEnabled = state.runEnabled && canStartScaffoldAction && !partialMutationLock
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(52.dp)
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Spacer(modifier = Modifier.weight(1f))
    if (state.executionResult is ScaffoldRunResult.Failed) {
      // F-201: in the partial-mutation case (rollbackComplete == false), clicking this button
      // releases the F-102/AC5 safety lock that holds Plan/Run disabled. A "Dismiss error" label
      // reads as a purely visual gesture (close the banner), which under-communicates the
      // consequence. Use an explicit "Acknowledge partial mutation" label so users — and
      // assistive tech — understand they are accepting that the repo may be in an inconsistent
      // state before scaffolding resumes. The clean-rollback case (rollbackComplete == true)
      // keeps the original "Dismiss error" wording since no safety lock is engaged.
      val acknowledgeLabel = if (partialMutationLock) "Acknowledge partial mutation" else "Dismiss error"
      FooterButton(
        label = acknowledgeLabel,
        enabled = !state.busy,
        primary = false,
        onClick = onAcknowledgeFailure,
      )
    }
    FooterButton(
      label = "Cancel",
      enabled = !state.busy,
      primary = false,
      onClick = onDismiss,
    )
    FooterButton(
      label = if (state.busy) "Planning..." else "Plan (dry run)",
      enabled = planEnabled,
      primary = false,
      onClick = onPlan,
    )
    FooterButton(
      label = if (state.busy) "Running..." else "Run",
      enabled = runEnabled,
      primary = true,
      onClick = onRun,
    )
  }
}

@Composable
private fun FooterButton(label: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
  val background = when {
    !enabled -> ScaffoldDialogRaised
    primary -> ScaffoldDialogYellow
    else -> ScaffoldDialogRaised
  }
  val foreground = when {
    !enabled -> ScaffoldDialogSteel
    primary -> Color(0xFF0B0B0D)
    else -> ScaffoldDialogText
  }
  Text(
    text = label,
    color = foreground,
    fontSize = 12.sp,
    fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .border(1.dp, ScaffoldDialogLine, RoundedCornerShape(6.dp))
      .background(background)
      .semantics { contentDescription = label }
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 7.dp)
      .widthIn(min = 60.dp),
  )
}
