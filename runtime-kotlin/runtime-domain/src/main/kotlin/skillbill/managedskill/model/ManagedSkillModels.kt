package skillbill.managedskill.model

import java.nio.file.Path
import java.time.Instant

const val MANAGED_SKILL_RECORD_CONTRACT_VERSION: String = "0.1"

enum class ManagedSkillSourceKind { FILE, DIRECTORY, ADOPTED }
enum class MachineSkillOwnership { PRODUCT, MANAGED, UNMANAGED, CONFLICT }
enum class MachineSkillHealth { HEALTHY, MISSING, BROKEN_LINK, DIVERGENT, CORRUPT, HASH_MISMATCH, ORPHAN }
enum class MachineSkillMutationKind { INSTALL, UPDATE, ADOPT, EDIT, MANAGE_TARGETS, REPAIR, DELETE }

class AgentSkillTargetId(provider: String, skillsPath: Path) {
  val provider: String = provider.trim()
  val skillsPath: Path = skillsPath.toAbsolutePath().normalize()
  init {
    require(this.provider.isNotBlank()) { "Agent provider must not be blank." }
    require(skillsPath.isAbsolute) { "Agent target path must be absolute." }
  }

  val stableIdentity: String = "${this.provider}:${this.skillsPath}"

  override fun equals(other: Any?): Boolean =
    other is AgentSkillTargetId && provider == other.provider && skillsPath == other.skillsPath

  override fun hashCode(): Int = 31 * provider.hashCode() + skillsPath.hashCode()

  override fun toString(): String = stableIdentity
}

data class ManagedSkillRecord(
  val name: String,
  val sourceKind: ManagedSkillSourceKind,
  val sourcePath: Path,
  val activeContentHash: String,
  val selectedTargets: Set<AgentSkillTargetId>,
  val importedAt: Instant,
  val updatedAt: Instant,
  val contractVersion: String = MANAGED_SKILL_RECORD_CONTRACT_VERSION,
) {
  init {
    require(selectedTargets.isNotEmpty()) { "At least one agent target must be selected." }
  }
}

data class OpaqueSkillBundleFile(val relativePath: String, val content: ByteArray) {
  override fun equals(other: Any?): Boolean =
    other is OpaqueSkillBundleFile && relativePath == other.relativePath && content.contentEquals(other.content)

  override fun hashCode(): Int = 31 * relativePath.hashCode() + content.contentHashCode()
}

data class OpaqueSkillBundle(
  val name: String,
  val description: String,
  val source: Path,
  val files: List<OpaqueSkillBundleFile>,
  val totalBytes: Long,
  val contentHash: String,
)

data class MachineSkillMutation(
  val path: Path,
  val action: String,
  val expectedType: String?,
  val expectedLinkTarget: Path? = null,
)

data class MachineSkillMutationPlan(
  val kind: MachineSkillMutationKind,
  val skillName: String,
  val mutations: List<MachineSkillMutation>,
  val recordDigest: String?,
)

fun requireSafeManagedSkillName(name: String, protectedNames: Set<String> = emptySet()): String {
  require(name.matches(Regex("^[a-z0-9][a-z0-9-]{0,62}$"))) {
    "Skill name must be a lowercase safe path segment containing only letters, digits, and hyphens."
  }
  require(protectedNames.none { it.equals(name, ignoreCase = true) }) { "Skill name '$name' is protected." }
  return name
}
