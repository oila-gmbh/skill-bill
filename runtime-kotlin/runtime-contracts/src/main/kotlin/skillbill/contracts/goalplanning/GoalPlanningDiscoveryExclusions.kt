package skillbill.contracts.goalplanning

import org.yaml.snakeyaml.Yaml

/** Loud-fail signal for a missing, empty, or malformed discovery-exclusion contract. */
class GoalPlanningDiscoveryExclusionsException(message: String) : IllegalStateException(message)

/**
 * The checked-in goal planning / preplanning discovery exclusion contract, staged onto the
 * classpath from `orchestration/contracts/goal-planning-discovery-exclusions.yaml`.
 *
 * Listed roots are repo-relative directory prefixes that never contribute planning memory.
 * Discovery and the shared-context packet migration both deny through this single source; a
 * missing or malformed contract loud-fails rather than degrading to allow-all.
 */
object GoalPlanningDiscoveryExclusions {
  const val CONTRACT_VERSION = "0.1"
  const val RESOURCE_PATH = "skillbill/contracts/goal-planning-discovery-exclusions.yaml"
  const val CONTRACT_FILE = "orchestration/contracts/goal-planning-discovery-exclusions.yaml"

  val excludedRoots: List<String> by lazy { parse(readContract()) }

  /** Prefix-deny predicate over '/'-joined repo-relative paths; segment-aware, so `platform-packsX/` passes. */
  fun isExcluded(relativePath: String): Boolean {
    val normalized = relativePath.removePrefix("./").trimStart('/')
    if (normalized.isEmpty()) return false
    val candidate = if (normalized.endsWith("/")) normalized else "$normalized/"
    return excludedRoots.any { root -> candidate.startsWith(root) }
  }

  private fun readContract(): String =
    javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)?.use { stream -> stream.readBytes().decodeToString() }
      ?: throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion contract is missing from the classpath at $RESOURCE_PATH",
      )

  internal fun parse(document: String): List<String> {
    val root = runCatching { Yaml().load<Any?>(document) }.getOrNull() as? Map<*, *>
      ?: throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion contract is not a YAML mapping",
      )
    val version = root["contract_version"]
    if (version != CONTRACT_VERSION) {
      throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion contract_version '$version' is unsupported; expected '$CONTRACT_VERSION'",
      )
    }
    val roots = (root["excluded_roots"] as? List<*>)?.map { entry ->
      entry as? String ?: throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion root '$entry' is not a string",
      )
    }.orEmpty()
    if (roots.isEmpty()) {
      throw GoalPlanningDiscoveryExclusionsException(
        "goal planning discovery exclusion contract declares no excluded_roots",
      )
    }
    roots.forEach(::requireNormalizedRoot)
    return roots
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
