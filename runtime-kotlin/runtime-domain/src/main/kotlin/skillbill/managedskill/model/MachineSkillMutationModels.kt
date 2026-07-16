package skillbill.managedskill.model

import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

enum class MachineSkillResourceKind { MANAGED_SOURCE, RECORD, SNAPSHOT, AGENT_LINK }
enum class MachineSkillOperation { CREATE, REPLACE, RETARGET, REMOVE, OBSERVE }
enum class MachineSkillOutcome { CREATE, REPLACE, RETARGET, REMOVE, UNCHANGED, CONFLICT, WARNING, SKIPPED, BLOCKED }
enum class NoFollowEntryKind { ABSENT, REGULAR_FILE, DIRECTORY, SYMBOLIC_LINK, SPECIAL }
enum class IdentityCapability { AVAILABLE, UNAVAILABLE }
enum class SymlinkCapability { AVAILABLE, UNAVAILABLE, NOT_REQUIRED }

data class FileSystemIdentity(val capability: IdentityCapability, val providerKey: String? = null) {
  init {
    require((capability == IdentityCapability.AVAILABLE) == (providerKey != null))
  }
}

data class PathObservation(
  val path: Path,
  val kind: NoFollowEntryKind,
  val identity: FileSystemIdentity,
  val rawLinkTarget: String? = null,
  val normalizedLinkTarget: Path? = null,
)

data class BundleIdentity(val name: String, val contentHash: String, val totalBytes: Long, val fileCount: Int)
data class OwnershipProof(val managed: Boolean, val recordDigest: String?, val reason: String)
data class MachineSkillConflict(val code: String, val path: Path?, val message: String)
data class MachineSkillWarning(val code: String, val path: Path?, val message: String)
data class AdoptionReplacement(val path: Path, val identity: FileSystemIdentity)
data class MachineSkillDesiredState(
  val kind: MachineSkillMutationKind,
  val skillName: String,
  val desiredMutations: List<MachineSkillMutation>,
  val preconditions: MachineSkillPreconditions,
  val adoptionReplacements: List<AdoptionReplacement> = emptyList(),
  val warnings: List<MachineSkillWarning> = emptyList(),
)

data class MachineSkillPreconditions(
  val observations: List<PathObservation>,
  val recordDigest: String?,
  val recordContractVersion: String?,
  val activeHash: String?,
  val candidateBundle: BundleIdentity?,
  val targetIdentities: Set<String>,
  val ownershipProofs: Map<Path, OwnershipProof>,
  val snapshotReferences: Set<Path>,
  val referenceDiscoveryComplete: Boolean,
  val symlinkCapability: SymlinkCapability,
)

data class MachineSkillMutation(
  val resource: MachineSkillResourceKind,
  val operation: MachineSkillOperation,
  val outcome: MachineSkillOutcome,
  val path: Path,
  val expected: PathObservation,
  val desiredLinkTarget: Path? = null,
)

class MachineSkillMutationPlan(
  val kind: MachineSkillMutationKind,
  val skillName: String,
  mutations: List<MachineSkillMutation>,
  preconditions: MachineSkillPreconditions,
  conflicts: List<MachineSkillConflict> = emptyList(),
  warnings: List<MachineSkillWarning> = emptyList(),
) {
  val mutations =
    immutable(mutations.sortedWith(compareBy({ it.resource.ordinal }, { it.path.toString() })))
  val preconditions =
    preconditions.copy(
      observations = immutable(preconditions.observations.map { it.copy() }),
      targetIdentities = Collections.unmodifiableSet(preconditions.targetIdentities.toSet()),
      ownershipProofs = Collections.unmodifiableMap(preconditions.ownershipProofs.toMap()),
      snapshotReferences = Collections.unmodifiableSet(preconditions.snapshotReferences.toSet()),
    )
  val conflicts = immutable(conflicts.toList())
  val warnings = immutable(warnings.toList())
  val planId: String = canonicalIdentity()

  private fun canonicalIdentity(): String {
    val canonical = buildString {
      append(kind).append('|').append(skillName).append('|')
      mutations.forEach {
        append(it.resource)
          .append(':')
          .append(it.operation)
          .append(':')
          .append(it.outcome)
          .append(':')
          .append(it.path)
          .append(':')
          .append(it.desiredLinkTarget)
          .append(';')
      }
      preconditions.observations.sortedBy { it.path.toString() }.forEach {
        append(it.path)
          .append(':')
          .append(it.kind)
          .append(':')
          .append(it.identity.providerKey)
          .append(':')
          .append(it.rawLinkTarget)
          .append(';')
      }
      append(preconditions.recordDigest)
        .append('|')
        .append(preconditions.activeHash)
        .append('|')
        .append(preconditions.candidateBundle)
      preconditions.targetIdentities.sorted().forEach(::append)
      preconditions.ownershipProofs.entries.sortedBy { it.key.toString() }.forEach { append(it.key).append(it.value) }
      preconditions.snapshotReferences.map(Path::toString).sorted().forEach(::append)
      append(preconditions.referenceDiscoveryComplete).append('|').append(preconditions.symlinkCapability)
      conflicts.forEach { append(it.code).append(it.path).append(it.message) }
      warnings.forEach { append(it.code).append(it.path).append(it.message) }
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
  }
}

data class PreparedMachineSkillMutation(
  val plan: MachineSkillMutationPlan,
  val candidate: OpaqueSkillBundle? = null,
  val desiredRecord: ManagedSkillRecord? = null,
  val snapshotsToRemove: Set<Path> = emptySet(),
) {
  init {
    require(candidate == null || candidate.name == plan.skillName)
    require(desiredRecord == null || desiredRecord.name == plan.skillName)
    require(
      candidate == null || plan.preconditions.candidateBundle == BundleIdentity(
        candidate.name,
        candidate.contentHash,
        candidate.totalBytes,
        candidate.files.size,
      ),
    )
  }
}

private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values)

data class StalePrecondition(val code: String, val path: Path?, val expected: String, val actual: String)
sealed interface MachineSkillApplyResult {
  data class Applied(val planId: String) : MachineSkillApplyResult
  data class Stale(val failures: List<StalePrecondition>) : MachineSkillApplyResult
  data class Blocked(val reason: String) : MachineSkillApplyResult
}
