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

/**
 * SKILL-46 follow-up F-601/F-715: every user-facing string in [ConfirmDeletionDialog] lives in one
 * place. There is no `stringResource(R.string.…)` surface in this desktop module yet, so a private
 * object is the simplest centralisation — when a localisation layer lands, only this object needs
 * to migrate. Functions are used for templated strings so callers don't have to recompose the
 * template at the call site.
 */
private object ConfirmDeletionStrings {
  const val DIALOG_CONTENT_DESCRIPTION = "Confirm deletion"
  const val CLOSE_BUTTON_CONTENT_DESCRIPTION = "Close confirm deletion dialog"
  const val CLOSE_GLYPH = "x"
  const val PREVIEW_BUSY = "Computing removal cascade..."
  const val PREVIEW_FAILED = "Preview failed. See banner below."
  fun headerTitle(label: String): String = "Delete: $label"
  fun sectionSkillsToRemove(count: Int): String = "Skills to remove ($count)"
  fun sectionFilesAndDirectories(count: Int): String = "Files and directories ($count)"
  fun sectionManifestEdits(count: Int): String = "Manifest edits ($count)"
  fun sectionAgentSymlinks(count: Int): String = "Agent symlinks ($count)"
  fun sectionReadmeCatalogEdits(count: Int): String = "README catalog edits ($count)"
  const val FAILED_TITLE = "Removal failed"
  const val FAILED_PARTIAL_TITLE = "Removal failed — repo may be partially mutated"
  const val FAILED_RECOVERY = "Run `scripts/validate_agent_configs` from the toolbar and inspect " +
    "the Console tab to confirm repo state before retrying."
  const val ACKNOWLEDGE_PARTIAL_MUTATION = "Acknowledge partial mutation"
  fun successBanner(removedCount: Int): String =
    "Removal complete — $removedCount paths removed. Running scripts/validate_agent_configs… " +
      "see the Console tab for results."
  const val CANCEL = "Cancel"
  const val DELETE = "Delete"
  const val DELETING = "Deleting..."
  const val ACKNOWLEDGE_CHECKBOX_LABEL = "I understand this is irreversible"
  const val ACKNOWLEDGE_HINT = "Tick the acknowledgment to enable Delete"
  fun targetLabelPlatformPack(platform: String): String = "platform pack '$platform'"
}

// F-602: a single shared noop lambda for surface-click swallows so we don't allocate on every
// recomposition.
private val NoopClick: () -> Unit = {}

@Composable
fun ConfirmDeletionDialog(state: ConfirmDeletionState, callbacks: ConfirmDeletionCallbacks) {
  // F-603: shared interactionSource + indication=null on surface-level clickable swallows so
  // ripples don't flash on a dialog backdrop or on the panel itself.
  val backdropInteraction = remember { MutableInteractionSource() }
  val panelInteraction = remember { MutableInteractionSource() }
  val semanticTones = SkillBillTheme.semanticTones
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(semanticTones.scrim)
      .semantics { contentDescription = ConfirmDeletionStrings.DIALOG_CONTENT_DESCRIPTION }
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
              text = ConfirmDeletionStrings.PREVIEW_BUSY,
              color = SkillBillTheme.colors.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
          }
          state.preview != null -> RemovalDossier(state)
          state.executionResult is DesktopSkillRemovalResult.Failed -> Text(
            text = ConfirmDeletionStrings.PREVIEW_FAILED,
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
  Row(
    modifier = Modifier.fillMaxWidth().height(
      SkillBillMetrics.dialogHeaderHeight,
    ).padding(horizontal = SkillBillDimens.pad4xl),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
  ) {
    Text(
      text = ConfirmDeletionStrings.headerTitle(displayLabelFor(target)),
      color = dialogTone.content,
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = ConfirmDeletionStrings.CLOSE_GLYPH,
      color = colors.onSurfaceVariant,
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier
        .clickable(role = Role.Button, onClick = onDismiss)
        .padding(horizontal = SkillBillDimens.padMd, vertical = SkillBillDimens.padSm)
        .semantics { contentDescription = ConfirmDeletionStrings.CLOSE_BUTTON_CONTENT_DESCRIPTION },
    )
  }
}

@Composable
private fun RemovalDossier(state: ConfirmDeletionState) {
  val preview = state.preview ?: return
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
    if (preview.cascadedSkillNames.isNotEmpty()) {
      SectionHeader(ConfirmDeletionStrings.sectionSkillsToRemove(preview.cascadedSkillNames.size))
      preview.cascadedSkillNames.forEach { name ->
        DossierLine(name)
      }
    }
    if (preview.filesystemPaths.isNotEmpty()) {
      SectionHeader(ConfirmDeletionStrings.sectionFilesAndDirectories(preview.filesystemPaths.size))
      preview.filesystemPaths.forEach { path -> DossierLine(path) }
    }
    if (preview.manifestEdits.isNotEmpty()) {
      SectionHeader(ConfirmDeletionStrings.sectionManifestEdits(preview.manifestEdits.size))
      preview.manifestEdits.forEach { edit ->
        // F-709: human-readable manifest-edit kind copy instead of enum.name.lowercase().
        DossierLine("${edit.manifestPath} — ${displayLabelFor(edit.editKind)} (${edit.detail})")
      }
    }
    if (preview.agentSymlinkUnlinks.isNotEmpty()) {
      SectionHeader(ConfirmDeletionStrings.sectionAgentSymlinks(preview.agentSymlinkUnlinks.size))
      preview.agentSymlinkUnlinks.forEach { link ->
        // F-709: human-readable provider label instead of enum.name.lowercase().
        DossierLine("[${displayLabelFor(link.provider)}] ${link.path}")
      }
    }
    if (preview.readmeCatalogEdits.isNotEmpty()) {
      SectionHeader(ConfirmDeletionStrings.sectionReadmeCatalogEdits(preview.readmeCatalogEdits.size))
      preview.readmeCatalogEdits.forEach { edit ->
        // F-709: human-readable README edit kind.
        DossierLine("${edit.readmePath} — ${displayLabelFor(edit.kind)} (${edit.detail})")
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
            ConfirmDeletionStrings.FAILED_TITLE
          } else {
            ConfirmDeletionStrings.FAILED_PARTIAL_TITLE
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
            text = ConfirmDeletionStrings.FAILED_RECOVERY,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        }
        Text(
          text = ConfirmDeletionStrings.ACKNOWLEDGE_PARTIAL_MUTATION,
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
          text = ConfirmDeletionStrings.successBanner(result.removedPaths.size),
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
        text = ConfirmDeletionStrings.CANCEL,
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
        text = if (state.executeBusy) ConfirmDeletionStrings.DELETING else ConfirmDeletionStrings.DELETE,
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
        text = ConfirmDeletionStrings.ACKNOWLEDGE_HINT,
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
  Row(
    modifier = Modifier
      .toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Checkbox,
        onValueChange = onCheckedChange,
      )
      .semantics {
        stateDescription = if (checked) "Checked" else "Not checked"
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
      text = ConfirmDeletionStrings.ACKNOWLEDGE_CHECKBOX_LABEL,
      color = if (enabled) dialogTone.content else colors.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

private fun displayLabelFor(target: DesktopSkillRemovalTarget): String = when (target) {
  is DesktopSkillRemovalTarget.HorizontalSkill -> target.skillName
  is DesktopSkillRemovalTarget.PlatformPack -> ConfirmDeletionStrings.targetLabelPlatformPack(target.platform)
  is DesktopSkillRemovalTarget.AddOn -> target.relativePath
}

// F-709: human-readable copy for the user-facing enum dossier rows.
private fun displayLabelFor(kind: DesktopManifestEditKind): String = when (kind) {
  DesktopManifestEditKind.REMOVE_CODE_REVIEW_AREA -> "remove code-review area"
  DesktopManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE -> "remove declared quality-check file"
  DesktopManifestEditKind.REMOVE_DECLARED_FILES_AREA_ENTRY -> "remove declared-files area entry"
  DesktopManifestEditKind.REMOVE_AREA_METADATA_ENTRY -> "remove area-metadata entry"
  DesktopManifestEditKind.REMOVE_DECLARED_FILES_BASELINE -> "remove declared-files baseline"
  DesktopManifestEditKind.REMOVE_POINTERS_BLOCK_KEY -> "remove pointers block key"
  DesktopManifestEditKind.REMOVE_ADDON_REFERENCES -> "remove add-on references"
  DesktopManifestEditKind.REMOVE_SKILL_CLASS_POINTER -> "remove skill-class pointer"
}

private fun displayLabelFor(kind: DesktopReadmeCatalogEditKind): String = when (kind) {
  DesktopReadmeCatalogEditKind.REMOVE_CATALOG_ROW -> "remove catalog row"
  DesktopReadmeCatalogEditKind.DECREMENT_SECTION_COUNT -> "decrement section count"
}

private fun displayLabelFor(provider: DesktopAgentSymlinkProvider): String = when (provider) {
  DesktopAgentSymlinkProvider.CLAUDE -> "Claude"
  DesktopAgentSymlinkProvider.CODEX -> "Codex"
  DesktopAgentSymlinkProvider.OPENCODE -> "OpenCode"
  DesktopAgentSymlinkProvider.JUNIE -> "Junie"
}
