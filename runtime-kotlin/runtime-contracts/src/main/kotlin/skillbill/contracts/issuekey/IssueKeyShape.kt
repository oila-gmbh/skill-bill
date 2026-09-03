package skillbill.contracts.issuekey

import org.yaml.snakeyaml.Yaml
import skillbill.error.InvalidIssueKeySchemaError

const val ISSUE_KEY_SCHEMA_ID: String = "https://skill-bill.dev/contracts/issue-key-schema.yaml"
const val ISSUE_KEY_SCHEMA_RESOURCE: String = "skillbill/contracts/issue-key-schema.yaml"
const val ISSUE_KEY_SCHEMA_REPO_PATH: String = "orchestration/contracts/issue-key-schema.yaml"

val MAX_ISSUE_KEY_LENGTH: Int get() = IssueKeyShape.maxLength

fun isWellFormedIssueKey(issueKey: String): Boolean {
  val trimmed = issueKey.trim()
  return trimmed.isNotEmpty() &&
    trimmed.length <= MAX_ISSUE_KEY_LENGTH &&
    trimmed.none(Character::isISOControl)
}

fun normalizeIssueKey(issueKey: String?): String? = issueKey?.trim()?.also {
  require(it.isNotEmpty()) { "issue key cannot be blank." }
  require(it.length <= MAX_ISSUE_KEY_LENGTH) { "issue key must be at most $MAX_ISSUE_KEY_LENGTH characters." }
  require(it.none(Character::isISOControl)) { "issue key cannot contain control characters." }
}

fun normalizeRequiredIssueKey(issueKey: String): String = requireNotNull(normalizeIssueKey(issueKey))

fun malformedIssueKeyReason(field: String, receivedEcho: String): String =
  "$field is malformed: expected a non-blank issue key of at most $MAX_ISSUE_KEY_LENGTH characters " +
    "with no control characters, but received $receivedEcho"

object IssueKeyShape {
  const val RESOURCE_PATH: String = ISSUE_KEY_SCHEMA_RESOURCE

  private val contract: Contract by lazy { parse(readContract()) }

  val maxLength: Int get() = contract.maxLength
  val jsonSchemaPattern: String get() = contract.pattern

  internal data class Contract(
    val maxLength: Int,
    val pattern: String,
  )

  private fun readContract(): String =
    javaClass.classLoader.getResourceAsStream(RESOURCE_PATH)?.use { stream -> stream.readBytes().decodeToString() }
      ?: throw InvalidIssueKeySchemaError(
        "issue-key schema is missing from the classpath at $RESOURCE_PATH " +
          "(expected $ISSUE_KEY_SCHEMA_REPO_PATH)",
      )

  internal fun parse(document: String): Contract {
    val root = runCatching { Yaml().load<Any?>(document) }.getOrNull() as? Map<*, *>
      ?: throw InvalidIssueKeySchemaError("issue-key schema is not a YAML mapping")
    requireKnownKeysOnly(root)
    if (root["\$id"] != ISSUE_KEY_SCHEMA_ID) {
      throw InvalidIssueKeySchemaError(
        "issue-key schema \$id '${root["\$id"]}' is unsupported; expected '$ISSUE_KEY_SCHEMA_ID'",
      )
    }
    if (root["type"] != "string") {
      throw InvalidIssueKeySchemaError("issue-key schema type must be string")
    }
    val minLength = requiredPositiveInt(root, "minLength")
    if (minLength != 1) {
      throw InvalidIssueKeySchemaError("issue-key schema minLength must be 1")
    }
    val pattern = root["pattern"] as? String
      ?: throw InvalidIssueKeySchemaError("issue-key schema pattern must be a string")
    return Contract(
      maxLength = requiredPositiveInt(root, "maxLength"),
      pattern = pattern,
    )
  }

  private fun requireKnownKeysOnly(root: Map<*, *>) {
    val unknown = root.keys.map(Any?::toString).filterNot { key -> key in KNOWN_KEYS }.sorted()
    if (unknown.isNotEmpty()) {
      throw InvalidIssueKeySchemaError(
        "issue-key schema declares unknown keys: ${unknown.joinToString(", ")}",
      )
    }
  }

  private fun requiredPositiveInt(root: Map<*, *>, key: String): Int {
    val value = root[key] as? Number
      ?: throw InvalidIssueKeySchemaError("issue-key schema $key must be a positive integer")
    val intValue = value.toInt()
    if (intValue < 1) {
      throw InvalidIssueKeySchemaError("issue-key schema $key must be a positive integer")
    }
    return intValue
  }

  private val KNOWN_KEYS = setOf(
    "\$schema",
    "\$id",
    "title",
    "type",
    "minLength",
    "maxLength",
    "pattern",
  )
}
