package skillbill.managedskill

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.time.Instant
import java.util.UUID

class FileSystemMachineSkillTransaction(
  home: Path,
  targetRoots: List<Path>,
  capabilityProbe: NativeSymlinkCapabilityProbe? = null,
) : MachineSkillTransactionPort {
  private val normalizedHome = home.toAbsolutePath().normalize()
  private val stateRoot = normalizedHome.resolve(".skill-bill")
  private val store = FileManagedSkillRecordStore(normalizedHome)
  private val inspector = capabilityProbe?.let {
    FileSystemMachineSkillMutationInspector(normalizedHome, targetRoots, it)
  } ?: FileSystemMachineSkillMutationInspector(normalizedHome, targetRoots)
  private val postMortems = FileMachineSkillPostMortemStore(normalizedHome)

  override fun currentPreconditions(plan: MachineSkillMutationPlan): MachineSkillPreconditions {
    val references = inspector.snapshotReferences()
    val record = runCatching { store.read(plan.skillName) }.getOrNull()
    return plan.preconditions.copy(
      observations = inspector.observe(plan.preconditions.observations.map { it.path }),
      recordDigest = runCatching { store.digest(plan.skillName) }.getOrNull(),
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
      val rollbackFailures = execution.rollback()
      val reason = failure.message ?: failure::class.java.simpleName
      if (rollbackFailures.isEmpty()) {
        MachineSkillApplyResult.Blocked(reason)
      } else {
        val id = persistPostMortem(prepared, reason, rollbackFailures)
        MachineSkillApplyResult.Blocked("$reason; rollback incomplete; post-mortem $id")
      }
    }
  }

  override fun recoverIncompleteTransactions(): List<String> = postMortems.unacknowledgedIds()

  private fun persistPostMortem(
    prepared: PreparedMachineSkillMutation,
    failure: String,
    rollbackFailures: List<String>,
  ): String {
    val id = UUID.randomUUID().toString()
    val mapper = ObjectMapper()
    val node = mapper.createObjectNode().apply {
      put("contract_version", "0.1")
      put("post_mortem_id", id)
      put("plan_id", prepared.plan.planId)
      put("created_at", Instant.now().toString())
      put("acknowledgement_status", "unacknowledged")
      putArray("affected_paths").also { array ->
        prepared.plan.mutations.map {
          it.path.toString()
        }.distinct().forEach(array::add)
      }
      putArray("attempted_operations").also { array ->
        prepared.plan.mutations.forEach { array.add("${it.operation}:${it.resource}:${it.path}") }
      }
      putArray("rollback_evidence").also { array -> rollbackFailures.forEach(array::add) }
      putArray("recovery_actions").add(
        "Inspect affected paths before retrying plan ${prepared.plan.planId}: $failure",
      )
    }
    postMortems.write(node)
    return id
  }

  private fun validationFailure(prepared: PreparedMachineSkillMutation): String? = when {
    !preconditionsMatch(prepared.plan.preconditions, currentPreconditions(prepared.plan)) -> {
      "Mutation preconditions changed before the first write"
    }
    prepared.plan.conflicts.isNotEmpty() || prepared.plan.mutations.any {
      it.outcome in setOf(MachineSkillOutcome.CONFLICT, MachineSkillOutcome.BLOCKED)
    } -> "Mutation contains conflicts or blocked outcomes"
    prepared.plan.mutations.any { it.resource == MachineSkillResourceKind.AGENT_LINK } &&
      inspector.symlinkCapability() != SymlinkCapability.AVAILABLE ->
      "Symbolic links are unavailable; on Windows enable Developer Mode or run elevated"
    else -> null
  }

  private fun preconditionsMatch(expected: MachineSkillPreconditions, actual: MachineSkillPreconditions): Boolean =
    expected.copy(observations = emptyList()) == actual.copy(observations = emptyList()) &&
      expected.observations.toSet() == actual.observations.toSet()
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

  fun rollback(): List<String> {
    val failures = mutableListOf<String>()
    fun restore(description: String, action: () -> Unit) {
      runCatching(
        action,
      ).exceptionOrNull()?.let {
        failures += "$description: ${it.message ?: it::class.java.simpleName}"
      }
    }
    linkBackups.asReversed().forEach { (path, backup) ->
      restore("restore link $path") {
        Files.deleteIfExists(path)
        if (backup != null) Files.move(backup, path, ATOMIC_MOVE)
      }
    }
    promotedSource?.let { restore("restore managed source") { sourcePublication.rollback(it) } }
    resourceBackups.asReversed().forEach { (path, backup) ->
      restore("restore resource $path") {
        if (Files.exists(backup, NOFOLLOW_LINKS)) Files.move(backup, path, ATOMIC_MOVE)
      }
    }
    restore("restore record") { restoreRecord() }
    createdSnapshot?.let { restore("remove snapshot $it") { deleteMachineSkillTree(it) } }
    stagedSource?.let { restore("remove staged source $it") { deleteMachineSkillTree(it) } }
    return failures
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
    if (previousRecord == null) {
      Files.deleteIfExists(store.recordPath(plan.skillName))
    } else {
      store.write(previousRecord, store.digest(plan.skillName))
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
