package skillbill.managedskill

import skillbill.managedskill.model.MachineSkillApplyResult
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

  override fun currentPreconditions(plan: skillbill.managedskill.model.MachineSkillMutationPlan): MachineSkillPreconditions {
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
    val plan = prepared.plan
    if (currentPreconditions(plan) != plan.preconditions) {
      return MachineSkillApplyResult.Blocked("Mutation preconditions changed before the first write")
    }
    if (plan.conflicts.isNotEmpty() || plan.mutations.any { it.outcome in setOf(MachineSkillOutcome.CONFLICT, MachineSkillOutcome.BLOCKED) }) {
      return MachineSkillApplyResult.Blocked("Mutation contains conflicts or blocked outcomes")
    }
    if (plan.mutations.any { it.resource == MachineSkillResourceKind.AGENT_LINK } &&
      inspector.symlinkCapability() != SymlinkCapability.AVAILABLE
    ) return MachineSkillApplyResult.Blocked("Symbolic links are unavailable")

    val sourcePublication = MachineSkillSourcePublication(stateRoot.resolve("managed-skills"))
    var stagedSource: Path? = null
    var promotedSource: PromotedMachineSkillSource? = null
    var createdSnapshot: Path? = null
    val linkBackups = mutableListOf<Pair<Path, Path?>>()
    val resourceBackups = mutableListOf<Pair<Path, Path>>()
    val expectedDigest = plan.preconditions.recordDigest ?: FileManagedSkillRecordStore.EXPECTED_ABSENT
    val previousRecord = runCatching { store.read(plan.skillName) }.getOrNull()
    var recordWritten = false
    try {
      val candidate = prepared.candidate
      if (candidate != null) {
        val destination = stateRoot.resolve("installed-skills/${candidate.name}-${candidate.contentHash}")
        val existed = Files.exists(destination, NOFOLLOW_LINKS)
        val published = MachineSkillSnapshotPublication(stateRoot.resolve("installed-skills")).publish(candidate)
        createdSnapshot = published.takeUnless { existed }
      }
      if (candidate != null && plan.mutations.any { it.resource == MachineSkillResourceKind.MANAGED_SOURCE && it.outcome != MachineSkillOutcome.UNCHANGED }) {
        stagedSource = sourcePublication.stage(candidate)
        promotedSource = sourcePublication.promote(plan.skillName, stagedSource)
        stagedSource = null
      }
      plan.mutations.filter {
        it.operation == MachineSkillOperation.REMOVE && it.resource in setOf(
          MachineSkillResourceKind.MANAGED_SOURCE,
          MachineSkillResourceKind.RECORD,
        )
      }.forEach { mutation ->
        val current = inspector.observe(listOf(mutation.path)).single()
        require(current == mutation.expected) { "Managed resource changed before deletion: ${mutation.path}" }
        if (Files.exists(mutation.path, NOFOLLOW_LINKS)) {
          val backup = mutation.path.resolveSibling(".${mutation.path.fileName}.backup-${System.nanoTime()}")
          Files.move(mutation.path, backup, ATOMIC_MOVE)
          resourceBackups += mutation.path to backup
        }
      }
      plan.mutations.filter { it.resource == MachineSkillResourceKind.AGENT_LINK }.forEach { mutation ->
        if (mutation.outcome in setOf(MachineSkillOutcome.UNCHANGED, MachineSkillOutcome.SKIPPED, MachineSkillOutcome.WARNING)) return@forEach
        val current = inspector.observe(listOf(mutation.path)).single()
        require(current == mutation.expected) { "Agent link changed before activation: ${mutation.path}" }
        val backup = if (current.kind == NoFollowEntryKind.ABSENT) null else mutation.path.resolveSibling(".${mutation.path.fileName}.backup-${System.nanoTime()}")
        if (backup != null) Files.move(mutation.path, backup, ATOMIC_MOVE)
        linkBackups += mutation.path to backup
        if (mutation.operation != MachineSkillOperation.REMOVE) {
          val target = requireNotNull(mutation.desiredLinkTarget)
          Files.createDirectories(mutation.path.parent)
          Files.createSymbolicLink(mutation.path, target)
        }
      }
      prepared.desiredRecord?.let { store.write(it, expectedDigest); recordWritten = true }
      promotedSource?.let(sourcePublication::commit)
      linkBackups.mapNotNull { it.second }.forEach(::deleteTree)
      resourceBackups.map { it.second }.forEach(::deleteTree)
      prepared.snapshotsToRemove.forEach { snapshot -> runCatching { deleteTree(snapshot) } }
      return MachineSkillApplyResult.Applied(plan.planId)
    } catch (failure: Exception) {
      linkBackups.asReversed().forEach { (path, backup) ->
        runCatching { Files.deleteIfExists(path); if (backup != null) Files.move(backup, path, ATOMIC_MOVE) }
      }
      promotedSource?.let { runCatching { sourcePublication.rollback(it) } }
      resourceBackups.asReversed().forEach { (path, backup) ->
        runCatching { if (Files.exists(backup, NOFOLLOW_LINKS)) Files.move(backup, path, ATOMIC_MOVE) }
      }
      if (recordWritten) runCatching {
        if (previousRecord == null) Files.deleteIfExists(store.recordPath(plan.skillName))
        else store.write(previousRecord, store.digest(plan.skillName))
      }
      createdSnapshot?.let { runCatching { deleteTree(it) } }
      stagedSource?.let { runCatching { deleteTree(it) } }
      return MachineSkillApplyResult.Blocked(failure.message ?: failure::class.java.simpleName)
    }
  }

  override fun recoverIncompleteTransactions(): List<String> = emptyList()

  private fun deleteTree(path: Path) {
    if (!Files.exists(path, NOFOLLOW_LINKS)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
  }
}
