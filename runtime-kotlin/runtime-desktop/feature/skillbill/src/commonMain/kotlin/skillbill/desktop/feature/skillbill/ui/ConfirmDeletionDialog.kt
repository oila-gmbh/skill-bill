@file:Suppress("FunctionName", "LongMethod")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.confirm_deletion_acknowledge
import dev.skillbill.designsystem.generated.resources.confirm_deletion_acknowledge_hint
import dev.skillbill.designsystem.generated.resources.confirm_deletion_acknowledge_partial
import dev.skillbill.designsystem.generated.resources.confirm_deletion_agent_symlinks
import dev.skillbill.designsystem.generated.resources.confirm_deletion_busy
import dev.skillbill.designsystem.generated.resources.confirm_deletion_cancel
import dev.skillbill.designsystem.generated.resources.confirm_deletion_cd
import dev.skillbill.designsystem.generated.resources.confirm_deletion_close_cd
import dev.skillbill.designsystem.generated.resources.confirm_deletion_close_glyph
import dev.skillbill.designsystem.generated.resources.confirm_deletion_delete
import dev.skillbill.designsystem.generated.resources.confirm_deletion_deleting
import dev.skillbill.designsystem.generated.resources.confirm_deletion_edit_remove_addon_refs
import dev.skillbill.designsystem.generated.resources.confirm_deletion_edit_remove_area_metadata
import dev.skillbill.designsystem.generated.resources.confirm_deletion_edit_remove_code_review_area
import dev.skillbill.designsystem.generated.resources.confirm_deletion_edit_remove_files_area_entry
import dev.skillbill.designsystem.generated.resources.confirm_deletion_edit_remove_files_baseline
import dev.skillbill.designsystem.generated.resources.confirm_deletion_edit_remove_pointers_key
import dev.skillbill.designsystem.generated.resources.confirm_deletion_edit_remove_quality_check_file
import dev.skillbill.designsystem.generated.resources.confirm_deletion_edit_remove_skill_class_pointer
import dev.skillbill.designsystem.generated.resources.confirm_deletion_failed_partial_title
import dev.skillbill.designsystem.generated.resources.confirm_deletion_failed_recovery
import dev.skillbill.designsystem.generated.resources.confirm_deletion_failed_title
import dev.skillbill.designsystem.generated.resources.confirm_deletion_files_and_dirs
import dev.skillbill.designsystem.generated.resources.confirm_deletion_header
import dev.skillbill.designsystem.generated.resources.confirm_deletion_manifest_edits
import dev.skillbill.designsystem.generated.resources.confirm_deletion_preview_failed
import dev.skillbill.designsystem.generated.resources.confirm_deletion_provider_claude
import dev.skillbill.designsystem.generated.resources.confirm_deletion_provider_codex
import dev.skillbill.designsystem.generated.resources.confirm_deletion_provider_junie
import dev.skillbill.designsystem.generated.resources.confirm_deletion_provider_opencode
import dev.skillbill.designsystem.generated.resources.confirm_deletion_provider_zcode
import dev.skillbill.designsystem.generated.resources.confirm_deletion_readme_decrement_count
import dev.skillbill.designsystem.generated.resources.confirm_deletion_readme_edits
import dev.skillbill.designsystem.generated.resources.confirm_deletion_readme_remove_row
import dev.skillbill.designsystem.generated.resources.confirm_deletion_skills_to_remove
import dev.skillbill.designsystem.generated.resources.confirm_deletion_state_checked
import dev.skillbill.designsystem.generated.resources.confirm_deletion_state_unchecked
import dev.skillbill.designsystem.generated.resources.confirm_deletion_success
import dev.skillbill.designsystem.generated.resources.confirm_deletion_target_platform_pack
import org.jetbrains.compose.resources.stringResource
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillMetrics
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTransparent
import skillbill.desktop.core.domain.model.ConfirmDeletionState
import skillbill.desktop.core.domain.model.DesktopAgentSymlinkProvider
import skillbill.desktop.core.domain.model.DesktopManifestEditKind
import skillbill.desktop.core.domain.model.DesktopReadmeCatalogEditKind
import skillbill.desktop.core.domain.model.DesktopSkillRemovalResult
import skillbill.desktop.core.domain.model.DesktopSkillRemovalTarget

/**
 * SKILL-46: callbacks the dialog routes back into the ViewModel.
 *
 * Hoisting the callbacks here keeps `SkillBillFrame` callers from passing extra parameters per
 * dialog button — mirroring the [ScaffoldWizardCallbacks] pattern.
 */
data class ConfirmDeletionCallbacks(
  val onAcknowledgedChanged: (Boolean) -> Unit,
  val onConfirmDelete: () -> Unit,
  val onDismiss: () -> Unit,
  val onAcknowledgeFailure: () -> Unit,
) {
  companion object {
    fun noop(): ConfirmDeletionCallbacks = ConfirmDeletionCallbacks(
      onAcknowledgedChanged = {},
      onConfirmDelete = {},
      onDismiss = {},
      onAcknowledgeFailure = {},
    )
  }
}

private val NoopClick: () -> Unit = {}

@Composable
fun ConfirmDeletionDialog(state: ConfirmDeletionState, callbacks: ConfirmDeletionCallbacks) {
  // F-603: shared interactionSource + indication=null on surface-level clickable swallows so
  // ripples don't flash on a dialog backdrop or on the panel itself.
  val backdropInteraction = remember { MutableInteractionSource() }
  val panelInteraction = remember { MutableInteractionSource() }
  val semanticTones = SkillBillTheme.semanticTones
  val dialogContentDescription = stringResource(Res.string.confirm_deletion_cd)
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(semanticTones.scrim)
      .semantics { contentDescription = dialogContentDescription }
      // Click outside the panel = dismiss. Mirrors ScaffoldWizardDialog backdrop behavior.
      .clickable(
        interactionSource = backdropInteraction,
        indication = null,
        role = Role.Button,
        onClick = callbacks.onDismiss,
      ),
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .widthIn(min = SkillBillDimens.dialogMinWidth, max = SkillBillDimens.dialogMaxWidth)
        .heightIn(max = SkillBillDimens.dialogMaxHeight)
        .clip(SkillBillTheme.shapes.medium)
        .border(SkillBillDimens.hairline, semanticTones.dialog.border, SkillBillTheme.shapes.medium)
        .background(semanticTones.dialog.container)
        // Swallow clicks inside the panel so the backdrop click doesn't fire.
        .clickable(
          interactionSource = panelInteraction,
          indication = null,
          enabled = false,
          onClick = NoopClick,
        ),
    ) {
      DialogHeader(target = state.target, onDismiss = callbacks.onDismiss)
      HorizontalDivider(color = semanticTones.dialog.border)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = SkillBillDimens.pad5xl, vertical = SkillBillDimens.pad3xl),
        verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacing2xl),
      ) {
        when {
          // F-717: spinner makes "computing" feel like progress, not a hang.
          state.previewBusy -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(SkillBillDimens.iconSm),
              strokeWidth = SkillBillDimens.spacingXs,
              color = SkillBillTheme.colors.primary,
            )
            Text(
              text = stringResource(Res.string.confirm_deletion_busy),
              color = SkillBillTheme.colors.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
          }
          state.preview != null -> RemovalDossier(state)
          state.executionResult is DesktopSkillRemovalResult.Failed -> Text(
            text = stringResource(Res.string.confirm_deletion_preview_failed),
            color = semanticTones.errorBanner.content,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        state.executionResult?.let { result -> ResultBanner(result, callbacks.onAcknowledgeFailure) }
      }
      HorizontalDivider(color = semanticTones.dialog.border)
      DialogFooter(state = state, callbacks = callbacks)
    }
  }
}

@Composable
private fun DialogHeader(target: DesktopSkillRemovalTarget, onDismiss: () -> Unit) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  val closeCd = stringResource(Res.string.confirm_deletion_close_cd)
  Row(
    modifier = Modifier.fillMaxWidth().height(
      SkillBillMetrics.dialogHeaderHeight,
    ).padding(horizontal = SkillBillDimens.pad4xl),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
  ) {
    Text(
      text = stringResource(Res.string.confirm_deletion_header, displayLabelFor(target)),
      color = dialogTone.content,
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = stringResource(Res.string.confirm_deletion_close_glyph),
      color = colors.onSurfaceVariant,
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier
        .clickable(role = Role.Button, onClick = onDismiss)
        .padding(horizontal = SkillBillDimens.padMd, vertical = SkillBillDimens.padSm)
        .semantics { contentDescription = closeCd },
    )
  }
}

@Composable
private fun RemovalDossier(state: ConfirmDeletionState) {
  val preview = state.preview ?: return
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
    if (preview.cascadedSkillNames.isNotEmpty()) {
      SectionHeader(stringResource(Res.string.confirm_deletion_skills_to_remove, preview.cascadedSkillNames.size))
      preview.cascadedSkillNames.forEach { name ->
        DossierLine(name)
      }
    }
    if (preview.filesystemPaths.isNotEmpty()) {
      SectionHeader(stringResource(Res.string.confirm_deletion_files_and_dirs, preview.filesystemPaths.size))
      preview.filesystemPaths.forEach { path -> DossierLine(path) }
    }
    if (preview.manifestEdits.isNotEmpty()) {
      SectionHeader(stringResource(Res.string.confirm_deletion_manifest_edits, preview.manifestEdits.size))
      preview.manifestEdits.forEach { edit ->
        val editKindLabel = displayLabelFor(edit.editKind)
        DossierLine("${edit.manifestPath} — $editKindLabel (${edit.detail})")
      }
    }
    if (preview.agentSymlinkUnlinks.isNotEmpty()) {
      SectionHeader(stringResource(Res.string.confirm_deletion_agent_symlinks, preview.agentSymlinkUnlinks.size))
      preview.agentSymlinkUnlinks.forEach { link ->
        val providerLabel = displayLabelFor(link.provider)
        DossierLine("[$providerLabel] ${link.path}")
      }
    }
    if (preview.readmeCatalogEdits.isNotEmpty()) {
      SectionHeader(stringResource(Res.string.confirm_deletion_readme_edits, preview.readmeCatalogEdits.size))
      preview.readmeCatalogEdits.forEach { edit ->
        val editKindLabel = displayLabelFor(edit.kind)
        DossierLine("${edit.readmePath} — $editKindLabel (${edit.detail})")
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    color = SkillBillTheme.colors.primary,
    style = MaterialTheme.typography.labelSmall,
    modifier = Modifier.padding(top = SkillBillDimens.padSm),
  )
}

@Composable
private fun DossierLine(text: String) {
  // F-708: long paths scroll horizontally instead of wrapping — matches the F-601 console rule
  // and preserves the bullet+indent alignment for the list.
  Text(
    text = "• $text",
    color = SkillBillTheme.colors.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
    softWrap = false,
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState()),
  )
}

@Composable
private fun ResultBanner(result: DesktopSkillRemovalResult, onAcknowledgeFailure: () -> Unit) {
  when (result) {
    is DesktopSkillRemovalResult.Failed -> {
      val tone = SkillBillTheme.semanticTones.errorBanner
      val colors = SkillBillTheme.colors
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(SkillBillComponentShapes.previewConsole)
          // F-710: red border draws the eye to the highest-severity state.
          .border(SkillBillDimens.hairline, tone.border, SkillBillComponentShapes.previewConsole)
          .background(tone.container)
          .padding(SkillBillDimens.padXl),
        verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
      ) {
        Text(
          text = if (result.rollbackComplete) {
            stringResource(Res.string.confirm_deletion_failed_title)
          } else {
            stringResource(Res.string.confirm_deletion_failed_partial_title)
          },
          color = tone.content,
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          text = "${result.exceptionName}: ${result.exceptionMessage}",
          color = colors.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
        if (!result.rollbackComplete) {
          // F-710: recovery instructions so the user knows what to do next.
          Text(
            text = stringResource(Res.string.confirm_deletion_failed_recovery),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        }
        Text(
          text = stringResource(Res.string.confirm_deletion_acknowledge_partial),
          color = SkillBillTheme.colors.primary,
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier
            .clickable(role = Role.Button, onClick = onAcknowledgeFailure)
            .padding(horizontal = SkillBillDimens.padMd, vertical = SkillBillDimens.padSm),
        )
      }
    }
    is DesktopSkillRemovalResult.Success -> {
      val tone = SkillBillTheme.semanticTones.successBanner
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(SkillBillComponentShapes.previewConsole)
          .border(SkillBillDimens.hairline, tone.border, SkillBillComponentShapes.previewConsole)
          .background(tone.container)
          .padding(SkillBillDimens.padXl),
      ) {
        Text(
          text = stringResource(Res.string.confirm_deletion_success, result.removedPaths.size),
          color = tone.content,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    is DesktopSkillRemovalResult.Preview -> Unit
  }
}

@Composable
private fun DialogFooter(state: ConfirmDeletionState, callbacks: ConfirmDeletionCallbacks) {
  // F-714: cluster the acknowledgment + Delete on the right edge so the eye reads
  // "I understand → Delete" in one beat. Cancel sits on the left as the safe escape.
  val checkboxEnabled = state.preview != null && !state.executeBusy && !state.partialMutationLocked
  val deleteEnabled = state.deleteEnabled
  val colors = SkillBillTheme.colors
  val errorTone = SkillBillTheme.semanticTones.errorBanner
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = SkillBillDimens.pad4xl, vertical = SkillBillDimens.pad2xl),
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacing2xl),
    ) {
      Text(
        text = stringResource(Res.string.confirm_deletion_cancel),
        color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
          .clickable(role = Role.Button, onClick = callbacks.onDismiss)
          .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.padMd),
      )
      Spacer(modifier = Modifier.weight(1f))
      AcknowledgmentCheckbox(
        checked = state.acknowledged,
        enabled = checkboxEnabled,
        onCheckedChange = callbacks.onAcknowledgedChanged,
      )
      Text(
        text = if (state.executeBusy) {
          stringResource(
            Res.string.confirm_deletion_deleting,
          )
        } else {
          stringResource(Res.string.confirm_deletion_delete)
        },
        color = if (deleteEnabled) errorTone.content else colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
          .clickable(enabled = deleteEnabled, role = Role.Button, onClick = callbacks.onConfirmDelete)
          .padding(horizontal = SkillBillDimens.padXl, vertical = SkillBillDimens.padMd)
          // F-607: announce the disabled state to screen readers so users with assistive tech
          // understand why a click is being suppressed.
          .semantics { if (!deleteEnabled) disabled() },
      )
    }
    // F-607: visible caption explains why Delete is disabled when the user hasn't ticked the
    // acknowledgment yet (preview ready + not busy + not locked + not acknowledged).
    if (state.preview != null && !state.acknowledged && !state.executeBusy && !state.partialMutationLocked) {
      Text(
        text = stringResource(Res.string.confirm_deletion_acknowledge_hint),
        color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = SkillBillDimens.padXl),
      )
    }
  }
}

@Composable
private fun AcknowledgmentCheckbox(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
  val colors = SkillBillTheme.colors
  val dialogTone = SkillBillTheme.semanticTones.dialog
  // F-704: switch from manual Row.clickable to Modifier.toggleable so screen readers announce
  // Role.Checkbox and the checked/unchecked state via stateDescription.
  val checkedDescription = stringResource(Res.string.confirm_deletion_state_checked)
  val uncheckedDescription = stringResource(Res.string.confirm_deletion_state_unchecked)
  Row(
    modifier = Modifier
      .toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Checkbox,
        onValueChange = onCheckedChange,
      )
      .semantics {
        stateDescription = if (checked) checkedDescription else uncheckedDescription
      }
      .padding(horizontal = SkillBillDimens.padMd, vertical = SkillBillDimens.padSm),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    Box(
      modifier = Modifier
        // F-704: 18 dp visible checkbox (was 14) — meets a comfortable touch/keyboard target.
        .size(SkillBillDimens.checkboxSize)
        .border(
          SkillBillDimens.hairline,
          if (enabled) colors.primary else colors.onSurfaceVariant,
          SkillBillComponentShapes.checkbox,
        )
        .background(if (checked) colors.primary else SkillBillTransparent),
    )
    Text(
      text = stringResource(Res.string.confirm_deletion_acknowledge),
      color = if (enabled) dialogTone.content else colors.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun displayLabelFor(target: DesktopSkillRemovalTarget): String = when (target) {
  is DesktopSkillRemovalTarget.HorizontalSkill -> target.skillName
  is DesktopSkillRemovalTarget.PlatformPack -> stringResource(
    Res.string.confirm_deletion_target_platform_pack,
    target.platform,
  )
  is DesktopSkillRemovalTarget.AddOn -> target.relativePath
  is DesktopSkillRemovalTarget.ExternalAddOn -> "${target.sourceRootAbsolutePath}/${target.fileName}"
}

@Composable
private fun displayLabelFor(kind: DesktopManifestEditKind): String = when (kind) {
  DesktopManifestEditKind.REMOVE_CODE_REVIEW_AREA -> stringResource(
    Res.string.confirm_deletion_edit_remove_code_review_area,
  )
  DesktopManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE -> stringResource(
    Res.string.confirm_deletion_edit_remove_quality_check_file,
  )
  DesktopManifestEditKind.REMOVE_DECLARED_FILES_AREA_ENTRY -> stringResource(
    Res.string.confirm_deletion_edit_remove_files_area_entry,
  )
  DesktopManifestEditKind.REMOVE_AREA_METADATA_ENTRY -> stringResource(
    Res.string.confirm_deletion_edit_remove_area_metadata,
  )
  DesktopManifestEditKind.REMOVE_DECLARED_FILES_BASELINE -> stringResource(
    Res.string.confirm_deletion_edit_remove_files_baseline,
  )
  DesktopManifestEditKind.REMOVE_POINTERS_BLOCK_KEY -> stringResource(
    Res.string.confirm_deletion_edit_remove_pointers_key,
  )
  DesktopManifestEditKind.REMOVE_ADDON_REFERENCES -> stringResource(Res.string.confirm_deletion_edit_remove_addon_refs)
  DesktopManifestEditKind.REMOVE_SKILL_CLASS_POINTER -> stringResource(
    Res.string.confirm_deletion_edit_remove_skill_class_pointer,
  )
}

@Composable
private fun displayLabelFor(kind: DesktopReadmeCatalogEditKind): String = when (kind) {
  DesktopReadmeCatalogEditKind.REMOVE_CATALOG_ROW -> stringResource(Res.string.confirm_deletion_readme_remove_row)
  DesktopReadmeCatalogEditKind.DECREMENT_SECTION_COUNT -> stringResource(
    Res.string.confirm_deletion_readme_decrement_count,
  )
}

@Composable
private fun displayLabelFor(provider: DesktopAgentSymlinkProvider): String = when (provider) {
  DesktopAgentSymlinkProvider.CLAUDE -> stringResource(Res.string.confirm_deletion_provider_claude)
  DesktopAgentSymlinkProvider.CODEX -> stringResource(Res.string.confirm_deletion_provider_codex)
  DesktopAgentSymlinkProvider.OPENCODE -> stringResource(Res.string.confirm_deletion_provider_opencode)
  DesktopAgentSymlinkProvider.JUNIE -> stringResource(Res.string.confirm_deletion_provider_junie)
  DesktopAgentSymlinkProvider.ZCODE -> stringResource(Res.string.confirm_deletion_provider_zcode)
}
