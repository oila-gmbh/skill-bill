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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import skillbill.desktop.core.domain.model.ConfirmDeletionState
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

private val DialogBackdrop = Color.Black.copy(alpha = 0.5f)
private val DialogPanel = Color(0xFF15151A)
private val DialogLine = Color(0xFF2A2A31)
private val DialogText = Color(0xFFF6F3E7)
private val DialogMuted = Color(0xFFB7B1A0)
private val DialogSteel = Color(0xFF6F7882)
private val DialogYellow = Color(0xFFF4C430)
private val DialogRed = Color(0xFFFF5F57)
private val DialogGreen = Color(0xFF60D394)
private val DialogRaised = Color(0xFF1B1B22)

@Composable
fun ConfirmDeletionDialog(state: ConfirmDeletionState, callbacks: ConfirmDeletionCallbacks) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(DialogBackdrop)
      .semantics { contentDescription = "Confirm deletion" }
      // Click outside the panel = dismiss. Mirrors ScaffoldWizardDialog backdrop behavior.
      .clickable(role = Role.Button, onClick = callbacks.onDismiss),
  ) {
    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .widthIn(min = 560.dp, max = 760.dp)
        .heightIn(max = 640.dp)
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, DialogLine, RoundedCornerShape(8.dp))
        .background(DialogPanel)
        // Swallow clicks inside the panel so the backdrop click doesn't fire.
        .clickable(enabled = false, onClick = {}),
    ) {
      DialogHeader(target = state.target, onDismiss = callbacks.onDismiss)
      HorizontalDivider(color = DialogLine)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        when {
          state.previewBusy -> Text(
            text = "Computing removal cascade...",
            color = DialogMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
          )
          state.preview != null -> RemovalDossier(state)
          state.executionResult is DesktopSkillRemovalResult.Failed -> Text(
            text = "Preview failed. See banner below.",
            color = DialogRed,
            fontSize = 12.sp,
          )
        }
        state.executionResult?.let { result -> ResultBanner(result, callbacks.onAcknowledgeFailure) }
      }
      HorizontalDivider(color = DialogLine)
      DialogFooter(state = state, callbacks = callbacks)
    }
  }
}

@Composable
private fun DialogHeader(target: DesktopSkillRemovalTarget, onDismiss: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      text = "Delete: ${displayLabelFor(target)}",
      color = DialogText,
      fontSize = 14.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = "x",
      color = DialogSteel,
      fontSize = 14.sp,
      modifier = Modifier
        .clickable(role = Role.Button, onClick = onDismiss)
        .padding(horizontal = 6.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun RemovalDossier(state: ConfirmDeletionState) {
  val preview = state.preview ?: return
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (preview.cascadedSkillNames.isNotEmpty()) {
      SectionHeader("Skills to remove (${preview.cascadedSkillNames.size})")
      preview.cascadedSkillNames.forEach { name ->
        DossierLine(name)
      }
    }
    if (preview.filesystemPaths.isNotEmpty()) {
      SectionHeader("Files and directories (${preview.filesystemPaths.size})")
      preview.filesystemPaths.forEach { path -> DossierLine(path) }
    }
    if (preview.manifestEdits.isNotEmpty()) {
      SectionHeader("Manifest edits (${preview.manifestEdits.size})")
      preview.manifestEdits.forEach { edit ->
        DossierLine("${edit.manifestPath} — ${edit.editKind.name.lowercase()} (${edit.detail})")
      }
    }
    if (preview.agentSymlinkUnlinks.isNotEmpty()) {
      SectionHeader("Agent symlinks (${preview.agentSymlinkUnlinks.size})")
      preview.agentSymlinkUnlinks.forEach { link ->
        DossierLine("[${link.provider.name.lowercase()}] ${link.path}")
      }
    }
    if (preview.readmeCatalogEdits.isNotEmpty()) {
      SectionHeader("README catalog edits (${preview.readmeCatalogEdits.size})")
      preview.readmeCatalogEdits.forEach { edit ->
        DossierLine("${edit.readmePath} — ${edit.kind.name.lowercase()} (${edit.detail})")
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    color = DialogYellow,
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    modifier = Modifier.padding(top = 4.dp),
  )
}

@Composable
private fun DossierLine(text: String) {
  Text(
    text = "• $text",
    color = DialogMuted,
    fontSize = 11.sp,
    fontFamily = FontFamily.Monospace,
  )
}

@Composable
private fun ResultBanner(result: DesktopSkillRemovalResult, onAcknowledgeFailure: () -> Unit) {
  when (result) {
    is DesktopSkillRemovalResult.Failed -> Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(4.dp))
        .background(DialogRaised)
        .padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = if (result.rollbackComplete) "Removal failed" else "Removal failed — repo may be partially mutated",
        color = DialogRed,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
      )
      Text(text = "${result.exceptionName}: ${result.exceptionMessage}", color = DialogMuted, fontSize = 11.sp)
      Text(
        text = "Dismiss banner",
        color = DialogYellow,
        fontSize = 11.sp,
        modifier = Modifier
          .clickable(role = Role.Button, onClick = onAcknowledgeFailure)
          .padding(horizontal = 6.dp, vertical = 4.dp),
      )
    }
    is DesktopSkillRemovalResult.Success -> Text(
      text = "Removal complete — ${result.removedPaths.size} paths removed.",
      color = DialogGreen,
      fontSize = 12.sp,
    )
    is DesktopSkillRemovalResult.Preview -> Unit
  }
}

@Composable
private fun DialogFooter(state: ConfirmDeletionState, callbacks: ConfirmDeletionCallbacks) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    AcknowledgmentCheckbox(
      checked = state.acknowledged,
      enabled = state.preview != null && !state.executeBusy && !state.partialMutationLocked,
      onCheckedChange = callbacks.onAcknowledgedChanged,
    )
    Spacer(modifier = Modifier.weight(1f))
    Text(
      text = "Cancel",
      color = DialogMuted,
      fontSize = 12.sp,
      modifier = Modifier
        .clickable(role = Role.Button, onClick = callbacks.onDismiss)
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    val deleteEnabled = state.deleteEnabled
    Text(
      text = if (state.executeBusy) "Deleting..." else "Delete",
      color = if (deleteEnabled) DialogRed else DialogSteel,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier
        .clickable(enabled = deleteEnabled, role = Role.Button, onClick = callbacks.onConfirmDelete)
        .padding(horizontal = 10.dp, vertical = 6.dp),
    )
  }
}

@Composable
private fun AcknowledgmentCheckbox(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier
      .clickable(enabled = enabled, role = Role.Checkbox, onClick = { onCheckedChange(!checked) })
      .padding(horizontal = 6.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier
        .size(14.dp)
        .border(1.dp, if (enabled) DialogYellow else DialogSteel, RoundedCornerShape(2.dp))
        .background(if (checked) DialogYellow else Color.Transparent),
    )
    Text(
      text = "I understand this is irreversible",
      color = if (enabled) DialogText else DialogSteel,
      fontSize = 12.sp,
    )
  }
}

private fun displayLabelFor(target: DesktopSkillRemovalTarget): String = when (target) {
  is DesktopSkillRemovalTarget.HorizontalSkill -> target.skillName
  is DesktopSkillRemovalTarget.PlatformPack -> "platform pack '${target.platform}'"
  is DesktopSkillRemovalTarget.AddOn -> target.relativePath
}
