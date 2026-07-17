@file:Suppress("FunctionName")

package skillbill.desktop.feature.skillbill.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import skillbill.desktop.core.designsystem.SkillBillComponentShapes
import skillbill.desktop.core.designsystem.SkillBillDimens
import skillbill.desktop.core.designsystem.SkillBillStatusTone
import skillbill.desktop.core.designsystem.SkillBillTheme
import skillbill.desktop.core.designsystem.SkillBillTypeStyles
import skillbill.desktop.core.designsystem.contentColorFor
import skillbill.desktop.core.domain.model.MachineSkillHealthFilter
import skillbill.desktop.core.domain.model.MachineSkillInstallStep
import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.MachineSkillManagerState
import skillbill.desktop.core.domain.model.MachineSkillOwnershipFilter
import skillbill.desktop.core.domain.model.MachineSkillTargetDetail
import skillbill.desktop.core.domain.model.MachineToolAction
import skillbill.desktop.core.domain.model.MachineToolDescriptor
import skillbill.desktop.core.domain.model.MachineToolMutationRisk
import skillbill.desktop.core.domain.model.MachineToolsState
import skillbill.desktop.core.domain.model.MachineToolsSurface

enum class MachineSkillManagerAction { EDIT, MANAGE_AGENTS, REVEAL, REPAIR, DELETE, ADOPT, INSPECT }

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
  val filteredRows = manager.filteredRows()
  Column(
    modifier = Modifier.fillMaxWidth().heightIn(max = SkillBillDimens.dialogMaxHeight),
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    OutlinedTextField(
      value = manager.query,
      onValueChange = callbacks.updateQuery,
      label = { Text("Search skills") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
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
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .testTag("machine-skill-manager-scroll"),
      verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacing2xl),
    ) {
      filteredRows.forEach { row ->
        ManagerSkillRow(row, selected = row.logicalKey == manager.selectedName) {
          callbacks.selectSkill(row.logicalKey)
        }
      }
      if (filteredRows.isEmpty() && !manager.loading) {
        Text(
          "No installed skills match these filters.",
          color = SkillBillTheme.frameTokens.muted,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      manager.detail?.let { detail ->
        HorizontalDivider(color = SkillBillTheme.frameTokens.line)
        ManagerSkillDetail(detail, manager, callbacks)
      }
      manager.error?.let {
        Text(
          it,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
      }
    }
    ManagerActionBar(state, callbacks)
  }
}

private fun MachineSkillManagerState.filteredRows(): List<MachineSkillManagerRow> = rows.filter { row ->
  row.name.contains(query, ignoreCase = true) &&
    (ownershipFilter == MachineSkillOwnershipFilter.ALL || row.ownership == ownershipFilter.name) &&
    (
      healthFilter == MachineSkillHealthFilter.ALL ||
        (healthFilter == MachineSkillHealthFilter.HEALTHY) == (row.health == "HEALTHY")
      ) &&
    (agentFilter == null || agentFilter in row.agents)
}

@Composable
private fun ManagerSkillRow(row: MachineSkillManagerRow, selected: Boolean, onClick: () -> Unit) {
  val background = if (selected) SkillBillTheme.frameTokens.raised else SkillBillTheme.frameTokens.panel
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.panel)
      .background(background)
      .border(SkillBillDimens.hairline, SkillBillTheme.frameTokens.line, SkillBillComponentShapes.panel)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(SkillBillDimens.pad2xl),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
  ) {
    MiniIcon("sk", SkillBillTheme.frameTokens.primary)
    Column(modifier = Modifier.weight(1f)) {
      Text(row.name, color = SkillBillTheme.frameTokens.text, style = SkillBillTypeStyles.semiBoldLabel)
      Text(
        row.description,
        color = SkillBillTheme.frameTokens.muted,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    ManagerBadge(row.ownership, ownershipTone(row.ownership))
    ManagerBadge(row.health, healthTone(row.health))
  }
}

@Composable
private fun ManagerSkillDetail(
  detail: MachineSkillManagerDetail,
  manager: MachineSkillManagerState,
  callbacks: MachineToolsCallbacks,
) {
  Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacing2xl)) {
    Column(verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
      ) {
        Text(
          detail.name,
          color = SkillBillTheme.frameTokens.text,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        ManagerBadge(detail.ownership, ownershipTone(detail.ownership))
      }
      Text(detail.description, color = SkillBillTheme.frameTokens.muted, style = MaterialTheme.typography.bodyMedium)
    }
    ManagerSection("Management source", "so") {
      ManagerKeyValue("Status", if (detail.canonicalManagedSourcePath == null) "Not managed yet" else "Managed")
      detail.canonicalManagedSourcePath?.let { ManagerKeyValue("Source", it) }
      detail.recordIdentity?.let { ManagerKeyValue("Record", it) }
      detail.activeSnapshotHash?.let { ManagerKeyValue("Snapshot", it.compactIdentity()) }
      detail.contentIdentity?.let { ManagerKeyValue("Content", it.compactIdentity()) }
      if (detail.provenance.isNotEmpty()) ManagerKeyValue("Found via", detail.provenance.joinToString())
    }
    ManagerSection(
      title = "Agent targets",
      marker = "ag",
      badge = "${detail.targets.count { it.state == "PRESENT" }} installed",
    ) {
      detail.targets
        .sortedWith(compareByDescending<MachineSkillTargetDetail> { it.state == "PRESENT" }.thenBy { it.provider })
        .forEach { target -> ManagerTargetRow(target) }
    }
    detail.validationIssues.forEach {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    detail.lastMutationResult.forEach {
      ManagerSection("Last change", "ch") {
        ManagerKeyValue(it.targetId, "${it.outcome} — ${it.detail}")
      }
    }
    if (detail.ownership == "MANAGED") {
      ManagedSkillActions(detail, callbacks)
    } else {
      UnmanagedSkillChoices(detail, manager, callbacks)
    }
    manager.pendingAction?.let { action -> ManagerActionChoices(action, detail, manager, callbacks) }
  }
}

@Composable
private fun ManagerSection(
  title: String,
  marker: String,
  badge: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(SkillBillComponentShapes.panel)
      .background(SkillBillTheme.frameTokens.panel)
      .border(SkillBillDimens.hairline, SkillBillTheme.frameTokens.line, SkillBillComponentShapes.panel)
      .padding(SkillBillDimens.pad2xl),
    verticalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
    ) {
      MiniIcon(marker, SkillBillTheme.frameTokens.primary)
      Text(
        title,
        color = SkillBillTheme.frameTokens.text,
        style = SkillBillTypeStyles.semiBoldLabel,
        modifier = Modifier.weight(1f),
      )
      badge?.let { ManagerBadge(it, SkillBillStatusTone.Neutral) }
    }
    content()
  }
}

@Composable
private fun ManagerKeyValue(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
  ) {
    LabelText(label, modifier = Modifier.weight(1f))
    Text(
      value,
      color = SkillBillTheme.frameTokens.muted,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      modifier = Modifier.weight(3f),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun ManagerTargetRow(target: MachineSkillTargetDetail) {
  val present = target.state == "PRESENT"
  val contentIdentity = target.contentIdentity
  val tone = if (present) SkillBillStatusTone.Success else SkillBillStatusTone.Neutral
  val targetColor = SkillBillTheme.frameTokens.status.contentColorFor(tone)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("machine-skill-target-${target.id}")
      .padding(vertical = SkillBillDimens.padSm),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingXl),
  ) {
    Box(
      modifier = Modifier
        .padding(top = SkillBillDimens.padMd)
        .size(SkillBillDimens.space7)
        .clip(SkillBillComponentShapes.pill)
        .background(targetColor),
    )
    Column(modifier = Modifier.weight(1f)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingLg),
      ) {
        Text(
          target.provider.uppercase(),
          color = SkillBillTheme.frameTokens.text,
          style = SkillBillTypeStyles.monoBadge,
          modifier = Modifier.weight(1f),
        )
        ManagerBadge(if (present) "INSTALLED" else "NOT INSTALLED", tone)
      }
      Text(
        target.path,
        color = if (present) SkillBillTheme.frameTokens.muted else SkillBillTheme.frameTokens.subtle,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (present && contentIdentity != null) {
        Text(
          "Content ${contentIdentity.compactIdentity()}",
          color = SkillBillTheme.frameTokens.subtle,
          style = SkillBillTypeStyles.caption.copy(fontFamily = FontFamily.Monospace),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun ManagedSkillActions(detail: MachineSkillManagerDetail, callbacks: MachineToolsCallbacks) {
  ManagerSection("Actions", "ac") {
    Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd)) {
      OutlinedButton(onClick = { callbacks.managerAction(MachineSkillManagerAction.EDIT) }) { Text("Edit") }
      OutlinedButton(onClick = { callbacks.managerAction(MachineSkillManagerAction.MANAGE_AGENTS) }) {
        Text("Manage agents")
      }
      OutlinedButton(onClick = { callbacks.managerAction(MachineSkillManagerAction.REVEAL) }) {
        Text("Reveal source")
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacingMd)) {
      if (detail.repairAvailable) {
        OutlinedButton(onClick = { callbacks.managerAction(MachineSkillManagerAction.REPAIR) }) { Text("Repair") }
      }
      OutlinedButton(onClick = { callbacks.managerAction(MachineSkillManagerAction.DELETE) }) { Text("Delete") }
    }
  }
}

@Composable
private fun UnmanagedSkillChoices(
  detail: MachineSkillManagerDetail,
  manager: MachineSkillManagerState,
  callbacks: MachineToolsCallbacks,
) {
  if (detail.targets.sumOf { it.occurrencePaths.size } <= 1) return
  ManagerSection("Installed copies", "cp") {
    Text(
      "Choose a copy to inspect before deciding which one Skill Bill should manage.",
      color = SkillBillTheme.frameTokens.muted,
      style = MaterialTheme.typography.bodySmall,
    )
    detail.targets
      .flatMap { target -> target.occurrencePaths.map { target.provider to it } }
      .forEach { (agent, path) ->
        OutlinedButton(onClick = { callbacks.selectAuthority(path) }) {
          Text(if (manager.authoritativeSource == path) "Selected: $agent · $path" else "$agent · $path")
        }
      }
    OutlinedButton(
      onClick = { callbacks.managerAction(MachineSkillManagerAction.INSPECT) },
      enabled = manager.authoritativeSource != null,
    ) { Text("Inspect selected copy") }
  }
}

@Composable
private fun ManagerActionChoices(
  action: String,
  detail: MachineSkillManagerDetail,
  manager: MachineSkillManagerState,
  callbacks: MachineToolsCallbacks,
) {
  ManagerSection("${action.actionLabel()} setup", "go") {
    if (action == "ADOPT") {
      Text("Authoritative source", color = SkillBillTheme.frameTokens.text, style = SkillBillTypeStyles.semiBoldLabel)
      detail.targets.flatMap { it.occurrencePaths }.forEach { path ->
        OutlinedButton(onClick = { callbacks.selectAuthority(path) }) {
          Text(if (manager.authoritativeSource == path) "Selected: $path" else path)
        }
      }
    }
    if (action == "ADOPT" || action == "MANAGE_AGENTS") {
      Text(
        if (action == "ADOPT") "Replacement targets" else "Managed targets",
        color = SkillBillTheme.frameTokens.text,
        style = SkillBillTypeStyles.semiBoldLabel,
      )
      detail.targets.forEach { target ->
        Row(
          modifier = Modifier.fillMaxWidth().clickable { callbacks.toggleManagerTarget(target.id) },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(target.id in manager.replacementTargetIds, { callbacks.toggleManagerTarget(target.id) })
          Text(
            "${target.provider} · ${target.path}",
            color = SkillBillTheme.frameTokens.muted,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
    manager.actionPreview.forEach { ManagerKeyValue(it.operation, "${it.path} — ${it.detail}") }
  }
}

@Composable
private fun ManagerActionBar(state: MachineToolsState, callbacks: MachineToolsCallbacks) {
  val manager = state.manager
  val detail = manager.detail ?: return
  val action = manager.pendingAction
  if (detail.ownership == "MANAGED" && action == null) return
  val choicesReady = action != "ADOPT" ||
    (manager.authoritativeSource != null && manager.replacementTargetIds.isNotEmpty())
  HorizontalDivider(color = SkillBillTheme.frameTokens.line)
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = SkillBillDimens.padMd),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(SkillBillDimens.spacing2xl),
  ) {
    Text(
      if (action == null) {
        "Adopt this skill to edit it safely and keep agent copies in sync."
      } else {
        "Review the ${action.actionLabel().lowercase()} before changing installed agent copies."
      },
      color = SkillBillTheme.frameTokens.muted,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.weight(1f),
    )
    when {
      action == null -> Button(
        onClick = { callbacks.managerAction(MachineSkillManagerAction.ADOPT) },
        modifier = Modifier.testTag("machine-skill-adopt-action"),
      ) { Text("Adopt skill") }
      manager.actionPlanId == null -> Button(
        onClick = callbacks.previewManagerAction,
        enabled = choicesReady,
      ) { Text("Preview ${action.actionLabel().lowercase()}") }
      else -> Button(
        onClick = callbacks.applyManagerAction,
        enabled = !state.machineMutationBusy,
      ) { Text("Confirm ${action.actionLabel().lowercase()}") }
    }
  }
}

@Composable
private fun ManagerBadge(text: String, tone: SkillBillStatusTone) {
  val color = SkillBillTheme.frameTokens.status.contentColorFor(tone)
  Text(
    text = text,
    color = color,
    style = SkillBillTypeStyles.caption.copy(fontFamily = FontFamily.Monospace),
    modifier = Modifier
      .border(SkillBillDimens.hairline, color.copy(alpha = 0.45f), SkillBillComponentShapes.previewConsole)
      .background(color.copy(alpha = 0.14f), SkillBillComponentShapes.previewConsole)
      .padding(horizontal = SkillBillDimens.padMd, vertical = SkillBillDimens.hairline),
    maxLines = 1,
  )
}

private fun ownershipTone(ownership: String): SkillBillStatusTone = when (ownership) {
  "MANAGED" -> SkillBillStatusTone.Success
  "CONFLICT" -> SkillBillStatusTone.Error
  else -> SkillBillStatusTone.Warning
}

private fun healthTone(health: String): SkillBillStatusTone =
  if (health == "HEALTHY") SkillBillStatusTone.Success else SkillBillStatusTone.Warning

private fun String.compactIdentity(): String = if (length <= 24) this else "${take(12)}…${takeLast(6)}"

private fun String.actionLabel(): String = when (this) {
  "ADOPT" -> "Adoption"
  "MANAGE_AGENTS" -> "Agent changes"
  "REPAIR" -> "Repair"
  "DELETE" -> "Deletion"
  else -> lowercase().replaceFirstChar(Char::titlecase)
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
