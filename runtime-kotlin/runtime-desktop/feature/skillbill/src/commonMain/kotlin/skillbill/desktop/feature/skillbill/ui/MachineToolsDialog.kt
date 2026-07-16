package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.domain.model.MachineSkillInstallStep
import skillbill.desktop.core.domain.model.MachineToolAction
import skillbill.desktop.core.domain.model.MachineToolDescriptor
import skillbill.desktop.core.domain.model.MachineToolMutationRisk
import skillbill.desktop.core.domain.model.MachineToolsState
import skillbill.desktop.core.domain.model.MachineToolsSurface

@Composable
internal fun MachineToolsDialog(
  state: MachineToolsState,
  onAction: (MachineToolAction) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(dialogTitle(state)) },
    text = {
      when (state.surface) {
        MachineToolsSurface.CATALOG -> ToolCatalog(state, onAction)
        MachineToolsSurface.INSTALL -> InstallWizard(state)
        MachineToolsSurface.MANAGER -> SkillManager(state)
        null -> Unit
      }
    },
    confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } },
    modifier = Modifier.onPreviewKeyEvent {
      if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
        onDismiss()
        true
      } else {
        false
      }
    },
  )
}

private fun dialogTitle(state: MachineToolsState) = when (state.surface) {
  MachineToolsSurface.CATALOG -> "Tools"
  MachineToolsSurface.INSTALL -> "Install skill to agents"
  MachineToolsSurface.MANAGER -> "Manage installed skills"
  null -> "Tools"
}

@Composable
private fun ToolCatalog(state: MachineToolsState, onAction: (MachineToolAction) -> Unit) {
  LazyColumn(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
    items(state.descriptors, key = MachineToolDescriptor::id) { descriptor ->
      val risk = if (descriptor.mutationRisk == MachineToolMutationRisk.MUTATES_MACHINE) {
        "Changes machine skills"
      } else {
        "Read only"
      }
      Card(
        Modifier
          .fillMaxWidth()
          .testTag("machine-tool-${descriptor.id}")
          .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = "${descriptor.title}. ${descriptor.shortDescription}. $risk"
          }
          .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.Spacebar)) {
              if (descriptor.availability.available) onAction(descriptor.action)
              true
            } else {
              false
            }
          }
          .clickable(enabled = descriptor.availability.available) { onAction(descriptor.action) }
          .padding(SkillBillDimens.pad4xl),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacing2xl)) {
          Text(descriptor.marker, style = MaterialTheme.typography.labelLarge)
          Column {
            Text(descriptor.title, style = MaterialTheme.typography.titleMedium)
            Text(descriptor.shortDescription, style = MaterialTheme.typography.bodyMedium)
            Text(descriptor.availability.reason ?: risk, style = MaterialTheme.typography.labelSmall)
          }
        }
      }
    }
  }
}

@Composable
private fun InstallWizard(state: MachineToolsState) {
  val install = state.install
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
    Text("Source · Targets · Preview · Apply · Results", style = MaterialTheme.typography.labelLarge)
    when (install.step) {
      MachineSkillInstallStep.SOURCE -> {
        Text("Choose a SKILL.md file or a directory containing one.")
        install.source?.let {
          Text("${it.skillName} — ${it.description}")
          Text("${it.sourcePath} · ${it.includedFileCount} files · ${it.totalBytes} bytes")
          Text(if (it.validationIssues.isEmpty()) "Valid skill bundle" else it.validationIssues.joinToString())
        }
      }
      MachineSkillInstallStep.TARGETS -> install.targets.forEach {
        Text("${if (it.selected) "[x]" else "[ ]"} ${it.provider} · ${it.path} · ${if (it.detected) "detected" else "not detected"}${it.conflict?.let { issue -> " · $issue" }.orEmpty()}")
      }
      MachineSkillInstallStep.PREVIEW -> {
        Text("Plan ${install.planId ?: "unavailable"}")
        install.preview.forEach { Text("${it.operation} ${it.path} — ${it.detail}") }
        install.warnings.forEach { Text("Warning: $it") }
      }
      MachineSkillInstallStep.APPLYING -> Text("Applying machine-skill mutation…")
      MachineSkillInstallStep.RESULTS -> install.results.forEach { Text("${it.targetId}: ${it.outcome} — ${it.detail}") }
    }
    install.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  }
}

@Composable
private fun SkillManager(state: MachineToolsState) {
  val manager = state.manager
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
    Text("Search: ${manager.query.ifBlank { "all skills" }}")
    Text("Ownership: ${manager.ownershipFilter} · Health: ${manager.healthFilter} · Agent: ${manager.agentFilter ?: "all"}")
    if (manager.loading) Text("Refreshing inventory…")
    manager.rows.forEach { row ->
      Text("${row.name} · ${row.ownership} · ${row.health} · ${row.agents.sorted().joinToString()}")
    }
    manager.detail?.let { detail ->
      Text(detail.name, style = MaterialTheme.typography.titleMedium)
      Text(detail.description)
      Text("${detail.ownership} · ${detail.provenance.joinToString()}")
      Text("Source: ${detail.canonicalManagedSourcePath ?: "unmanaged"}")
      Text("Snapshot: ${detail.activeSnapshotHash ?: "none"} · Content: ${detail.contentIdentity ?: "unknown"}")
      detail.targets.forEach { Text("${it.provider} · ${it.path} · ${it.detectionStatus} · ${it.state}") }
      detail.validationIssues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
      if (detail.ownership == "MANAGED") {
        Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd)) {
          Button(onClick = {}) { Text("Edit") }
          Button(onClick = {}) { Text("Manage agents") }
          Button(onClick = {}) { Text("Reveal source") }
          if (detail.repairAvailable) Button(onClick = {}) { Text("Repair") }
          Button(onClick = {}) { Text("Delete") }
        }
      } else {
        Text("Read-only until adopted. Divergent copies require an authoritative source and replacement targets.")
        Button(onClick = {}) { Text("Adopt") }
      }
    }
    manager.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  }
}
