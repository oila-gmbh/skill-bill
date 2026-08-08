package skillbill.contracts.goalplanning

import org.yaml.snakeyaml.Yaml

/** Loud-fail signal for a missing, empty, or malformed discovery-exclusion contract. */
class GoalPlanningDiscoveryExclusionsException(message: String) : IllegalStateException(message)

/**
 * The checked-in goal planning / preplanning discovery exclusion contract, staged onto the
 * classpath from `orchestration/contracts/goal-planning-discovery-exclusions.yaml`.
 *
 * Listed roots are anchored repo-relative directory prefixes and listed directory names are
 * denied at any depth; neither ever contributes planning memory. Discovery and the
 * shared-context packet migration both deny through this single source; a missing or
 * malformed contract loud-fails rather than degrading to allow-all.
 */
object GoalPlanningDiscoveryExclusions {
  const val CONTRACT_VERSION = "0.2"
  const val RESOURCE_PATH = "skillbill/contracts/goal-planning-discovery-exclusions.yaml"
  const val CONTRACT_FILE = "orchestration/contracts/goal-planning-discovery-exclusions.yaml"

  private val contract: Contract by lazy { parse(readContract()) }

  val excludedRoots: List<String> get() = contract.roots

  val excludedDirectoryNames: List<String> get() = contract.directoryNames

  /**
   * Deny predicate over '/'-joined repo-relative paths. Roots match as whole-segment anchored
   * prefixes, so `platform-packsX/` passes; directory names match at any path position, so nested
   * `runtime-kotlin/foo/build/` is denied exactly like repo-root `build/`.
   */
  fun isExcluded(relativePath: String): Boolean {
    val normalized = relativePath.removePrefix("./").trim('/')
    if (normalized.isEmpty()) return false
    if (excludedRoots.any { root -> "$normalized/".startsWith(root) }) return true
    val names = excludedDirectoryNames
    return normalized.split("/").any { segment -> segment in names }
  }

  internal data class Contract(val roots: List<String>, val directoryNames: List<String>)

  private fun readContract(): String =
    javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)?.use { stream -> stream.readBytes().decodeToString() }
      ?: throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion contract is missing from the classpath at $RESOURCE_PATH",
      )

  internal fun parse(document: String): Contract {
    val root = runCatching { Yaml().load<Any?>(document) }.getOrNull() as? Map<*, *>
      ?: throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion contract is not a YAML mapping",
      )
    requireSupportedVersion(root["contract_version"])
    return Contract(
      roots = requiredStringList(root, "excluded_roots").onEach(::requireNormalizedRoot),
      directoryNames = requiredStringList(root, "excluded_directory_names").onEach(::requireBareDirectoryName),
    )
  }

  private fun requireSupportedVersion(version: Any?) {
    if (version != CONTRACT_VERSION) {
      throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion contract_version '$version' is unsupported; expected '$CONTRACT_VERSION'",
      )
    }
  }

  private fun requiredStringList(root: Map<*, *>, key: String): List<String> {
    val entries = (root[key] as? List<*>)?.map { entry ->
      entry as? String ?: throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion $key entry '$entry' is not a string",
      )
    }.orEmpty()
    if (entries.isEmpty()) {
      throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion contract declares no $key",
      )
    }
    return entries
  }

  private fun requireBareDirectoryName(name: String) {
    val invalid = name.isBlank() || name in setOf(".", "..") || name.any { char -> char == '/' || char == '\\' }
    if (invalid) {
      throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery excluded_directory_names entry '$name' must be a bare directory name",
      )
    }
  }

  private fun requireNormalizedRoot(root: String) {
    val invalid = root.isBlank() ||
      !root.endsWith("/") ||
      root.startsWith("/") ||
      root.startsWith("./") ||
      root.contains("\\") ||
      root.split("/").any { segment -> segment == ".." || segment == "." }
    if (invalid) {
      throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion root '$root' must be a normalized repo-relative prefix ending in '/'",
      )
    }
  }
}
