package skillbill.contracts.goalplanning

import org.yaml.snakeyaml.Yaml
import skillbill.error.InvalidGoalVerificationBoundaryCapsSchemaError

object GoalVerificationBoundaryCaps {
  const val CONTRACT_VERSION = "0.2"
  const val RESOURCE_PATH = "skillbill/contracts/goal-verification-boundary-caps.yaml"
  const val CONTRACT_FILE = "orchestration/contracts/goal-verification-boundary-caps.yaml"
  const val SCHEMA_FILE = "orchestration/contracts/goal-verification-boundary-caps-schema.yaml"

  private val KNOWN_KEYS = setOf(
    "contract_version",
    "max_discovery_file_count",
    "max_headings_per_file",
    "max_catalog_headings",
    "history_recency_days",
    "max_selected_bodies",
    "max_body_bytes",
    "max_total_body_bytes",
    "max_boundary_file_bytes",
  )

  private val contract: Contract by lazy { parse(readContract()) }

  val maxDiscoveryFileCount: Int get() = contract.maxDiscoveryFileCount
  val maxHeadingsPerFile: Int get() = contract.maxHeadingsPerFile
  val maxCatalogHeadings: Int get() = contract.maxCatalogHeadings
  val historyRecencyDays: Int get() = contract.historyRecencyDays
  val maxSelectedBodies: Int get() = contract.maxSelectedBodies
  val maxBodyBytes: Int get() = contract.maxBodyBytes
  val maxTotalBodyBytes: Int get() = contract.maxTotalBodyBytes
  val maxBoundaryFileBytes: Long get() = contract.maxBoundaryFileBytes

  internal data class Contract(
    val maxDiscoveryFileCount: Int,
    val maxHeadingsPerFile: Int,
    val maxCatalogHeadings: Int,
    val historyRecencyDays: Int,
    val maxSelectedBodies: Int,
    val maxBodyBytes: Int,
    val maxTotalBodyBytes: Int,
    val maxBoundaryFileBytes: Long,
  )

  private fun readContract(): String =
    javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)?.use { stream -> stream.readBytes().decodeToString() }
      ?: throw InvalidGoalVerificationBoundaryCapsSchemaError(
        "goal verification boundary caps contract is missing from the classpath at $RESOURCE_PATH",
      )

  internal fun parse(document: String): Contract {
    val root = runCatching { Yaml().load<Any?>(document) }.getOrNull() as? Map<*, *>
      ?: throw InvalidGoalVerificationBoundaryCapsSchemaError(
        "goal verification boundary caps contract is not a YAML mapping",
      )
    requireKnownKeysOnly(root)
    requireSupportedVersion(root["contract_version"])
    return Contract(
      maxDiscoveryFileCount = requiredPositiveInt(root, "max_discovery_file_count"),
      maxHeadingsPerFile = requiredPositiveInt(root, "max_headings_per_file"),
      maxCatalogHeadings = requiredPositiveInt(root, "max_catalog_headings"),
      historyRecencyDays = requiredPositiveInt(root, "history_recency_days"),
      maxSelectedBodies = requiredPositiveInt(root, "max_selected_bodies"),
      maxBodyBytes = requiredPositiveInt(root, "max_body_bytes"),
      maxTotalBodyBytes = requiredPositiveInt(root, "max_total_body_bytes"),
      maxBoundaryFileBytes = requiredPositiveInt(root, "max_boundary_file_bytes").toLong(),
    )
  }

  private fun requireKnownKeysOnly(root: Map<*, *>) {
    val unknown = root.keys.map(Any?::toString).filterNot { key -> key in KNOWN_KEYS }.sorted()
    if (unknown.isNotEmpty()) {
      throw InvalidGoalVerificationBoundaryCapsSchemaError(
        "goal verification boundary caps contract declares unknown keys: ${unknown.joinToString(", ")}",
      )
    }
  }

  private fun requireSupportedVersion(version: Any?) {
    if (version != CONTRACT_VERSION) {
      throw InvalidGoalVerificationBoundaryCapsSchemaError(
        "goal verification boundary caps contract_version '$version' is unsupported; expected '$CONTRACT_VERSION'",
      )
    }
  }

  private fun requiredPositiveInt(root: Map<*, *>, key: String): Int {
    val value = root[key] as? Number
      ?: throw InvalidGoalVerificationBoundaryCapsSchemaError(
        "goal verification boundary caps $key must be a positive integer",
      )
    val intValue = value.toInt()
    if (intValue < 1) {
      throw InvalidGoalVerificationBoundaryCapsSchemaError(
        "goal verification boundary caps $key must be a positive integer",
      )
    }
    return intValue
  }
}
