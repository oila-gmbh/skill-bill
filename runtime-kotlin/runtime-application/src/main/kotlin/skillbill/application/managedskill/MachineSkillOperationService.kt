package skillbill.application.managedskill

import skillbill.managedskill.model.*
import skillbill.ports.managedskill.MachineSkillWorkspacePort
import java.nio.file.Path
import java.time.Clock

class MachineSkillOperationService(
  private val workspace: MachineSkillWorkspacePort,
  private val clock: Clock = Clock.systemUTC(),
) {
  fun previewInstall(request: InstallMachineSkillRequest): MachineSkillOperationPreview =
    previewCaptured(workspace.capture(request.source), request.selectedTargets, ManagedSkillSourceKind.DIRECTORY)

  fun openEdit(request: EditMachineSkillRequest): OpenMachineSkillEdit {
    val record = requireNotNull(workspace.readRecord(request.name)) { "Managed skill record is missing." }
    require(workspace.recordDigest(request.name) == request.expectedRecordDigest) { "Managed record changed before edit open." }
    val bundle = workspace.capture(workspace.sourceRoot(request.name))
    require(bundle.contentHash == request.expectedSourceHash && bundle.contentHash == record.activeContentHash) {
      "Managed source changed before edit open."
    }
    val markdown = bundle.files.single { it.relativePath == "SKILL.md" }.content.toString(Charsets.UTF_8)
    return OpenMachineSkillEdit(request.name, markdown, request.expectedRecordDigest, request.expectedSourceHash)
  }

  fun previewEdit(request: SaveMachineSkillEditRequest): MachineSkillOperationPreview {
    val record = workspace.readRecord(request.edit.name)
      ?: return blocked(MachineSkillMutationKind.EDIT, request.edit.name, "record-missing")
    if (workspace.recordDigest(record.name) != request.edit.expectedRecordDigest) {
      return blocked(MachineSkillMutationKind.EDIT, record.name, "record-stale")
    }
    val candidate = workspace.captureEditedSource(record.name, request.edit.expectedSourceHash, request.skillMarkdown)
    return previewCaptured(candidate, record.selectedTargets, record.sourceKind, MachineSkillMutationKind.EDIT, record)
  }

  fun previewManageTargets(request: ManageMachineSkillTargetsRequest): MachineSkillOperationPreview {
    val record = workspace.readRecord(request.name)
      ?: return blocked(MachineSkillMutationKind.MANAGE_TARGETS, request.name, "record-missing")
    if (request.selectedTargets.isEmpty()) return blocked(MachineSkillMutationKind.MANAGE_TARGETS, request.name, "targets-empty")
    val source = workspace.capture(workspace.sourceRoot(request.name))
    if (source.contentHash != record.activeContentHash || !workspace.snapshotHealthy(source)) {
      return blocked(MachineSkillMutationKind.MANAGE_TARGETS, request.name, "managed-state-unhealthy")
    }
    return previewCaptured(source, request.selectedTargets, record.sourceKind, MachineSkillMutationKind.MANAGE_TARGETS, record)
  }

  fun previewAdoption(request: AdoptMachineSkillRequest): MachineSkillOperationPreview {
    val source = request.authoritativeSource
      ?: return blocked(MachineSkillMutationKind.ADOPT, request.name, "authoritative-source-required")
    if (request.replacementTargets.isEmpty()) return blocked(MachineSkillMutationKind.ADOPT, request.name, "replacement-targets-required")
    val bundle = workspace.capture(source)
    if (bundle.name != request.name) return blocked(MachineSkillMutationKind.ADOPT, request.name, "authoritative-name-mismatch")
    val divergent = request.replacementTargets.filter { target ->
      runCatching { workspace.capture(target.skillsPath.resolve(request.name)).contentHash }.getOrNull() != bundle.contentHash
    }
    if (divergent.isNotEmpty()) return blocked(MachineSkillMutationKind.ADOPT, request.name, "replacement-not-equivalent", divergent.first())
    return previewCaptured(bundle, request.replacementTargets, ManagedSkillSourceKind.ADOPTED, MachineSkillMutationKind.ADOPT)
  }

  fun previewRepair(request: RepairMachineSkillRequest): MachineSkillOperationPreview {
    val record = workspace.readRecord(request.name)
      ?: return blocked(MachineSkillMutationKind.REPAIR, request.name, "record-missing-or-corrupt")
    val bundle = runCatching { workspace.capture(workspace.sourceRoot(request.name)) }.getOrNull()
      ?: return blocked(MachineSkillMutationKind.REPAIR, request.name, "source-missing-or-invalid")
    val references = workspace.snapshotReferences()
    if (bundle.contentHash != record.activeContentHash) return blocked(MachineSkillMutationKind.REPAIR, request.name, "source-hash-mismatch")
    if (!workspace.snapshotHealthy(bundle)) return blocked(MachineSkillMutationKind.REPAIR, request.name, "snapshot-unhealthy")
    if (!references.complete) return blocked(MachineSkillMutationKind.REPAIR, request.name, "references-incomplete")
    return buildPreview(bundle, record, record.selectedTargets, MachineSkillMutationKind.REPAIR, writeCandidate = false)
  }

  fun previewDelete(request: DeleteMachineSkillRequest): MachineSkillOperationPreview {
    val record = workspace.readRecord(request.name)
      ?: return blocked(MachineSkillMutationKind.DELETE, request.name, "record-missing-or-corrupt")
    val snapshot = workspace.snapshotRoot(record.name, record.activeContentHash)
    val paths = record.selectedTargets.map { it.skillsPath.resolve(record.name) } +
      listOf(workspace.sourceRoot(record.name), workspace.recordPath(record.name), snapshot)
    val observations = workspace.observe(paths)
    val mutations = observations.map { observation ->
      val resource = when (observation.path) {
        workspace.sourceRoot(record.name) -> MachineSkillResourceKind.MANAGED_SOURCE
        workspace.recordPath(record.name) -> MachineSkillResourceKind.RECORD
        snapshot -> MachineSkillResourceKind.SNAPSHOT
        else -> MachineSkillResourceKind.AGENT_LINK
      }
      val expectedOwnedLink = resource != MachineSkillResourceKind.AGENT_LINK || observation.normalizedLinkTarget == snapshot
      MachineSkillMutation(
        resource,
        MachineSkillOperation.REMOVE,
        if (expectedOwnedLink) MachineSkillOutcome.REMOVE else MachineSkillOutcome.CONFLICT,
        observation.path,
        observation,
      )
    }
    val prepared = prepare(MachineSkillMutationKind.DELETE, record.name, mutations, null, null, record, setOf(snapshot))
    return MachineSkillOperationPreview(MachineSkillMutationKind.DELETE, record.name, prepared = prepared, outcomes = emptyList())
  }

  private fun previewCaptured(
    candidate: OpaqueSkillBundle,
    targets: Set<AgentSkillTargetId>,
    sourceKind: ManagedSkillSourceKind,
    kindOverride: MachineSkillMutationKind? = null,
    existingOverride: ManagedSkillRecord? = null,
  ): MachineSkillOperationPreview {
    if (targets.isEmpty()) return blocked(kindOverride ?: MachineSkillMutationKind.INSTALL, candidate.name, "targets-empty")
    val existing = existingOverride ?: workspace.readRecord(candidate.name)
    val decision = when {
      existing == null -> MachineSkillInstallDecision.INSTALL
      existing.activeContentHash == candidate.contentHash -> MachineSkillInstallDecision.REINSTALL
      else -> MachineSkillInstallDecision.UPDATE
    }
    val kind = kindOverride ?: if (decision == MachineSkillInstallDecision.UPDATE) MachineSkillMutationKind.UPDATE else MachineSkillMutationKind.INSTALL
    if (decision == MachineSkillInstallDecision.REINSTALL) {
      val canonical = runCatching { workspace.capture(workspace.sourceRoot(candidate.name)) }.getOrNull()
      if (canonical?.contentHash != candidate.contentHash || !workspace.snapshotHealthy(candidate)) {
        return blocked(kind, candidate.name, "identical-content-state-unhealthy")
      }
    }
    val importedAt = existing?.importedAt ?: clock.instant()
    val record = ManagedSkillRecord(
      candidate.name,
      sourceKind,
      workspace.sourceRoot(candidate.name),
      candidate.contentHash,
      targets,
      importedAt,
      clock.instant(),
    )
    val preview = buildPreview(candidate, record, targets, kind, writeCandidate = decision != MachineSkillInstallDecision.REINSTALL)
    return MachineSkillOperationPreview(kind, candidate.name, decision, preview.prepared, preview.outcomes)
  }

  private fun buildPreview(
    candidate: OpaqueSkillBundle,
    desiredRecord: ManagedSkillRecord,
    targets: Set<AgentSkillTargetId>,
    kind: MachineSkillMutationKind,
    writeCandidate: Boolean,
  ): MachineSkillOperationPreview {
    val old = workspace.readRecord(candidate.name)
    val oldSnapshot = old?.let { workspace.snapshotRoot(it.name, it.activeContentHash) }
    val snapshot = workspace.snapshotRoot(candidate.name, candidate.contentHash)
    val allTargets = (old?.selectedTargets.orEmpty() + targets)
    val targetPaths = allTargets.associateWith { it.skillsPath.resolve(candidate.name) }
    val fixedPaths = listOf(workspace.sourceRoot(candidate.name), workspace.recordPath(candidate.name), snapshot)
    val observations = workspace.observe(fixedPaths + targetPaths.values).associateBy { it.path }
    fun mutation(resource: MachineSkillResourceKind, operation: MachineSkillOperation, outcome: MachineSkillOutcome, path: Path, target: Path? = null) =
      MachineSkillMutation(resource, operation, outcome, path, observations.getValue(path), target)
    val mutations = mutableListOf<MachineSkillMutation>()
    val sourceOutcome = if (writeCandidate) if (old == null) MachineSkillOutcome.CREATE else MachineSkillOutcome.REPLACE else MachineSkillOutcome.UNCHANGED
    mutations += mutation(MachineSkillResourceKind.MANAGED_SOURCE, if (old == null) MachineSkillOperation.CREATE else MachineSkillOperation.REPLACE, sourceOutcome, workspace.sourceRoot(candidate.name))
    mutations += mutation(MachineSkillResourceKind.SNAPSHOT, MachineSkillOperation.CREATE, if (workspace.snapshotHealthy(candidate)) MachineSkillOutcome.UNCHANGED else MachineSkillOutcome.CREATE, snapshot)
    mutations += mutation(MachineSkillResourceKind.RECORD, if (old == null) MachineSkillOperation.CREATE else MachineSkillOperation.REPLACE, if (old == desiredRecord) MachineSkillOutcome.UNCHANGED else if (old == null) MachineSkillOutcome.CREATE else MachineSkillOutcome.REPLACE, workspace.recordPath(candidate.name))
    targetPaths.forEach { (target, path) ->
      val selected = target in targets
      val expectedOld = oldSnapshot != null && observations.getValue(path).normalizedLinkTarget == oldSnapshot
      val outcome = when {
        selected && observations.getValue(path).normalizedLinkTarget == snapshot -> MachineSkillOutcome.UNCHANGED
        selected && (observations.getValue(path).kind == NoFollowEntryKind.ABSENT || expectedOld) -> if (expectedOld) MachineSkillOutcome.RETARGET else MachineSkillOutcome.CREATE
        !selected && expectedOld -> MachineSkillOutcome.REMOVE
        !selected -> MachineSkillOutcome.SKIPPED
        else -> MachineSkillOutcome.CONFLICT
      }
      mutations += mutation(MachineSkillResourceKind.AGENT_LINK, if (!selected) MachineSkillOperation.REMOVE else if (expectedOld) MachineSkillOperation.RETARGET else MachineSkillOperation.CREATE, outcome, path, snapshot.takeIf { selected })
    }
    val prepared = prepare(kind, candidate.name, mutations, candidate.takeIf { writeCandidate }, desiredRecord, old)
    val outcomes = targetPaths.map { (target, path) ->
      val value = prepared.plan.mutations.single { it.path == path }.outcome
      MachineSkillServiceOutcome(value.toServiceKind(), value.name.lowercase(), value.name, candidate.name, target, path)
    }
    return MachineSkillOperationPreview(kind, candidate.name, prepared = prepared, outcomes = outcomes)
  }

  private fun prepare(kind: MachineSkillMutationKind, name: String, mutations: List<MachineSkillMutation>, candidate: OpaqueSkillBundle?, desired: ManagedSkillRecord?, old: ManagedSkillRecord?, cleanup: Set<Path> = emptySet()): PreparedMachineSkillMutation {
    val references = workspace.snapshotReferences()
    val ownership = mutations.associate { mutation ->
      val expectedSnapshot = old?.let { workspace.snapshotRoot(it.name, it.activeContentHash) }
      mutation.path to OwnershipProof(mutation.resource != MachineSkillResourceKind.AGENT_LINK || mutation.expected.normalizedLinkTarget == expectedSnapshot, workspace.recordDigest(name), "captured-preview")
    }
    val preconditions = MachineSkillPreconditions(
      mutations.map { it.expected }, workspace.recordDigest(name), old?.contractVersion, old?.activeContentHash,
      candidate?.let { BundleIdentity(it.name, it.contentHash, it.totalBytes, it.files.size) },
      desired?.selectedTargets.orEmpty().mapTo(mutableSetOf()) { it.stableIdentity }, ownership,
      references.references, references.complete, workspace.symlinkCapability(),
    )
    val plan = MachineSkillMutationPlan(kind, name, mutations, preconditions)
    return PreparedMachineSkillMutation(plan, candidate, desired, cleanup)
  }

  private fun blocked(kind: MachineSkillMutationKind, name: String, code: String, target: AgentSkillTargetId? = null) =
    MachineSkillOperationPreview(kind, name, outcomes = listOf(MachineSkillServiceOutcome(MachineSkillServiceOutcomeKind.BLOCKED, code, code, name, target)))

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
}
