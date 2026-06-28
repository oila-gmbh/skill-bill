@file:Suppress("FunctionName", "LongMethod")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.confirm_deletion_state_checked
import dev.skillbill.designsystem.generated.resources.confirm_deletion_state_unchecked
import dev.skillbill.designsystem.generated.resources.scaffold_ack_dirty_repo_cd
import dev.skillbill.designsystem.generated.resources.scaffold_acknowledge_partial_mutation
import dev.skillbill.designsystem.generated.resources.scaffold_add_layer
import dev.skillbill.designsystem.generated.resources.scaffold_add_on_name
import dev.skillbill.designsystem.generated.resources.scaffold_add_suggested_prefix
import dev.skillbill.designsystem.generated.resources.scaffold_baseline_pack
import dev.skillbill.designsystem.generated.resources.scaffold_baseline_section
import dev.skillbill.designsystem.generated.resources.scaffold_baseline_skill
import dev.skillbill.designsystem.generated.resources.scaffold_cancel
import dev.skillbill.designsystem.generated.resources.scaffold_code_review_area
import dev.skillbill.designsystem.generated.resources.scaffold_description
import dev.skillbill.designsystem.generated.resources.scaffold_dialog_cd
import dev.skillbill.designsystem.generated.resources.scaffold_dirty_repo_ack_label
import dev.skillbill.designsystem.generated.resources.scaffold_dirty_repo_detail
import dev.skillbill.designsystem.generated.resources.scaffold_dirty_repo_heading
import dev.skillbill.designsystem.generated.resources.scaffold_dismiss_cd
import dev.skillbill.designsystem.generated.resources.scaffold_dismiss_error
import dev.skillbill.designsystem.generated.resources.scaffold_display_name
import dev.skillbill.designsystem.generated.resources.scaffold_dry_run_plan
import dev.skillbill.designsystem.generated.resources.scaffold_error_add_on_name_required
import dev.skillbill.designsystem.generated.resources.scaffold_error_baseline_composition_cycle
import dev.skillbill.designsystem.generated.resources.scaffold_error_baseline_mode_unsupported
import dev.skillbill.designsystem.generated.resources.scaffold_error_baseline_pack_required
import dev.skillbill.designsystem.generated.resources.scaffold_error_baseline_pack_unavailable
import dev.skillbill.designsystem.generated.resources.scaffold_error_baseline_scope_unsupported
import dev.skillbill.designsystem.generated.resources.scaffold_error_baseline_self_reference
import dev.skillbill.designsystem.generated.resources.scaffold_error_baseline_skill_required
import dev.skillbill.designsystem.generated.resources.scaffold_error_baseline_skill_unavailable
import dev.skillbill.designsystem.generated.resources.scaffold_error_code_review_area_required
import dev.skillbill.designsystem.generated.resources.scaffold_error_duplicate_baseline_layer
import dev.skillbill.designsystem.generated.resources.scaffold_error_family_required
import dev.skillbill.designsystem.generated.resources.scaffold_error_owning_platform_pack_required
import dev.skillbill.designsystem.generated.resources.scaffold_error_platform_required
import dev.skillbill.designsystem.generated.resources.scaffold_error_platform_slug_required
import dev.skillbill.designsystem.generated.resources.scaffold_error_skill_name_required
import dev.skillbill.designsystem.generated.resources.scaffold_failed
import dev.skillbill.designsystem.generated.resources.scaffold_failed_partial
import dev.skillbill.designsystem.generated.resources.scaffold_failed_partial_cd
import dev.skillbill.designsystem.generated.resources.scaffold_failed_rolled_back_cd
import dev.skillbill.designsystem.generated.resources.scaffold_family
import dev.skillbill.designsystem.generated.resources.scaffold_hide_manifest_yaml
import dev.skillbill.designsystem.generated.resources.scaffold_label_input_cd
import dev.skillbill.designsystem.generated.resources.scaffold_layer_number
import dev.skillbill.designsystem.generated.resources.scaffold_manifest_edit_previews
import dev.skillbill.designsystem.generated.resources.scaffold_mode
import dev.skillbill.designsystem.generated.resources.scaffold_no_baseline_layers
import dev.skillbill.designsystem.generated.resources.scaffold_no_baseline_packs
import dev.skillbill.designsystem.generated.resources.scaffold_no_options
import dev.skillbill.designsystem.generated.resources.scaffold_option_cd
import dev.skillbill.designsystem.generated.resources.scaffold_owning_platform_pack
import dev.skillbill.designsystem.generated.resources.scaffold_plan_dry_run
import dev.skillbill.designsystem.generated.resources.scaffold_planning
import dev.skillbill.designsystem.generated.resources.scaffold_platform
import dev.skillbill.designsystem.generated.resources.scaffold_platform_preset
import dev.skillbill.designsystem.generated.resources.scaffold_platform_slug
import dev.skillbill.designsystem.generated.resources.scaffold_prefix_field_cd
import dev.skillbill.designsystem.generated.resources.scaffold_preview_install_targets
import dev.skillbill.designsystem.generated.resources.scaffold_preview_manifest_edits
import dev.skillbill.designsystem.generated.resources.scaffold_preview_notes
import dev.skillbill.designsystem.generated.resources.scaffold_preview_planned_files
import dev.skillbill.designsystem.generated.resources.scaffold_preview_symlinks
import dev.skillbill.designsystem.generated.resources.scaffold_remove_layer
import dev.skillbill.designsystem.generated.resources.scaffold_repo_partially_mutated_badge
import dev.skillbill.designsystem.generated.resources.scaffold_required
import dev.skillbill.designsystem.generated.resources.scaffold_required_cd
import dev.skillbill.designsystem.generated.resources.scaffold_rollback_incomplete
import dev.skillbill.designsystem.generated.resources.scaffold_run
import dev.skillbill.designsystem.generated.resources.scaffold_running
import dev.skillbill.designsystem.generated.resources.scaffold_scope
import dev.skillbill.designsystem.generated.resources.scaffold_select_kind_cd
import dev.skillbill.designsystem.generated.resources.scaffold_show_manifest_yaml
import dev.skillbill.designsystem.generated.resources.scaffold_skill_name
import dev.skillbill.designsystem.generated.resources.scaffold_skill_name_helper
import dev.skillbill.designsystem.generated.resources.scaffold_succeeded
import dev.skillbill.designsystem.generated.resources.scaffold_title_prefix
import dev.skillbill.designsystem.generated.resources.scaffold_validation_title
import dev.skillbill.designsystem.generated.resources.scaffold_wizard_kind
import org.jetbrains.compose.resources.stringResource
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.domain.model.BaselineReviewSkillOption
import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerForm
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.model.ScaffoldValidationId
import skillbill.desktop.core.domain.model.ScaffoldValidationMessage
import skillbill.desktop.core.domain.model.ScaffoldWizardFormFields
import skillbill.desktop.core.domain.model.ScaffoldWizardState

private const val SKILL_NAME_PREFIX = "bill-"

/**
 * Public callbacks the dialog needs to drive the view model. Hoisting the callbacks here keeps
 * `SkillBillFrame` callers from passing >40 parameters to the frame; the route builds this struct
 * once and forwards.
 */
data class ScaffoldWizardCallbacks(
  val onSelectKind: (ScaffoldKind) -> Unit,
  val onFormChanged: ((ScaffoldWizardFormFields) -> ScaffoldWizardFormFields) -> Unit,
  val onAddBaselineLayer: () -> Unit,
  val onAddSuggestedBaselineLayer: () -> Unit,
  val onEditBaselineLayer: (Int, (ScaffoldBaselineLayerForm) -> ScaffoldBaselineLayerForm) -> Unit,
  val onRemoveBaselineLayer: (Int) -> Unit,
  val onDirtyOverrideChanged: (Boolean) -> Unit,
  val onPlan: () -> Unit,
  val onRun: () -> Unit,
  val onAcknowledgeFailure: () -> Unit,
  val onDismiss: () -> Unit,
)

@Composable
fun ScaffoldWizardDialog(
  state: ScaffoldWizardState,
  canStartScaffoldAction: Boolean,
  callbacks: ScaffoldWizardCallbacks,
) {
  val semanticTones = SkillBillTheme.semanticTones
  val dialogContentDescription = stringResource(Res.string.scaffold_dialog_cd)
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(semanticTones.scrim)
      .semantics { contentDescription = dialogContentDescription }
      // Pointer-only outside-tap dismiss. Do NOT use clickable + Role.Button here: that makes the
      // scrim keyboard-focusable, and pressing Enter/Space while focus has drifted to it fires
      // onDismiss mid-typing.
      .pointerInput(Unit) { detectTapGestures { callbacks.onDismiss() } },
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .widthIn(min = SkillBillDimens.dialogMinWidth, max = SkillBillDimens.dialogMaxWidth)
        .heightIn(max = SkillBillDimens.dialogMaxHeight)
        .clip(SkillBillTheme.shapes.medium)
        .border(SkillBillDimens.hairline, semanticTones.dialog.border, SkillBillTheme.shapes.medium)
        .background(semanticTones.dialog.container)
        // Block dismiss-on-outside-tap when the user clicks inside the panel.
        .pointerInput(Unit) { detectTapGestures { /* consume */ } },
    ) {
      WizardHeader(kind = state.kind, onDismiss = callbacks.onDismiss)
      HorizontalDivider(color = semanticTones.dialog.border)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = SkillBillDimens.pad5xl, vertical = SkillBillDimens.pad3xl),
        verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacing2xl),
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
        if (state.validationErrors.isNotEmpty()) {
          ValidationBanner(state.validationErrors)
        }
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
      HorizontalDivider(color = semanticTones.dialog.border)
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
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(SkillBillMetrics.dialogHeaderHeight)
      .padding(horizontal = SkillBillDimens.pad4xl),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
  ) {
    Text(
      text = stringResource(Res.string.scaffold_title_prefix, kind.displayLabel),
      color = dialogTone.content,
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.weight(1f),
    )
    val dismissCd = stringResource(Res.string.scaffold_dismiss_cd)
    Text(
      text = "x",
      color = colors.onSurfaceVariant,
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier
        .semantics { contentDescription = dismissCd }
        .clickable(role = Role.Button, onClick = onDismiss)
        .padding(horizontal = SkillBillDimens.padMd, vertical = SkillBillDimens.padXs),
    )
  }
}

@Composable
private fun KindPicker(selected: ScaffoldKind, onSelect: (ScaffoldKind) -> Unit, enabled: Boolean) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd)) {
    SectionLabel(stringResource(Res.string.scaffold_wizard_kind))
    Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd)) {
      ScaffoldKind.activeCreationValues().forEach { kind ->
        val isSelected = kind == selected
        val backgroundColor = if (isSelected) colors.primary else colors.surfaceVariant
        val foregroundColor = when {
          !enabled -> colors.onSurfaceVariant
          isSelected -> colors.onPrimary
          else -> dialogTone.content
        }
        val kindCd = stringResource(Res.string.scaffold_select_kind_cd, kind.displayLabel)
        Text(
          text = kind.displayLabel,
          color = foregroundColor,
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier
            .clip(SkillBillComponentShapes.control)
            .border(SkillBillDimens.hairline, dialogTone.border, SkillBillComponentShapes.control)
            .background(backgroundColor)
            .semantics { contentDescription = kindCd }
            .clickable(enabled = enabled, role = Role.Button) { onSelect(kind) }
            .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.padMd),
        )
      }
    }
  }
}

@Composable
private fun DirtyRepoWarning(override: Boolean, enabled: Boolean, onOverrideChanged: (Boolean) -> Unit) {
  val tone = SkillBillTheme.semanticTones.warningBanner
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  val acknowledgeCd = stringResource(Res.string.scaffold_ack_dirty_repo_cd)
  val checkedDescription = stringResource(Res.string.confirm_deletion_state_checked)
  val uncheckedDescription = stringResource(Res.string.confirm_deletion_state_unchecked)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, tone.border, SkillBillComponentShapes.control)
      .background(tone.container)
      .padding(horizontal = SkillBillDimens.pad2xl, vertical = SkillBillDimens.padXl),
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
  ) {
    Text(
      text = stringResource(Res.string.scaffold_dirty_repo_heading),
      color = tone.content,
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      text = stringResource(Res.string.scaffold_dirty_repo_detail),
      color = colors.onSurfaceVariant,
      style = SkillBillTypeStyles.codeCaption,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
      modifier = Modifier
        .toggleable(
          value = override,
          enabled = enabled,
          role = Role.Checkbox,
          onValueChange = onOverrideChanged,
        )
        .semantics {
          contentDescription = acknowledgeCd
          stateDescription = if (override) checkedDescription else uncheckedDescription
        },
    ) {
      Text(text = if (override) "[x]" else "[ ]", color = tone.content, style = MaterialTheme.typography.bodySmall)
      Text(
        text = stringResource(Res.string.scaffold_dirty_repo_ack_label),
        color = dialogTone.content,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
private fun WizardForm(state: ScaffoldWizardState, callbacks: ScaffoldWizardCallbacks) {
  val fields = state.formFields
  when (state.kind) {
    ScaffoldKind.HORIZONTAL_SKILL -> {
      PrefixedTextFieldRow(
        label = stringResource(Res.string.scaffold_skill_name),
        prefix = SKILL_NAME_PREFIX,
        value = fields.name,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(name = value) }
        },
      )
      Text(
        text = stringResource(Res.string.scaffold_skill_name_helper),
        color = SkillBillTheme.colors.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
      )
      TextFieldRow(
        label = stringResource(Res.string.scaffold_description),
        value = fields.description,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(description = value) }
        },
      )
    }
    ScaffoldKind.PLATFORM_PACK -> {
      PresetPicker(
        label = stringResource(Res.string.scaffold_platform_preset),
        options = state.optionCatalog.platformPackPresets.map { it.platform to it.displayName },
        selected = fields.platform,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(platform = value, displayName = it.displayName) }
        },
      )
      TextFieldRow(
        label = stringResource(Res.string.scaffold_platform_slug),
        value = fields.platform,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(platform = value) }
        },
      )
      TextFieldRow(
        label = stringResource(Res.string.scaffold_display_name),
        value = fields.displayName,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(displayName = value) }
        },
      )
      BaselineLayerControls(state = state, callbacks = callbacks)
    }
    ScaffoldKind.PLATFORM_OVERRIDE_PILOTED -> {
      PresetPicker(
        label = stringResource(Res.string.scaffold_platform),
        options = state.optionCatalog.pilotedPlatformPacks.map { it.platform to it.displayName },
        selected = fields.platform,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(platform = value) }
        },
      )
      PresetPicker(
        label = stringResource(Res.string.scaffold_family),
        options = (state.optionCatalog.shelledFamilies + state.optionCatalog.preShellFamilies)
          .map { family -> family to family },
        selected = fields.family,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(family = value) }
        },
      )
      TextFieldRow(
        label = stringResource(Res.string.scaffold_description),
        value = fields.description,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(description = value) }
        },
      )
    }
    ScaffoldKind.CODE_REVIEW_AREA -> {
      PresetPicker(
        label = stringResource(Res.string.scaffold_platform),
        options = state.optionCatalog.pilotedPlatformPacks.map { it.platform to it.displayName },
        selected = fields.platform,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(platform = value) }
        },
      )
      PresetPicker(
        label = stringResource(Res.string.scaffold_code_review_area),
        options = state.optionCatalog.approvedCodeReviewAreas.map { area -> area to area },
        selected = fields.area,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(area = value) }
        },
      )
      TextFieldRow(
        label = stringResource(Res.string.scaffold_description),
        value = fields.description,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(description = value) }
        },
      )
    }
    ScaffoldKind.ADD_ON -> {
      TextFieldRow(
        label = stringResource(Res.string.scaffold_add_on_name),
        value = fields.name,
        enabled = !state.busy,
        onValueChanged = { value ->
          callbacks.onFormChanged { it.copy(name = value) }
        },
      )
      PresetPicker(
        label = stringResource(Res.string.scaffold_owning_platform_pack),
        options = state.optionCatalog.pilotedPlatformPacks.map { it.platform to it.displayName },
        selected = fields.platform,
        enabled = !state.busy,
        onSelected = { value ->
          callbacks.onFormChanged { it.copy(platform = value) }
        },
      )
    }
  }
}

@Composable
private fun BaselineLayerControls(state: ScaffoldWizardState, callbacks: ScaffoldWizardCallbacks) {
  val fields = state.formFields
  val hasBaselinePacks = state.optionCatalog.baselineReviewPacks.isNotEmpty()
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
    SectionLabel(stringResource(Res.string.scaffold_baseline_section))
    if (fields.baselineLayers.isEmpty()) {
      Text(
        text = stringResource(Res.string.scaffold_no_baseline_layers),
        color = SkillBillTheme.colors.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    fields.baselineLayers.forEachIndexed { index, layer ->
      key(layer.rowId) {
        BaselineLayerEditor(
          index = index,
          layer = layer,
          state = state,
          callbacks = callbacks,
        )
      }
    }
    if (!hasBaselinePacks) {
      Text(
        text = stringResource(Res.string.scaffold_no_baseline_packs),
        color = SkillBillTheme.colors.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
      InlineButton(
        label = stringResource(Res.string.scaffold_add_layer),
        enabled = !state.busy && hasBaselinePacks,
        onClick = callbacks.onAddBaselineLayer,
      )
      if (state.baselineLayerSuggestion != null) {
        val suggestedLabel = stringResource(Res.string.scaffold_add_suggested_prefix) + " " +
          state.baselineLayerSuggestionLabel.orEmpty()
        InlineButton(
          label = suggestedLabel,
          enabled = !state.busy,
          onClick = callbacks.onAddSuggestedBaselineLayer,
        )
      }
    }
  }
}

@Composable
private fun BaselineLayerEditor(
  index: Int,
  layer: ScaffoldBaselineLayerForm,
  state: ScaffoldWizardState,
  callbacks: ScaffoldWizardCallbacks,
) {
  val pack = state.optionCatalog.baselineReviewPacks.firstOrNull { it.platform == layer.platform }
  val skill = pack?.skills?.firstOrNull { it.name == layer.skill }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, SkillBillTheme.semanticTones.dialog.border, SkillBillComponentShapes.control)
      .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.padLg),
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
    ) {
      Text(
        text = stringResource(Res.string.scaffold_layer_number, index + 1),
        color = SkillBillTheme.semanticTones.dialog.content,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f),
      )
      InlineButton(
        label = stringResource(Res.string.scaffold_remove_layer),
        enabled = !state.busy,
        onClick = { callbacks.onRemoveBaselineLayer(index) },
      )
    }
    PresetPicker(
      label = stringResource(Res.string.scaffold_baseline_pack),
      options = state.optionCatalog.baselineReviewPacks.map { option -> option.platform to option.displayName },
      selected = layer.platform,
      enabled = !state.busy,
      onSelected = { platform ->
        val nextPack = state.optionCatalog.baselineReviewPacks.firstOrNull { it.platform == platform }
        val nextSkill = nextPack?.skills?.firstOrNull()
        callbacks.onEditBaselineLayer(index) {
          it.copy(
            platform = platform,
            skill = nextSkill?.name.orEmpty(),
            mode = nextSkill?.supportedModes?.firstOrNull().orEmpty(),
            scope = nextSkill?.supportedScopes?.firstOrNull() ?: ScaffoldBaselineLayerForm.DEFAULT_SCOPE,
          )
        }
      },
    )
    PresetPicker(
      label = stringResource(Res.string.scaffold_baseline_skill),
      options = pack?.skills.orEmpty().map { option -> option.name to option.name },
      selected = layer.skill,
      enabled = !state.busy && pack != null,
      onSelected = { skillName ->
        val nextSkill = pack?.skills?.firstOrNull { it.name == skillName }
        callbacks.onEditBaselineLayer(index) {
          it.copy(
            skill = skillName,
            mode = nextSkill?.supportedModes?.firstOrNull().orEmpty(),
            scope = nextSkill?.supportedScopes?.firstOrNull() ?: ScaffoldBaselineLayerForm.DEFAULT_SCOPE,
          )
        }
      },
    )
    ModeAndScopeRow(
      index = index,
      layer = layer,
      skill = skill,
      enabled = !state.busy && skill != null,
      callbacks = callbacks,
    )
  }
}

@Composable
private fun ModeAndScopeRow(
  index: Int,
  layer: ScaffoldBaselineLayerForm,
  skill: BaselineReviewSkillOption?,
  enabled: Boolean,
  callbacks: ScaffoldWizardCallbacks,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg), verticalAlignment = Alignment.Top) {
    Box(modifier = Modifier.weight(1f)) {
      PresetPicker(
        label = stringResource(Res.string.scaffold_mode),
        options = skill?.supportedModes.orEmpty().map { mode -> mode to mode },
        selected = layer.mode,
        enabled = enabled,
        onSelected = { mode ->
          callbacks.onEditBaselineLayer(index) { it.copy(mode = mode) }
        },
      )
    }
    Box(modifier = Modifier.weight(1f)) {
      PresetPicker(
        label = stringResource(Res.string.scaffold_scope),
        options = skill?.supportedScopes.orEmpty().map { scope -> scope to scope },
        selected = layer.scope,
        enabled = enabled,
        onSelected = { scope ->
          callbacks.onEditBaselineLayer(index) { it.copy(scope = scope) }
        },
      )
    }
    RequiredToggle(
      required = layer.required,
      enabled = enabled,
      onToggle = {
        callbacks.onEditBaselineLayer(index) { it.copy(required = !it.required) }
      },
    )
  }
}

@Composable
private fun RequiredToggle(required: Boolean, enabled: Boolean, onToggle: () -> Unit) {
  val requiredCd = stringResource(Res.string.scaffold_required_cd)
  Column(
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    SectionLabel(stringResource(Res.string.scaffold_required))
    Checkbox(
      checked = required,
      enabled = enabled,
      onCheckedChange = { onToggle() },
      modifier = Modifier.semantics {
        contentDescription = requiredCd
      },
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    color = SkillBillTheme.colors.onSurfaceVariant,
    style = SkillBillTypeStyles.codeCaption.copy(fontFamily = FontFamily.Monospace),
  )
}

@Composable
private fun TextFieldRow(label: String, value: String, enabled: Boolean, onValueChanged: (String) -> Unit) {
  val textFieldTokens = SkillBillTheme.textFieldTokens
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  val borderColor = when {
    !enabled -> textFieldTokens.disabledBorder
    focused -> textFieldTokens.focusedBorder
    else -> textFieldTokens.border
  }
  val textColor = if (enabled) textFieldTokens.text else textFieldTokens.disabledText
  val containerColor = if (enabled) textFieldTokens.container else textFieldTokens.disabledContainer
  val inputCd = stringResource(Res.string.scaffold_label_input_cd, label)
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm)) {
    SectionLabel(label)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(SkillBillDimens.controlHeightLg)
        .clip(SkillBillComponentShapes.control)
        .border(SkillBillDimens.hairline, borderColor, SkillBillComponentShapes.control)
        .background(containerColor)
        .padding(horizontal = SkillBillDimens.padLg, vertical = SkillBillDimens.padMd)
        .semantics { contentDescription = inputCd },
    ) {
      BasicTextField(
        value = value,
        onValueChange = onValueChanged,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
          fontFamily = FontFamily.Monospace,
          color = textColor,
        ),
        cursorBrush = SolidColor(textFieldTokens.cursor),
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun PrefixedTextFieldRow(
  label: String,
  prefix: String,
  value: String,
  enabled: Boolean,
  onValueChanged: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val textFieldTokens = SkillBillTheme.textFieldTokens
  val interactionSource = remember { MutableInteractionSource() }
  val focused by interactionSource.collectIsFocusedAsState()
  val borderColor = when {
    !enabled -> textFieldTokens.disabledBorder
    focused -> textFieldTokens.focusedBorder
    else -> textFieldTokens.border
  }
  val textColor = if (enabled) textFieldTokens.text else textFieldTokens.disabledText
  val containerColor = if (enabled) textFieldTokens.container else textFieldTokens.disabledContainer
  val focusRequester = remember { FocusRequester() }
  val rowClickInteractionSource = remember { MutableInteractionSource() }
  val prefixFieldCd = stringResource(Res.string.scaffold_prefix_field_cd, label, prefix)
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm)) {
    SectionLabel(label)
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm),
      modifier = Modifier
        .fillMaxWidth()
        .height(SkillBillDimens.controlHeightLg)
        .clip(SkillBillComponentShapes.control)
        .border(SkillBillDimens.hairline, borderColor, SkillBillComponentShapes.control)
        .background(containerColor)
        .clickable(
          interactionSource = rowClickInteractionSource,
          indication = null,
        ) { focusRequester.requestFocus() }
        .padding(horizontal = SkillBillDimens.padLg, vertical = SkillBillDimens.padMd)
        .semantics(mergeDescendants = true) { contentDescription = prefixFieldCd },
    ) {
      Text(
        text = prefix,
        color = SkillBillTheme.colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      )
      BasicTextField(
        value = value,
        onValueChange = { raw -> onValueChanged(raw.removePrefix(prefix)) },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
          fontFamily = FontFamily.Monospace,
          color = textColor,
        ),
        cursorBrush = SolidColor(textFieldTokens.cursor),
        interactionSource = interactionSource,
        modifier = Modifier
          .weight(1f)
          .focusRequester(focusRequester)
          .testTag("scaffold.skillName.field")
          .semantics { editableText = AnnotatedString("$prefix$value") },
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
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm)) {
    SectionLabel(label)
    if (options.isEmpty()) {
      Text(
        text = stringResource(Res.string.scaffold_no_options),
        color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    } else {
      Row(
        horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
      ) {
        options.forEach { (value, display) ->
          val isSelected = value == selected
          val backgroundColor = if (isSelected) colors.primary else colors.surfaceVariant
          val foregroundColor = when {
            !enabled -> colors.onSurfaceVariant
            isSelected -> colors.onPrimary
            else -> dialogTone.content
          }
          val optionCd = stringResource(Res.string.scaffold_option_cd, label, display)
          Text(
            text = display,
            color = foregroundColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
              .clip(SkillBillComponentShapes.control)
              .border(SkillBillDimens.hairline, dialogTone.border, SkillBillComponentShapes.control)
              .background(backgroundColor)
              .semantics { contentDescription = optionCd }
              .clickable(enabled = enabled, role = Role.Button) { onSelected(value) }
              .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.padMd),
          )
        }
      }
    }
  }
}

@Composable
private fun PlanPreview(plan: skillbill.desktop.core.domain.model.ScaffoldPlan) {
  val tone = SkillBillTheme.semanticTones.successBanner
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, tone.border, SkillBillComponentShapes.control)
      .background(tone.container)
      .padding(horizontal = SkillBillDimens.pad2xl, vertical = SkillBillDimens.padXl),
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
  ) {
    Text(
      text = stringResource(Res.string.scaffold_dry_run_plan),
      color = tone.content,
      style = MaterialTheme.typography.bodySmall,
    )
    PreviewSection(label = stringResource(Res.string.scaffold_preview_planned_files), lines = plan.createdFiles)
    PreviewSection(label = stringResource(Res.string.scaffold_preview_manifest_edits), lines = plan.manifestEdits)
    ManifestPreviewSection(plan.manifestPreviews)
    PreviewSection(label = stringResource(Res.string.scaffold_preview_symlinks), lines = plan.symlinks)
    PreviewSection(label = stringResource(Res.string.scaffold_preview_install_targets), lines = plan.installTargets)
    PreviewSection(label = stringResource(Res.string.scaffold_preview_notes), lines = plan.notes)
  }
}

@Composable
private fun ManifestPreviewSection(previews: List<skillbill.desktop.core.domain.model.ManifestEditPreview>) {
  if (previews.isEmpty()) return
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm)) {
    Text(
      text = stringResource(Res.string.scaffold_manifest_edit_previews),
      color = colors.onSurfaceVariant,
      style = SkillBillTypeStyles.codeCaption.copy(fontFamily = FontFamily.Monospace),
    )
    previews.forEach { preview ->
      var expanded by remember(preview.path) { mutableStateOf(false) }
      Text(
        text = preview.path,
        color = dialogTone.content,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      InlineButton(
        label = if (expanded) {
          stringResource(Res.string.scaffold_hide_manifest_yaml)
        } else {
          stringResource(Res.string.scaffold_show_manifest_yaml)
        },
        enabled = true,
        onClick = { expanded = !expanded },
      )
      if (expanded) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(SkillBillComponentShapes.previewConsole)
            .background(SkillBillTheme.colors.background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.padLg),
        ) {
          Text(
            text = preview.content,
            color = SkillBillTheme.colors.onBackground,
            style = SkillBillTypeStyles.codeCaption.copy(fontFamily = FontFamily.Monospace),
          )
        }
      }
    }
  }
}

@Composable
private fun PreviewSection(label: String, lines: List<String>) {
  if (lines.isEmpty()) return
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXs)) {
    Text(
      text = label,
      color = colors.onSurfaceVariant,
      style = SkillBillTypeStyles.codeCaption.copy(fontFamily = FontFamily.Monospace),
    )
    lines.forEach { line ->
      Text(
        text = line,
        color = dialogTone.content,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ValidationBanner(errors: List<ScaffoldValidationMessage>) {
  val tone = SkillBillTheme.semanticTones.warningBanner
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, tone.border, SkillBillComponentShapes.control)
      .background(tone.container)
      .padding(horizontal = SkillBillDimens.pad2xl, vertical = SkillBillDimens.padXl),
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm),
  ) {
    Text(
      text = stringResource(Res.string.scaffold_validation_title),
      color = tone.content,
      style = MaterialTheme.typography.bodySmall,
    )
    errors.forEach { error ->
      Text(
        text = scaffoldValidationText(error),
        color = tone.content,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
private fun scaffoldValidationText(message: ScaffoldValidationMessage): String {
  val resource = when (message.id) {
    ScaffoldValidationId.SKILL_NAME_REQUIRED -> Res.string.scaffold_error_skill_name_required
    ScaffoldValidationId.PLATFORM_SLUG_REQUIRED -> Res.string.scaffold_error_platform_slug_required
    ScaffoldValidationId.PLATFORM_REQUIRED -> Res.string.scaffold_error_platform_required
    ScaffoldValidationId.FAMILY_REQUIRED -> Res.string.scaffold_error_family_required
    ScaffoldValidationId.CODE_REVIEW_AREA_REQUIRED -> Res.string.scaffold_error_code_review_area_required
    ScaffoldValidationId.ADD_ON_NAME_REQUIRED -> Res.string.scaffold_error_add_on_name_required
    ScaffoldValidationId.OWNING_PLATFORM_PACK_REQUIRED -> Res.string.scaffold_error_owning_platform_pack_required
    ScaffoldValidationId.BASELINE_PACK_REQUIRED -> Res.string.scaffold_error_baseline_pack_required
    ScaffoldValidationId.BASELINE_PACK_UNAVAILABLE -> Res.string.scaffold_error_baseline_pack_unavailable
    ScaffoldValidationId.BASELINE_SKILL_REQUIRED -> Res.string.scaffold_error_baseline_skill_required
    ScaffoldValidationId.BASELINE_SKILL_UNAVAILABLE -> Res.string.scaffold_error_baseline_skill_unavailable
    ScaffoldValidationId.BASELINE_MODE_UNSUPPORTED -> Res.string.scaffold_error_baseline_mode_unsupported
    ScaffoldValidationId.BASELINE_SCOPE_UNSUPPORTED -> Res.string.scaffold_error_baseline_scope_unsupported
    ScaffoldValidationId.DUPLICATE_BASELINE_LAYER -> Res.string.scaffold_error_duplicate_baseline_layer
    ScaffoldValidationId.BASELINE_SELF_REFERENCE -> Res.string.scaffold_error_baseline_self_reference
    ScaffoldValidationId.BASELINE_COMPOSITION_CYCLE -> Res.string.scaffold_error_baseline_composition_cycle
  }
  return stringResource(resource, *message.args.toTypedArray())
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
  val tone = SkillBillTheme.semanticTones.successBanner
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, tone.border, SkillBillComponentShapes.control)
      .background(tone.container)
      .padding(horizontal = SkillBillDimens.pad2xl, vertical = SkillBillDimens.padXl),
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm),
  ) {
    Text(
      text = stringResource(Res.string.scaffold_succeeded, result.result.skillName),
      color = tone.content,
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      text = result.result.skillPath,
      color = SkillBillTheme.semanticTones.dialog.content,
      style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
  }
}

@Composable
private fun FailureConsole(result: ScaffoldRunResult.Failed) {
  val tone = if (result.rollbackComplete) {
    SkillBillTheme.semanticTones.errorBanner
  } else {
    SkillBillTheme.semanticTones.warningBanner
  }
  // F-103: partial mutation MUST be conveyed without depending on color alone. We add a visible
  // text badge, a leading warning glyph, and an explicit semantics contentDescription so the
  // banner is readable to color-blind users and assistive tech.
  val bannerSemantics = if (result.rollbackComplete) {
    stringResource(Res.string.scaffold_failed_rolled_back_cd)
  } else {
    stringResource(Res.string.scaffold_failed_partial_cd)
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, tone.border, SkillBillComponentShapes.control)
      .background(tone.container)
      .padding(horizontal = SkillBillDimens.pad2xl, vertical = SkillBillDimens.padXl)
      .semantics { contentDescription = bannerSemantics },
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingSm),
  ) {
    if (!result.rollbackComplete) {
      // Visible badge + leading glyph so the banner reads as visually distinct without depending
      // on color. Placed above the title so it is the first text scanned.
      Text(
        text = stringResource(Res.string.scaffold_repo_partially_mutated_badge),
        color = tone.content,
        style = SkillBillTypeStyles.monoBadge,
      )
    }
    Text(
      text = if (result.rollbackComplete) {
        stringResource(Res.string.scaffold_failed)
      } else {
        stringResource(Res.string.scaffold_failed_partial)
      },
      color = tone.content,
      style = MaterialTheme.typography.bodySmall,
    )
    if (!result.rollbackComplete) {
      Text(
        text = stringResource(Res.string.scaffold_rollback_incomplete),
        color = tone.content,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(SkillBillComponentShapes.previewConsole)
        .background(SkillBillTheme.colors.background)
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.padLg),
    ) {
      Text(
        text = "${result.exceptionName}: ${result.exceptionMessage}".trim(),
        color = SkillBillTheme.colors.onBackground,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
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
      .height(SkillBillMetrics.footerHeight)
      .padding(horizontal = SkillBillDimens.pad4xl),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
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
      val acknowledgeLabel = if (partialMutationLock) {
        stringResource(
          Res.string.scaffold_acknowledge_partial_mutation,
        )
      } else {
        stringResource(Res.string.scaffold_dismiss_error)
      }
      FooterButton(
        label = acknowledgeLabel,
        enabled = !state.busy,
        primary = false,
        onClick = onAcknowledgeFailure,
      )
    }
    FooterButton(
      label = stringResource(Res.string.scaffold_cancel),
      enabled = !state.busy,
      primary = false,
      onClick = onDismiss,
    )
    FooterButton(
      label = if (state.busy) {
        stringResource(
          Res.string.scaffold_planning,
        )
      } else {
        stringResource(Res.string.scaffold_plan_dry_run)
      },
      enabled = planEnabled,
      primary = false,
      onClick = onPlan,
    )
    FooterButton(
      label = if (state.busy) stringResource(Res.string.scaffold_running) else stringResource(Res.string.scaffold_run),
      enabled = runEnabled,
      primary = true,
      onClick = onRun,
    )
  }
}

@Composable
private fun InlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  Text(
    text = label,
    color = if (enabled) dialogTone.content else colors.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
    modifier = Modifier
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, dialogTone.border, SkillBillComponentShapes.control)
      .background(colors.surfaceVariant)
      .semantics { contentDescription = label }
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.padMd),
  )
}

@Composable
private fun FooterButton(label: String, enabled: Boolean, primary: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  val background = when {
    !enabled -> colors.surfaceVariant
    primary -> colors.primary
    else -> colors.surfaceVariant
  }
  val foreground = when {
    !enabled -> colors.onSurfaceVariant
    primary -> colors.onPrimary
    else -> dialogTone.content
  }
  Text(
    text = label,
    color = foreground,
    style = if (primary) MaterialTheme.typography.bodySmall else SkillBillTypeStyles.bodySmallNormal,
    modifier = Modifier
      .clip(SkillBillComponentShapes.control)
      .border(SkillBillDimens.hairline, dialogTone.border, SkillBillComponentShapes.control)
      .background(background)
      .semantics { contentDescription = label }
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = SkillBillDimens.pad2xl, vertical = SkillBillDimens.space7)
      .widthIn(min = SkillBillDimens.footerButtonMinWidth),
  )
}
