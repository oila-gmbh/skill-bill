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
    if (plan.conflicts.isNotEmpty() || plan.mutations.any { it.outcome in setOf(MachineSkillOutcome.CONFLICT, MachineSkillOutcome.BLOCKED) }) {
      return MachineSkillApplyResult.Blocked("Mutation contains conflicts or blocked outcomes")
    }
    if (plan.mutations.any { it.resource == MachineSkillResourceKind.AGENT_LINK } &&
      inspector.symlinkCapability() != SymlinkCapability.AVAILABLE
    ) return MachineSkillApplyResult.Blocked("Symbolic links are unavailable")

    val sourcePublication = MachineSkillSourcePublication(stateRoot.resolve("managed-skills"))
    var stagedSource: Path? = null
    var promotedSource: PromotedMachineSkillSource? = null
    val createdSnapshot = prepared.candidate?.let { bundle ->
      val destination = stateRoot.resolve("installed-skills/${bundle.name}-${bundle.contentHash}")
      val existed = Files.exists(destination, NOFOLLOW_LINKS)
      val published = MachineSkillSnapshotPublication(stateRoot.resolve("installed-skills")).publish(bundle)
      published.takeUnless { existed }
    }
    val linkBackups = mutableListOf<Pair<Path, Path?>>()
    val expectedDigest = plan.preconditions.recordDigest ?: FileManagedSkillRecordStore.EXPECTED_ABSENT
    try {
      val candidate = prepared.candidate
      if (candidate != null && plan.mutations.any { it.resource == MachineSkillResourceKind.MANAGED_SOURCE && it.outcome != MachineSkillOutcome.UNCHANGED }) {
        stagedSource = sourcePublication.stage(candidate)
        promotedSource = sourcePublication.promote(plan.skillName, stagedSource)
        stagedSource = null
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
      prepared.desiredRecord?.let { store.write(it, expectedDigest) }
      promotedSource?.let(sourcePublication::commit)
      linkBackups.mapNotNull { it.second }.forEach(::deleteTree)
      prepared.snapshotsToRemove.forEach(::deleteTree)
      return MachineSkillApplyResult.Applied(plan.planId)
    } catch (failure: Exception) {
      linkBackups.asReversed().forEach { (path, backup) ->
        runCatching { Files.deleteIfExists(path); if (backup != null) Files.move(backup, path, ATOMIC_MOVE) }
      }
      promotedSource?.let { runCatching { sourcePublication.rollback(it) } }
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
