@file:Suppress("FunctionName")

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.domain.model.MachineSkillHealthFilter
import skillbill.desktop.core.domain.model.MachineSkillInstallStep
import skillbill.desktop.core.domain.model.MachineSkillOwnershipFilter
import skillbill.desktop.core.domain.model.MachineToolAction
import skillbill.desktop.core.domain.model.MachineToolDescriptor
import skillbill.desktop.core.domain.model.MachineToolMutationRisk
import skillbill.desktop.core.domain.model.MachineToolsState
import skillbill.desktop.core.domain.model.MachineToolsSurface

enum class MachineSkillManagerAction { EDIT, MANAGE_AGENTS, REVEAL, REPAIR, DELETE, ADOPT }

data class MachineToolsCallbacks(
  val chooseSource: () -> Unit = {},
  val toggleTarget: (String) -> Unit = {},
  val setInstallStep: (MachineSkillInstallStep) -> Unit = {},
  val preview: () -> Unit = {},
  val apply: () -> Unit = {},
  val retry: () -> Unit = {},
  val acknowledge: () -> Unit = {},
  val updateQuery: (String) -> Unit = {},
  val updateOwnership: (MachineSkillOwnershipFilter) -> Unit = {},
  val updateHealth: (MachineSkillHealthFilter) -> Unit = {},
  val updateAgent: (String?) -> Unit = {},
  val selectSkill: (String) -> Unit = {},
  val managerAction: (MachineSkillManagerAction) -> Unit = {},
  val selectAuthority: (String) -> Unit = {},
  val toggleManagerTarget: (String) -> Unit = {},
  val previewManagerAction: () -> Unit = {},
  val applyManagerAction: () -> Unit = {},
)

@Composable
internal fun MachineToolsDialog(
  state: MachineToolsState,
  onAction: (MachineToolAction) -> Unit,
  onDismiss: () -> Unit,
  callbacks: MachineToolsCallbacks = MachineToolsCallbacks(),
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(dialogTitle(state)) },
    text = {
      when (state.surface) {
        MachineToolsSurface.CATALOG -> ToolCatalog(state, onAction)
        MachineToolsSurface.INSTALL -> InstallWizard(state, callbacks)
        MachineToolsSurface.MANAGER -> SkillManager(state, callbacks)
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
private fun InstallWizard(state: MachineToolsState, callbacks: MachineToolsCallbacks) {
  val install = state.install
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
    Text("Source · Targets · Preview · Apply · Results", style = MaterialTheme.typography.labelLarge)
    when (install.step) {
      MachineSkillInstallStep.SOURCE -> {
        Text("Choose a SKILL.md file or a directory containing one.")
        Button(onClick = callbacks.chooseSource) { Text("Choose source") }
        install.source?.let {
          Text("${it.skillName} — ${it.description}")
          Text("${it.sourcePath} · ${it.includedFileCount} files · ${it.totalBytes} bytes")
          Text(if (it.validationIssues.isEmpty()) "Valid skill bundle" else it.validationIssues.joinToString())
        }
      }
      MachineSkillInstallStep.TARGETS -> install.targets.forEach {
        Row(Modifier.clickable(enabled = it.conflict == null) { callbacks.toggleTarget(it.id) }) {
          Checkbox(
            checked = it.selected,
            onCheckedChange = { _ -> callbacks.toggleTarget(it.id) },
            enabled = it.conflict == null,
          )
          val detection = if (it.detected) "detected" else "not detected"
          val conflict = it.conflict?.let { issue -> " · $issue" }.orEmpty()
          Text("${it.provider} · ${it.path} · $detection$conflict")
        }
      }
      MachineSkillInstallStep.PREVIEW -> {
        Text("Plan ${install.planId ?: "unavailable"}")
        install.preview.forEach { Text("${it.operation} ${it.path} — ${it.detail}") }
        install.warnings.forEach { Text("Warning: $it") }
      }
      MachineSkillInstallStep.APPLYING -> Text(
        "Applying machine-skill mutation…",
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
      )
      MachineSkillInstallStep.RESULTS -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        install.results.forEach { Text("${it.targetId}: ${it.outcome} — ${it.detail}") }
        Button(onClick = callbacks.retry) { Text("Retry") }
      }
    }
    install.error?.let {
      Text(
        it,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
      )
    }
    when (install.step) {
      MachineSkillInstallStep.SOURCE -> Button(
        onClick = { callbacks.setInstallStep(MachineSkillInstallStep.TARGETS) },
        enabled = install.canContinue,
      ) {
        Text("Continue")
      }
      MachineSkillInstallStep.TARGETS -> Button(
        onClick = callbacks.preview,
        enabled = install.canPreview,
      ) {
        Text("Preview")
      }
      MachineSkillInstallStep.PREVIEW -> Button(
        onClick = callbacks.apply,
        enabled = install.planId != null && !state.machineMutationBusy,
      ) {
        Text("Apply")
      }
      else -> Unit
    }
    state.postMortem?.let {
      Text(
        it,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
      )
      Button(onClick = callbacks.acknowledge) { Text("Acknowledge") }
    }
  }
}

@Composable
private fun SkillManager(state: MachineToolsState, callbacks: MachineToolsCallbacks) {
  val manager = state.manager
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg)) {
    OutlinedTextField(value = manager.query, onValueChange = callbacks.updateQuery, label = { Text("Search skills") })
    Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd)) {
      OutlinedButton(onClick = { callbacks.updateOwnership(manager.ownershipFilter.nextFilter()) }) {
        Text("Ownership: ${manager.ownershipFilter}")
      }
      OutlinedButton(onClick = { callbacks.updateHealth(manager.healthFilter.nextFilter()) }) {
        Text("Health: ${manager.healthFilter}")
      }
      val agents = manager.rows.flatMap { it.agents }.distinct().sorted()
      OutlinedButton(onClick = { callbacks.updateAgent(manager.agentFilter.nextAgent(agents)) }) {
        Text("Agent: ${manager.agentFilter ?: "ALL"}")
      }
    }
    if (manager.loading) Text("Refreshing inventory…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    manager.rows.filter { row ->
      row.name.contains(manager.query, ignoreCase = true) &&
        (manager.ownershipFilter == MachineSkillOwnershipFilter.ALL || row.ownership == manager.ownershipFilter.name) &&
        (
          manager.healthFilter == MachineSkillHealthFilter.ALL ||
            (manager.healthFilter == MachineSkillHealthFilter.HEALTHY) == (row.health == "HEALTHY")
          ) &&
        (manager.agentFilter == null || manager.agentFilter in row.agents)
    }.forEach { row ->
      Text(
        "${row.name} · ${row.ownership} · ${row.health} · ${row.agents.sorted().joinToString()}",
        Modifier.clickable { callbacks.selectSkill(row.name) }.semantics { role = Role.Button },
      )
    }
    manager.detail?.let { detail ->
      Text(detail.name, style = MaterialTheme.typography.titleMedium)
      Text(detail.description)
      Text("${detail.ownership} · ${detail.provenance.joinToString()}")
      Text("Source: ${detail.canonicalManagedSourcePath ?: "unmanaged"}")
      Text("Record: ${detail.recordIdentity ?: "none"}")
      Text(
        "Snapshot: ${detail.activeSnapshotHash ?: "none"} · " +
          "Content: ${detail.contentIdentity ?: "divergent or unknown"}",
      )
      detail.targets.forEach {
        Text(
          "${it.provider} · ${it.path} · ${it.detectionStatus} · ${it.state} · " +
            "content ${it.contentIdentity ?: "none"}",
        )
      }
      detail.validationIssues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
      detail.lastMutationResult.forEach { Text("Last mutation ${it.targetId}: ${it.outcome} — ${it.detail}") }
      if (detail.ownership == "MANAGED") {
        Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd)) {
          Button(onClick = { callbacks.managerAction(MachineSkillManagerAction.EDIT) }) { Text("Edit") }
          Button(onClick = { callbacks.managerAction(MachineSkillManagerAction.MANAGE_AGENTS) }) {
            Text("Manage agents")
          }
          Button(onClick = { callbacks.managerAction(MachineSkillManagerAction.REVEAL) }) { Text("Reveal source") }
          if (detail.repairAvailable) {
            Button(onClick = { callbacks.managerAction(MachineSkillManagerAction.REPAIR) }) {
              Text("Repair")
            }
          }
          Button(onClick = { callbacks.managerAction(MachineSkillManagerAction.DELETE) }) { Text("Delete") }
        }
      } else {
        Text("Read-only until adopted. Divergent copies require an authoritative source and replacement targets.")
        Button(onClick = { callbacks.managerAction(MachineSkillManagerAction.ADOPT) }) { Text("Adopt") }
      }
      manager.pendingAction?.let { action ->
        Text("$action preview and confirmation", style = MaterialTheme.typography.titleMedium)
        if (action == "ADOPT") {
          Text("Choose the authoritative source")
          detail.targets.flatMap { it.occurrencePaths }.forEach { path ->
            OutlinedButton(onClick = { callbacks.selectAuthority(path) }) {
              Text(if (manager.authoritativeSource == path) "Selected: $path" else path)
            }
          }
        }
        if (action == "ADOPT" || action == "MANAGE_AGENTS") {
          Text(if (action == "ADOPT") "Choose replacement targets" else "Choose managed targets")
          detail.targets.forEach { target ->
            Row(Modifier.clickable { callbacks.toggleManagerTarget(target.id) }) {
              Checkbox(target.id in manager.replacementTargetIds, { callbacks.toggleManagerTarget(target.id) })
              Text("${target.provider} · ${target.path}")
            }
          }
        }
        manager.actionPreview.forEach { Text("${it.operation} ${it.path} — ${it.detail}") }
        if (manager.actionPlanId == null) {
          val choicesReady = action != "ADOPT" ||
            (manager.authoritativeSource != null && manager.replacementTargetIds.isNotEmpty())
          Button(onClick = callbacks.previewManagerAction, enabled = choicesReady) { Text("Preview $action") }
        } else {
          Button(onClick = callbacks.applyManagerAction, enabled = !state.machineMutationBusy) {
            Text("Confirm $action")
          }
        }
      }
    }
    manager.error?.let {
      Text(
        it,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
      )
    }
  }
}

private fun MachineSkillOwnershipFilter.nextFilter(): MachineSkillOwnershipFilter {
  val values = MachineSkillOwnershipFilter.values()
  return values[(ordinal + 1) % values.size]
}

private fun MachineSkillHealthFilter.nextFilter(): MachineSkillHealthFilter {
  val values = MachineSkillHealthFilter.values()
  return values[(ordinal + 1) % values.size]
}

private fun String?.nextAgent(agents: List<String>): String? {
  if (agents.isEmpty()) return null
  val index = agents.indexOf(this)
  return if (index < 0) agents.first() else agents.getOrNull(index + 1)
}
