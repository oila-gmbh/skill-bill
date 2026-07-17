package skillbill.managedskill.model

import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.Locale

const val MANAGED_SKILL_RECORD_CONTRACT_VERSION: String = "0.1"

enum class ManagedSkillSourceKind { FILE, DIRECTORY, ADOPTED }
enum class MachineSkillOwnership { PRODUCT, MANAGED, UNMANAGED, CONFLICT }
enum class MachineSkillHealth { HEALTHY, MISSING, BROKEN_LINK, DIVERGENT, CORRUPT, HASH_MISMATCH, ORPHAN }
enum class MachineSkillMutationKind { INSTALL, UPDATE, ADOPT, EDIT, MANAGE_TARGETS, REPAIR, DELETE }

enum class MachineSkillEntryKind { FILE, DIRECTORY, SYMLINK, OTHER, ABSENT }
enum class MachineSkillLinkHealth { NOT_A_LINK, HEALTHY, BROKEN, EXTERNAL, EXPECTED_BROKEN, EXPECTED_MISMATCH }
enum class MachineSkillIssueSeverity { INFO, WARNING, ERROR }

data class MachineSkillIssue(
  val code: String,
  val message: String,
  val severity: MachineSkillIssueSeverity = MachineSkillIssueSeverity.WARNING,
)

data class AgentSkillTargetState(
  val id: AgentSkillTargetId,
  val displayName: String,
  val detected: Boolean,
  val selected: Boolean,
  val directoryPresent: Boolean,
  val issues: List<MachineSkillIssue> = emptyList(),
)

data class MachineSkillOccurrence(
  val target: AgentSkillTargetId,
  val rawName: String,
  val path: Path,
  val entryKind: MachineSkillEntryKind,
  val ownership: MachineSkillOwnership,
  val linkHealth: MachineSkillLinkHealth,
  val contentHash: String? = null,
  val provenance: Set<String> = emptySet(),
  val issues: List<MachineSkillIssue> = emptyList(),
)

data class MachineSkillTargetPresence(
  val target: AgentSkillTargetId,
  val present: Boolean,
  val occurrences: List<MachineSkillOccurrence> = emptyList(),
)

data class MachineSkillInventoryRow(
  val normalizedName: String,
  val displayName: String,
  val ownership: MachineSkillOwnership,
  val health: MachineSkillHealth,
  val targetPresence: List<MachineSkillTargetPresence>,
  val contentHashes: Set<String>,
  val divergent: Boolean,
  val issues: List<MachineSkillIssue> = emptyList(),
)

data class MachineSkillInventoryDiagnostic(
  val kind: String,
  val path: Path?,
  val message: String,
)

class MachineSkillInventorySnapshot(
  targets: List<AgentSkillTargetState>,
  rows: List<MachineSkillInventoryRow>,
  productDiagnostics: List<MachineSkillOccurrence> = emptyList(),
  diagnostics: List<MachineSkillInventoryDiagnostic> = emptyList(),
) {
  val targets: List<AgentSkillTargetState> =
    immutableList(targets.map { it.copy(issues = immutableList(it.issues.toList())) })
  val rows: List<MachineSkillInventoryRow> = immutableList(
    rows.map { row ->
      row.copy(
        targetPresence = immutableList(
          row.targetPresence.map { presence ->
            presence.copy(occurrences = immutableList(presence.occurrences.map(::copyOccurrence)))
          },
        ),
        contentHashes = Collections.unmodifiableSet(row.contentHashes.toSet()),
        issues = immutableList(row.issues.toList()),
      )
    },
  )
  val productDiagnostics: List<MachineSkillOccurrence> = immutableList(productDiagnostics.map(::copyOccurrence))
  val diagnostics: List<MachineSkillInventoryDiagnostic> = immutableList(diagnostics.toList())
}

private fun copyOccurrence(value: MachineSkillOccurrence) = value.copy(
  provenance = Collections.unmodifiableSet(value.provenance.toSet()),
  issues = immutableList(value.issues.toList()),
)

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(values)

fun normalizeManagedSkillName(rawName: String): String? = rawName.trim().lowercase(Locale.ROOT).takeIf {
  runCatching { requireSafeManagedSkillName(it) }.isSuccess
}

class AgentSkillTargetId(provider: String, skillsPath: Path) {
  val provider: String = provider.trim().lowercase(Locale.ROOT)
  val skillsPath: Path = skillsPath.normalize()
  init {
    require(this.provider.matches(Regex("^[a-z0-9][a-z0-9-]{0,62}$"))) {
      "Agent provider must be a lowercase safe identifier."
    }
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

class OpaqueSkillBundleFile(val relativePath: String, content: ByteArray) {
  private val capturedContent = content.copyOf()
  val content: ByteArray get() = capturedContent.copyOf()

  override fun equals(other: Any?): Boolean =
    other is OpaqueSkillBundleFile && relativePath == other.relativePath && capturedContent.contentEquals(
      other.capturedContent,
    )

  override fun hashCode(): Int = 31 * relativePath.hashCode() + capturedContent.contentHashCode()
}

class OpaqueSkillBundle(
  val name: String,
  val description: String,
  val source: Path,
  files: List<OpaqueSkillBundleFile>,
  val totalBytes: Long,
  val contentHash: String,
) {
  val files: List<OpaqueSkillBundleFile> = Collections.unmodifiableList(files.toList())
}

fun requireSafeManagedSkillName(name: String, protectedNames: Set<String> = emptySet()): String {
  require(name.matches(Regex("^[a-z0-9][a-z0-9-]{0,62}$"))) {
    "Skill name must be a lowercase safe path segment containing only letters, digits, and hyphens."
  }
  require(protectedNames.none { it.equals(name, ignoreCase = true) }) { "Skill name '$name' is protected." }
  return name
}
