package skillbill.contracts.goalplanning

import org.yaml.snakeyaml.Yaml
import skillbill.error.InvalidGoalPlanningDiscoveryExclusionsSchemaError
import kotlin.coroutines.cancellation.CancellationException

/**
 * The checked-in goal planning / preplanning discovery exclusion contract, staged onto the
 * classpath from `orchestration/contracts/goal-planning-discovery-exclusions.yaml` and governed by
 * the sibling `-schema.yaml`.
 *
 * Listed roots are anchored repo-relative directory prefixes and listed directory names are
 * denied at any depth; neither ever contributes planning memory. Discovery and the
 * shared-context packet migration both deny through this single source; a missing or
 * malformed contract loud-fails rather than degrading to allow-all.
 */
object GoalPlanningDiscoveryExclusions {
  const val CONTRACT_VERSION = "0.3"
  const val RESOURCE_PATH = "skillbill/contracts/goal-planning-discovery-exclusions.yaml"
  const val CONTRACT_FILE = "orchestration/contracts/goal-planning-discovery-exclusions.yaml"
  const val SCHEMA_FILE = "orchestration/contracts/goal-planning-discovery-exclusions-schema.yaml"

  private val KNOWN_KEYS = setOf("contract_version", "excluded_roots", "excluded_directory_names")

  private val contract: Contract by lazy { parse(readContract()) }

  val excludedRoots: List<String> get() = contract.roots

  val excludedDirectoryNames: List<String> get() = contract.directoryNames

  /**
   * Deny predicate over '/'-joined repo-relative paths. The path is normalized first, so interior
   * `.` and `..` segments cannot dress an excluded root up as an allowed one; a path that walks above
   * the repo root is denied outright. Roots match as whole-segment anchored prefixes, so
   * `platform-packsX/` passes; directory names match at any position, so nested
   * `runtime-kotlin/foo/build/` is denied exactly like repo-root `build/`.
   */
  fun isExcluded(relativePath: String): Boolean {
    val normalized = normalize(relativePath) ?: return true
    if (normalized.isEmpty()) return false
    if (excludedRoots.any { root -> "$normalized/".startsWith(root) }) return true
    val names = excludedDirectoryNames
    return normalized.split("/").any { segment -> segment in names }
  }

  /** Null when the path escapes the repository root, which callers must treat as denied. */
  private fun normalize(relativePath: String): String? {
    val segments = mutableListOf<String>()
    for (segment in relativePath.replace('\\', '/').split("/")) {
      when (segment) {
        "", "." -> Unit
        ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
        else -> segments.add(segment)
      }
    }
    return segments.joinToString("/")
  }

  internal data class Contract(val roots: List<String>, val directoryNames: List<String>)

  private fun readContract(): String =
    javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)?.use { stream -> stream.readBytes().decodeToString() }
      ?: throw InvalidGoalPlanningDiscoveryExclusionsSchemaError(
        "goal planning discovery exclusion contract is missing from the classpath at $RESOURCE_PATH",
      )

  internal fun parse(document: String): Contract {
    val root = loadRootMapping(document)
    requireKnownKeysOnly(root)
    requireSupportedVersion(root["contract_version"])
    return Contract(
      roots = requiredStringList(root, "excluded_roots").onEach(::requireNormalizedRoot),
      directoryNames = requiredStringList(root, "excluded_directory_names").onEach(::requireBareDirectoryName),
    )
  }

  private fun loadRootMapping(document: String): Map<*, *> {
    val loaded = try {
      Yaml().load<Any?>(document) as? Map<*, *>
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      null
    }
    return loaded ?: throw InvalidGoalPlanningDiscoveryExclusionsSchemaError(
      "goal planning discovery exclusion contract is not a YAML mapping",
    )
  }

  /**
   * The schema is closed, and so is this parser. Ignoring an unknown key would let a misspelled
   * `excluded_paths:` read as a deny rule that silently never applies while discovery walks the tree
   * the author believed was denied.
   */
  private fun requireKnownKeysOnly(root: Map<*, *>) {
    val unknown = root.keys.map(Any?::toString).filterNot { key -> key in KNOWN_KEYS }.sorted()
    if (unknown.isNotEmpty()) {
      throw InvalidGoalPlanningDiscoveryExclusionsSchemaError(
        "goal planning discovery exclusion contract declares unknown keys: ${unknown.joinToString(", ")}",
      )
    }
  }

  private fun requireSupportedVersion(version: Any?) {
    if (version != CONTRACT_VERSION) {
      throw InvalidGoalPlanningDiscoveryExclusionsSchemaError(
        "goal planning discovery exclusion contract_version '$version' is unsupported; expected '$CONTRACT_VERSION'",
      )
    }
  }

  private fun requiredStringList(root: Map<*, *>, key: String): List<String> {
    val entries = (root[key] as? List<*>)?.map { entry ->
      entry as? String ?: throw InvalidGoalPlanningDiscoveryExclusionsSchemaError(
        "goal planning discovery exclusion $key entry '$entry' is not a string",
      )
    }.orEmpty()
    if (entries.isEmpty()) {
      throw InvalidGoalPlanningDiscoveryExclusionsSchemaError(
        "goal planning discovery exclusion contract declares no $key",
      )
    }
    return entries
  }

  private fun requireBareDirectoryName(name: String) {
    val invalid = name.isBlank() || name in setOf(".", "..") || name.any { char -> char == '/' || char == '\\' }
    if (invalid) {
      throw InvalidGoalPlanningDiscoveryExclusionsSchemaError(
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
      throw InvalidGoalPlanningDiscoveryExclusionsSchemaError(
        "goal planning discovery exclusion root '$root' must be a normalized repo-relative prefix ending in '/'",
      )
    }
  }
}
