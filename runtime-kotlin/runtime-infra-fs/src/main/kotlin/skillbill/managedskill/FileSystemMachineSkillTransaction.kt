package skillbill.managedskill

import skillbill.managedskill.model.MachineSkillApplyResult
import skillbill.managedskill.model.MachineSkillMutation
import skillbill.managedskill.model.MachineSkillMutationPlan
import skillbill.managedskill.model.MachineSkillOperation
import skillbill.managedskill.model.MachineSkillOutcome
import skillbill.managedskill.model.MachineSkillPreconditions
import skillbill.managedskill.model.MachineSkillResourceKind
import skillbill.managedskill.model.NoFollowEntryKind
import skillbill.managedskill.model.PreparedMachineSkillMutation
import skillbill.managedskill.model.SymlinkCapability
import skillbill.ports.managedskill.MachineSkillTransactionPort
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

class FileSystemMachineSkillTransaction(
  home: Path,
  targetRoots: List<Path>,
) : MachineSkillTransactionPort {
  private val normalizedHome = home.toAbsolutePath().normalize()
  private val stateRoot = normalizedHome.resolve(".skill-bill")
  private val store = FileManagedSkillRecordStore(normalizedHome)
  private val inspector = FileSystemMachineSkillMutationInspector(normalizedHome, targetRoots)

  override fun currentPreconditions(plan: MachineSkillMutationPlan): MachineSkillPreconditions {
    val references = inspector.snapshotReferences()
    val record = runCatching { store.read(plan.skillName) }.getOrNull()
    return plan.preconditions.copy(
      observations = inspector.observe(plan.preconditions.observations.map { it.path }),
      recordDigest = store.digest(plan.skillName),
      recordContractVersion = record?.contractVersion,
      activeHash = record?.activeContentHash,
      snapshotReferences = references.references,
      referenceDiscoveryComplete = references.complete,
      symlinkCapability = inspector.symlinkCapability(),
    )
  }

  override fun apply(prepared: PreparedMachineSkillMutation): MachineSkillApplyResult {
    validationFailure(prepared)?.let { return MachineSkillApplyResult.Blocked(it) }
    val execution = MachineSkillTransactionExecution(stateRoot, store, inspector, prepared)
    return runCatching {
      execution.execute()
      MachineSkillApplyResult.Applied(prepared.plan.planId)
    }.getOrElse { failure ->
      execution.rollback()
      MachineSkillApplyResult.Blocked(failure.message ?: failure::class.java.simpleName)
    }
  }

  override fun recoverIncompleteTransactions(): List<String> = emptyList()

  private fun validationFailure(prepared: PreparedMachineSkillMutation): String? = when {
    currentPreconditions(prepared.plan) != prepared.plan.preconditions -> {
      "Mutation preconditions changed before the first write"
    }
    prepared.plan.conflicts.isNotEmpty() || prepared.plan.mutations.any {
      it.outcome in setOf(MachineSkillOutcome.CONFLICT, MachineSkillOutcome.BLOCKED)
    } -> "Mutation contains conflicts or blocked outcomes"
    prepared.plan.mutations.any { it.resource == MachineSkillResourceKind.AGENT_LINK } &&
      inspector.symlinkCapability() != SymlinkCapability.AVAILABLE -> "Symbolic links are unavailable"
    else -> null
  }
}

private class MachineSkillTransactionExecution(
  private val stateRoot: Path,
  private val store: FileManagedSkillRecordStore,
  private val inspector: FileSystemMachineSkillMutationInspector,
  private val prepared: PreparedMachineSkillMutation,
) {
  private val plan = prepared.plan
  private val sourcePublication = MachineSkillSourcePublication(stateRoot.resolve("managed-skills"))
  private var stagedSource: Path? = null
  private var promotedSource: PromotedMachineSkillSource? = null
  private var createdSnapshot: Path? = null
  private val linkBackups = mutableListOf<Pair<Path, Path?>>()
  private val resourceBackups = mutableListOf<Pair<Path, Path>>()
  private val expectedDigest = plan.preconditions.recordDigest ?: FileManagedSkillRecordStore.EXPECTED_ABSENT
  private val previousRecord = runCatching { store.read(plan.skillName) }.getOrNull()
  private var recordWritten = false

  fun execute() {
    publishSnapshot()
    publishSource()
    backupRemovedResources()
    activateLinks()
    writeRecord()
    commit()
  }

  fun rollback() {
    linkBackups.asReversed().forEach { (path, backup) ->
      runCatching {
        Files.deleteIfExists(path)
        if (backup != null) Files.move(backup, path, ATOMIC_MOVE)
      }
    }
    promotedSource?.let { runCatching { sourcePublication.rollback(it) } }
    resourceBackups.asReversed().forEach { (path, backup) ->
      runCatching {
        if (Files.exists(backup, NOFOLLOW_LINKS)) Files.move(backup, path, ATOMIC_MOVE)
      }
    }
    restoreRecord()
    createdSnapshot?.let { runCatching { deleteMachineSkillTree(it) } }
    stagedSource?.let { runCatching { deleteMachineSkillTree(it) } }
  }

  private fun publishSnapshot() {
    val candidate = prepared.candidate ?: return
    val destination = stateRoot.resolve("installed-skills/${candidate.name}-${candidate.contentHash}")
    val existed = Files.exists(destination, NOFOLLOW_LINKS)
    val published = MachineSkillSnapshotPublication(stateRoot.resolve("installed-skills")).publish(candidate)
    createdSnapshot = published.takeUnless { existed }
  }

  private fun publishSource() {
    val candidate = prepared.candidate ?: return
    val sourceChanges = plan.mutations.any {
      it.resource == MachineSkillResourceKind.MANAGED_SOURCE && it.outcome != MachineSkillOutcome.UNCHANGED
    }
    if (!sourceChanges) return
    stagedSource = sourcePublication.stage(candidate)
    promotedSource = sourcePublication.promote(plan.skillName, requireNotNull(stagedSource))
    stagedSource = null
  }

  private fun backupRemovedResources() {
    plan.mutations.filter { mutation ->
      mutation.operation == MachineSkillOperation.REMOVE &&
        mutation.resource in setOf(MachineSkillResourceKind.MANAGED_SOURCE, MachineSkillResourceKind.RECORD)
    }.forEach { mutation ->
      val current = inspector.observe(listOf(mutation.path)).single()
      require(current == mutation.expected) { "Managed resource changed before deletion: ${mutation.path}" }
      if (Files.exists(mutation.path, NOFOLLOW_LINKS)) {
        val backup = mutation.path.resolveSibling(".${mutation.path.fileName}.backup-${System.nanoTime()}")
        Files.move(mutation.path, backup, ATOMIC_MOVE)
        resourceBackups += mutation.path to backup
      }
    }
  }

  private fun activateLinks() {
    plan.mutations.filter { it.resource == MachineSkillResourceKind.AGENT_LINK }
      .filterNot(::linkMutationNeedsNoWrite)
      .forEach { mutation ->
        val current = inspector.observe(listOf(mutation.path)).single()
        require(current == mutation.expected) { "Agent link changed before activation: ${mutation.path}" }
        val backup = current.takeUnless { it.kind == NoFollowEntryKind.ABSENT }?.let {
          mutation.path.resolveSibling(".${mutation.path.fileName}.backup-${System.nanoTime()}")
        }
        if (backup != null) Files.move(mutation.path, backup, ATOMIC_MOVE)
        linkBackups += mutation.path to backup
        if (mutation.operation != MachineSkillOperation.REMOVE) {
          Files.createDirectories(mutation.path.parent)
          Files.createSymbolicLink(mutation.path, requireNotNull(mutation.desiredLinkTarget))
        }
      }
  }

  private fun writeRecord() {
    prepared.desiredRecord?.let {
      store.write(it, expectedDigest)
      recordWritten = true
    }
  }

  private fun commit() {
    promotedSource?.let(sourcePublication::commit)
    linkBackups.mapNotNull { it.second }.forEach(::deleteMachineSkillTree)
    resourceBackups.map { it.second }.forEach(::deleteMachineSkillTree)
    prepared.snapshotsToRemove.forEach { snapshot -> runCatching { deleteMachineSkillTree(snapshot) } }
  }

  private fun restoreRecord() {
    if (!recordWritten) return
    runCatching {
      if (previousRecord == null) {
        Files.deleteIfExists(store.recordPath(plan.skillName))
      } else {
        store.write(previousRecord, store.digest(plan.skillName))
      }
    }
  }
}

private fun linkMutationNeedsNoWrite(mutation: MachineSkillMutation): Boolean {
  val outcomes = setOf(
    MachineSkillOutcome.UNCHANGED,
    MachineSkillOutcome.SKIPPED,
    MachineSkillOutcome.WARNING,
  )
  return mutation.outcome in outcomes
}

private fun deleteMachineSkillTree(path: Path) {
  if (!Files.exists(path, NOFOLLOW_LINKS)) return
  Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
