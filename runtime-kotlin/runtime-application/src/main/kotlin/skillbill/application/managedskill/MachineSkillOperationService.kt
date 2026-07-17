@file:Suppress("ktlint:standard:max-line-length")

package skillbill.application.managedskill

import me.tatarka.inject.annotations.Inject
import skillbill.managedskill.model.AdoptMachineSkillRequest
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.BundleIdentity
import skillbill.managedskill.model.DeleteMachineSkillRequest
import skillbill.managedskill.model.EditMachineSkillRequest
import skillbill.managedskill.model.InstallMachineSkillRequest
import skillbill.managedskill.model.MachineSkillInstallDecision
import skillbill.managedskill.model.MachineSkillMutation
import skillbill.managedskill.model.MachineSkillMutationKind
import skillbill.managedskill.model.MachineSkillMutationPlan
import skillbill.managedskill.model.MachineSkillOperation
import skillbill.managedskill.model.MachineSkillOperationPreview
import skillbill.managedskill.model.MachineSkillOutcome
import skillbill.managedskill.model.MachineSkillPreconditions
import skillbill.managedskill.model.MachineSkillResourceKind
import skillbill.managedskill.model.MachineSkillServiceOutcome
import skillbill.managedskill.model.MachineSkillServiceOutcomeKind
import skillbill.managedskill.model.ManageMachineSkillTargetsRequest
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.ManagedSkillSourceKind
import skillbill.managedskill.model.NoFollowEntryKind
import skillbill.managedskill.model.OpaqueSkillBundle
import skillbill.managedskill.model.OpenMachineSkillEdit
import skillbill.managedskill.model.OwnershipProof
import skillbill.managedskill.model.PreparedMachineSkillMutation
import skillbill.managedskill.model.RepairMachineSkillRequest
import skillbill.managedskill.model.SaveMachineSkillEditRequest
import skillbill.ports.managedskill.MachineSkillWorkspacePort
import java.nio.file.Path
import java.time.Clock

@Inject
class MachineSkillOperationService(
  private val workspace: MachineSkillWorkspacePort,
  clock: Clock = Clock.systemUTC(),
) {
  private val previews = MachineSkillPreviewBuilder(workspace, clock)

  fun previewInstall(request: InstallMachineSkillRequest): MachineSkillOperationPreview = runCatching {
    previews.validateTargets(
      MachineSkillMutationKind.INSTALL,
      request.source.fileName?.toString().orEmpty(),
      request.selectedTargets,
    )
      ?: previews.previewCaptured(
        CapturedPreviewRequest(
          workspace.bundles.capture(request.source),
          request.selectedTargets,
          ManagedSkillSourceKind.DIRECTORY,
        ),
      )
  }.getOrElse {
    previews.failed(
      MachineSkillMutationKind.INSTALL,
      request.source.fileName?.toString().orEmpty(),
      "bundle-invalid",
      it,
    )
  }

  fun openEdit(request: EditMachineSkillRequest): OpenMachineSkillEdit {
    val record = requireNotNull(workspace.records.readRecord(request.name)) { "Managed skill record is missing." }
    require(workspace.records.recordDigest(request.name) == request.expectedRecordDigest) {
      "Managed record changed before edit open."
    }
    val bundle = workspace.bundles.capture(workspace.records.sourceRoot(request.name))
    require(bundle.contentHash == request.expectedSourceHash && bundle.contentHash == record.activeContentHash) {
      "Managed source changed before edit open."
    }
    val markdown = bundle.files.single { it.relativePath == "SKILL.md" }.content.toString(Charsets.UTF_8)
    return OpenMachineSkillEdit(request.name, markdown, request.expectedRecordDigest, request.expectedSourceHash)
  }

  fun previewEdit(request: SaveMachineSkillEditRequest): MachineSkillOperationPreview {
    val record = workspace.records.readRecord(request.edit.name)
      ?: return previews.blocked(MachineSkillMutationKind.EDIT, request.edit.name, "record-missing")
    if (workspace.records.recordDigest(record.name) != request.edit.expectedRecordDigest) {
      return previews.blocked(MachineSkillMutationKind.EDIT, record.name, "record-stale")
    }
    val candidate = workspace.bundles.captureEditedSource(
      record.name,
      request.edit.expectedSourceHash,
      request.skillMarkdown,
    )
    return previews.previewCaptured(
      CapturedPreviewRequest(
        candidate,
        record.selectedTargets,
        record.sourceKind,
        MachineSkillMutationKind.EDIT,
        record,
      ),
    )
  }

  fun previewManageTargets(request: ManageMachineSkillTargetsRequest): MachineSkillOperationPreview = run {
    val record = workspace.records.readRecord(request.name)
    val invalidTarget = previews.validateTargets(
      MachineSkillMutationKind.MANAGE_TARGETS,
      request.name,
      request.selectedTargets,
    )
    when {
      record == null -> previews.blocked(MachineSkillMutationKind.MANAGE_TARGETS, request.name, "record-missing")
      request.selectedTargets.isEmpty() -> previews.blocked(
        MachineSkillMutationKind.MANAGE_TARGETS,
        request.name,
        "targets-empty",
      )
      invalidTarget != null -> invalidTarget
      else -> previewManagedTargets(record, request.selectedTargets)
    }
  }

  fun previewAdoption(request: AdoptMachineSkillRequest): MachineSkillOperationPreview = run {
    val source = request.authoritativeSource
    val invalidTarget = previews.validateTargets(
      MachineSkillMutationKind.ADOPT,
      request.name,
      request.replacementTargets,
    )
    when {
      source == null -> previews.blocked(
        MachineSkillMutationKind.ADOPT,
        request.name,
        "authoritative-source-required",
      )
      request.replacementTargets.isEmpty() -> previews.blocked(
        MachineSkillMutationKind.ADOPT,
        request.name,
        "replacement-targets-required",
      )
      invalidTarget != null -> invalidTarget
      else -> previewAdoptionSource(request, source)
    }
  }

  private fun previewManagedTargets(
    record: ManagedSkillRecord,
    selectedTargets: Set<AgentSkillTargetId>,
  ): MachineSkillOperationPreview {
    val source = workspace.bundles.capture(workspace.records.sourceRoot(record.name))
    return if (source.contentHash != record.activeContentHash || !workspace.snapshots.snapshotHealthy(source)) {
      previews.blocked(MachineSkillMutationKind.MANAGE_TARGETS, record.name, "managed-state-unhealthy")
    } else {
      previews.previewCaptured(
        CapturedPreviewRequest(
          source,
          selectedTargets,
          record.sourceKind,
          MachineSkillMutationKind.MANAGE_TARGETS,
          record,
        ),
      )
    }
  }

  private fun previewAdoptionSource(request: AdoptMachineSkillRequest, source: Path): MachineSkillOperationPreview {
    val bundle = workspace.bundles.capture(source)
    if (bundle.name != request.name) {
      return previews.blocked(
        MachineSkillMutationKind.ADOPT,
        request.name,
        "authoritative-name-mismatch",
      )
    }
    val divergent = request.replacementTargets.filter { target ->
      runCatching {
        workspace.bundles.capture(
          target.skillsPath.resolve(request.name),
        ).contentHash
      }.getOrNull() != bundle.contentHash
    }
    if (divergent.isNotEmpty()) {
      return previews.blocked(
        MachineSkillMutationKind.ADOPT,
        request.name,
        "replacement-not-equivalent",
        divergent.first(),
      )
    }
    return previews.previewCaptured(
      CapturedPreviewRequest(
        bundle,
        request.replacementTargets,
        ManagedSkillSourceKind.ADOPTED,
        MachineSkillMutationKind.ADOPT,
        adoptionReplacements = request.replacementTargets,
      ),
    )
  }

  fun previewRepair(request: RepairMachineSkillRequest): MachineSkillOperationPreview = run {
    val record = workspace.records.readRecord(request.name)
    val bundle = record?.let {
      runCatching { workspace.bundles.capture(workspace.records.sourceRoot(request.name)) }.getOrNull()
    }
    val references = workspace.snapshots.snapshotReferences()
    when {
      record == null -> previews.blocked(
        MachineSkillMutationKind.REPAIR,
        request.name,
        "record-missing-or-corrupt",
      )
      bundle == null -> previews.blocked(
        MachineSkillMutationKind.REPAIR,
        request.name,
        "source-missing-or-invalid",
      )
      bundle.contentHash != record.activeContentHash -> previews.blocked(
        MachineSkillMutationKind.REPAIR,
        request.name,
        "source-hash-mismatch",
      )
      !workspace.snapshots.snapshotHealthy(bundle) -> previews.blocked(
        MachineSkillMutationKind.REPAIR,
        request.name,
        "snapshot-unhealthy",
      )
      !references.complete -> previews.blocked(
        MachineSkillMutationKind.REPAIR,
        request.name,
        "references-incomplete",
      )
      else -> previewHealthyRepair(record, bundle)
    }
  }

  private fun previewHealthyRepair(
    record: ManagedSkillRecord,
    bundle: OpaqueSkillBundle,
  ): MachineSkillOperationPreview {
    val preview = previews.buildPreview(
      PreviewBuildRequest(bundle, record, record.selectedTargets, MachineSkillMutationKind.REPAIR, false),
    )
    val orphanOutcomes = workspace.snapshots.orphanSnapshots().map { path ->
      MachineSkillServiceOutcome(
        MachineSkillServiceOutcomeKind.WARNING,
        "orphan-snapshot",
        "Unreferenced snapshot requires manual review",
        record.name,
        path = path,
      )
    }
    return preview.copy(outcomes = preview.outcomes + orphanOutcomes)
  }

  fun previewDelete(request: DeleteMachineSkillRequest): MachineSkillOperationPreview {
    val record = workspace.records.readRecord(request.name)
      ?: return previews.blocked(MachineSkillMutationKind.DELETE, request.name, "record-missing-or-corrupt")
    val snapshot = workspace.snapshots.snapshotRoot(record.name, record.activeContentHash)
    val references = workspace.snapshots.snapshotReferences()
    if (!references.complete) {
      return previews.blocked(MachineSkillMutationKind.DELETE, request.name, "references-incomplete")
    }
    val ownedLinks = workspace.targets.ownedLinkPaths(record.name, snapshot)
    val snapshotRemovable = workspace.snapshots.snapshotUnreferencedAfterDelete(record.name, snapshot, ownedLinks)
    val paths = ownedLinks + listOf(
      workspace.records.sourceRoot(record.name),
      workspace.records.recordPath(record.name),
    ) +
      listOfNotNull(snapshot.takeIf { snapshotRemovable })
    val observations = workspace.targets.observe(paths)
    val mutations = observations.map { observation ->
      val resource = when (observation.path) {
        workspace.records.sourceRoot(record.name) -> MachineSkillResourceKind.MANAGED_SOURCE
        workspace.records.recordPath(record.name) -> MachineSkillResourceKind.RECORD
        snapshot -> MachineSkillResourceKind.SNAPSHOT
        else -> MachineSkillResourceKind.AGENT_LINK
      }
      val expectedOwnedLink = resource != MachineSkillResourceKind.AGENT_LINK ||
        observation.normalizedLinkTarget == snapshot
      MachineSkillMutation(
        resource,
        MachineSkillOperation.REMOVE,
        if (expectedOwnedLink) MachineSkillOutcome.REMOVE else MachineSkillOutcome.CONFLICT,
        observation.path,
        observation,
      )
    }
    val prepared = previews.prepare(
      PreparePreviewRequest(
        MachineSkillMutationKind.DELETE,
        record.name,
        mutations,
        old = record,
        cleanup = setOf(snapshot),
      ),
    )
    return MachineSkillOperationPreview(
      MachineSkillMutationKind.DELETE,
      record.name,
      prepared = prepared,
      outcomes = emptyList(),
    )
  }
}

private data class CapturedPreviewRequest(
  val candidate: OpaqueSkillBundle,
  val targets: Set<AgentSkillTargetId>,
  val sourceKind: ManagedSkillSourceKind,
  val kindOverride: MachineSkillMutationKind? = null,
  val existingOverride: ManagedSkillRecord? = null,
  val adoptionReplacements: Set<AgentSkillTargetId> = emptySet(),
)

private data class PreviewBuildRequest(
  val candidate: OpaqueSkillBundle,
  val desiredRecord: ManagedSkillRecord,
  val targets: Set<AgentSkillTargetId>,
  val kind: MachineSkillMutationKind,
  val writeCandidate: Boolean,
  val adoptionReplacements: Set<AgentSkillTargetId> = emptySet(),
)

private data class PreparePreviewRequest(
  val kind: MachineSkillMutationKind,
  val name: String,
  val mutations: List<MachineSkillMutation>,
  val candidate: OpaqueSkillBundle? = null,
  val desired: ManagedSkillRecord? = null,
  val old: ManagedSkillRecord? = null,
  val cleanup: Set<Path> = emptySet(),
)

private data class PreviewMutationContext(
  val old: ManagedSkillRecord?,
  val oldSnapshot: Path?,
  val snapshot: Path,
  val targetPaths: Map<AgentSkillTargetId, Path>,
  val observations: Map<Path, skillbill.managedskill.model.PathObservation>,
)

private data class TargetSelection(val selected: Boolean, val expectedOld: Boolean)

private class MachineSkillPreviewBuilder(
  private val workspace: MachineSkillWorkspacePort,
  private val clock: Clock,
) {
  fun previewCaptured(request: CapturedPreviewRequest): MachineSkillOperationPreview {
    val candidate = request.candidate
    if (request.targets.isEmpty()) {
      return blocked(
        request.kindOverride ?: MachineSkillMutationKind.INSTALL,
        candidate.name,
        "targets-empty",
      )
    }
    val existing = request.existingOverride ?: workspace.records.readRecord(candidate.name)
    val decision = when {
      existing == null -> MachineSkillInstallDecision.INSTALL
      existing.activeContentHash == candidate.contentHash -> MachineSkillInstallDecision.REINSTALL
      else -> MachineSkillInstallDecision.UPDATE
    }
    val defaultKind = if (decision == MachineSkillInstallDecision.UPDATE) {
      MachineSkillMutationKind.UPDATE
    } else {
      MachineSkillMutationKind.INSTALL
    }
    val kind = request.kindOverride ?: defaultKind
    if (decision == MachineSkillInstallDecision.REINSTALL) {
      val canonical = runCatching {
        workspace.bundles.capture(workspace.records.sourceRoot(candidate.name))
      }.getOrNull()
      if (canonical?.contentHash != candidate.contentHash || !workspace.snapshots.snapshotHealthy(candidate)) {
        return blocked(kind, candidate.name, "identical-content-state-unhealthy")
      }
    }
    val importedAt = existing?.importedAt ?: clock.instant()
    val record = ManagedSkillRecord(
      candidate.name,
      request.sourceKind,
      workspace.records.sourceRoot(candidate.name),
      candidate.contentHash,
      request.targets,
      importedAt,
      clock.instant(),
    )
    validateTargets(kind, candidate.name, request.targets)?.let { return it }
    val preview = buildPreview(
      PreviewBuildRequest(
        candidate,
        record,
        request.targets,
        kind,
        decision != MachineSkillInstallDecision.REINSTALL,
        request.adoptionReplacements,
      ),
    )
    return MachineSkillOperationPreview(kind, candidate.name, decision, preview.prepared, preview.outcomes)
  }

  fun buildPreview(request: PreviewBuildRequest): MachineSkillOperationPreview {
    val context = mutationContext(request)
    val mutations = fixedMutations(request, context) + context.targetPaths.map { (target, path) ->
      targetMutation(request, context, target, path)
    }
    val prepared = prepare(
      PreparePreviewRequest(
        request.kind,
        request.candidate.name,
        mutations,
        request.candidate.takeIf { request.writeCandidate },
        request.desiredRecord,
        context.old,
      ),
    )
    val outcomes = context.targetPaths.map { (target, path) ->
      val value = prepared.plan.mutations.single { it.path == path }.outcome
      MachineSkillServiceOutcome(
        value.toServiceKind(),
        value.name.lowercase(),
        value.name,
        request.candidate.name,
        target,
        path,
      )
    }
    return MachineSkillOperationPreview(
      request.kind,
      request.candidate.name,
      prepared = prepared,
      outcomes = outcomes,
    )
  }

  private fun mutationContext(request: PreviewBuildRequest): PreviewMutationContext {
    val candidate = request.candidate
    val old = workspace.records.readRecord(candidate.name)
    val oldSnapshot = old?.let { workspace.snapshots.snapshotRoot(it.name, it.activeContentHash) }
    val snapshot = workspace.snapshots.snapshotRoot(candidate.name, candidate.contentHash)
    val targetPaths = (old?.selectedTargets.orEmpty() + request.targets)
      .associateWith { it.skillsPath.resolve(candidate.name) }
    val fixedPaths = listOf(
      workspace.records.sourceRoot(candidate.name),
      workspace.records.recordPath(candidate.name),
      snapshot,
    )
    val observations = workspace.targets.observe(fixedPaths + targetPaths.values).associateBy { it.path }
    return PreviewMutationContext(old, oldSnapshot, snapshot, targetPaths, observations)
  }

  private fun fixedMutations(
    request: PreviewBuildRequest,
    context: PreviewMutationContext,
  ): List<MachineSkillMutation> {
    val candidate = request.candidate
    val old = context.old
    val sourceOutcome = when {
      !request.writeCandidate -> MachineSkillOutcome.UNCHANGED
      old == null -> MachineSkillOutcome.CREATE
      else -> MachineSkillOutcome.REPLACE
    }
    val source = MachineSkillMutation(
      MachineSkillResourceKind.MANAGED_SOURCE,
      if (old == null) MachineSkillOperation.CREATE else MachineSkillOperation.REPLACE,
      sourceOutcome,
      workspace.records.sourceRoot(candidate.name),
      context.observations.getValue(workspace.records.sourceRoot(candidate.name)),
    )
    val snapshot = MachineSkillMutation(
      MachineSkillResourceKind.SNAPSHOT,
      MachineSkillOperation.CREATE,
      if (workspace.snapshots.snapshotHealthy(candidate)) MachineSkillOutcome.UNCHANGED else MachineSkillOutcome.CREATE,
      context.snapshot,
      context.observations.getValue(context.snapshot),
    )
    val recordOutcome = when {
      old == request.desiredRecord -> MachineSkillOutcome.UNCHANGED
      old == null -> MachineSkillOutcome.CREATE
      else -> MachineSkillOutcome.REPLACE
    }
    val recordPath = workspace.records.recordPath(candidate.name)
    val record = MachineSkillMutation(
      MachineSkillResourceKind.RECORD,
      if (old == null) MachineSkillOperation.CREATE else MachineSkillOperation.REPLACE,
      recordOutcome,
      recordPath,
      context.observations.getValue(recordPath),
    )
    return listOf(source, snapshot, record)
  }

  private fun targetMutation(
    request: PreviewBuildRequest,
    context: PreviewMutationContext,
    target: AgentSkillTargetId,
    path: Path,
  ): MachineSkillMutation {
    val selected = target in request.targets
    val expectedOld = context.oldSnapshot != null &&
      context.observations.getValue(path).normalizedLinkTarget == context.oldSnapshot
    val operation = when {
      !selected -> MachineSkillOperation.REMOVE
      expectedOld -> MachineSkillOperation.RETARGET
      else -> MachineSkillOperation.CREATE
    }
    val observation = context.observations.getValue(path)
    val selection = TargetSelection(selected, expectedOld)
    val outcome = targetOutcome(request, context, target, observation, selection)
    return MachineSkillMutation(
      MachineSkillResourceKind.AGENT_LINK,
      operation,
      outcome,
      path,
      observation,
      context.snapshot.takeIf { selected },
    )
  }

  private fun targetOutcome(
    request: PreviewBuildRequest,
    context: PreviewMutationContext,
    target: AgentSkillTargetId,
    observation: skillbill.managedskill.model.PathObservation,
    selection: TargetSelection,
  ): MachineSkillOutcome {
    val outcome = when {
      selection.selected && observation.normalizedLinkTarget == context.snapshot -> MachineSkillOutcome.UNCHANGED
      selection.selected && target in request.adoptionReplacements -> MachineSkillOutcome.RETARGET
      selection.selected && (observation.kind == NoFollowEntryKind.ABSENT || selection.expectedOld) -> {
        if (selection.expectedOld) MachineSkillOutcome.RETARGET else MachineSkillOutcome.CREATE
      }
      !selection.selected && selection.expectedOld -> MachineSkillOutcome.REMOVE
      !selection.selected -> MachineSkillOutcome.SKIPPED
      else -> MachineSkillOutcome.CONFLICT
    }
    return outcome
  }

  fun prepare(request: PreparePreviewRequest): PreparedMachineSkillMutation {
    val references = workspace.snapshots.snapshotReferences()
    val ownership = request.mutations.associate { mutation ->
      val expectedSnapshot = request.old?.let {
        workspace.snapshots.snapshotRoot(it.name, it.activeContentHash)
      }
      mutation.path to OwnershipProof(
        mutation.resource != MachineSkillResourceKind.AGENT_LINK ||
          mutation.expected.normalizedLinkTarget == expectedSnapshot,
        workspace.records.recordDigest(request.name),
        "captured-preview",
      )
    }
    val preconditions = MachineSkillPreconditions(
      request.mutations.map { it.expected },
      workspace.records.recordDigest(request.name),
      request.old?.contractVersion,
      request.old?.activeContentHash,
      request.candidate?.let { BundleIdentity(it.name, it.contentHash, it.totalBytes, it.files.size) },
      request.desired?.selectedTargets.orEmpty().mapTo(mutableSetOf()) { it.stableIdentity },
      ownership,
      references.references,
      references.complete,
      workspace.targets.symlinkCapability(),
    )
    val plan = MachineSkillMutationPlan(request.kind, request.name, request.mutations, preconditions)
    return PreparedMachineSkillMutation(plan, request.candidate, request.desired, request.cleanup)
  }

  fun blocked(kind: MachineSkillMutationKind, name: String, code: String, target: AgentSkillTargetId? = null) =
    MachineSkillOperationPreview(
      kind,
      name,
      outcomes = listOf(MachineSkillServiceOutcome(MachineSkillServiceOutcomeKind.BLOCKED, code, code, name, target)),
    )

  fun failed(kind: MachineSkillMutationKind, name: String, code: String, failure: Throwable) =
    MachineSkillOperationPreview(
      kind,
      name,
      outcomes = listOf(
        MachineSkillServiceOutcome(MachineSkillServiceOutcomeKind.FAILED, code, failure.message ?: code, name),
      ),
    )

  fun validateTargets(
    kind: MachineSkillMutationKind,
    name: String,
    targets: Set<AgentSkillTargetId>,
  ): MachineSkillOperationPreview? = targets.firstOrNull {
    !workspace.targets.isDiscoveredTarget(it)
  }?.let { blocked(kind, name, "target-outside-discovered-roots", it) }
}

private fun MachineSkillOutcome.toServiceKind() = when (this) {
  MachineSkillOutcome.CREATE -> MachineSkillServiceOutcomeKind.CREATED
  MachineSkillOutcome.REPLACE, MachineSkillOutcome.RETARGET -> MachineSkillServiceOutcomeKind.RETARGETED
  MachineSkillOutcome.REMOVE -> MachineSkillServiceOutcomeKind.REMOVED
  MachineSkillOutcome.UNCHANGED -> MachineSkillServiceOutcomeKind.UNCHANGED
  MachineSkillOutcome.CONFLICT -> MachineSkillServiceOutcomeKind.CONFLICT
  MachineSkillOutcome.WARNING -> MachineSkillServiceOutcomeKind.WARNING
  MachineSkillOutcome.SKIPPED -> MachineSkillServiceOutcomeKind.SKIPPED
  MachineSkillOutcome.BLOCKED -> MachineSkillServiceOutcomeKind.BLOCKED
}
