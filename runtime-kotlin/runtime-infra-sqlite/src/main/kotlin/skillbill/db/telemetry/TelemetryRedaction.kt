package skillbill.db.telemetry

import java.security.MessageDigest

const val REDACTED_ISSUE_KEY_PREFIX = "iss_"

private const val REDACTED_TOKEN_HEX_LENGTH = 16
private const val HEX_MASK = 0xff

/**
 * Maps a raw issue key to the value that goes on the wire. Only `full` is unredacted; every other
 * level (including an unknown or unresolved one) fails closed to the non-reversible substitute.
 */
fun redactIssueKey(issueKey: String, level: String, salt: String): String {
  if (issueKey.isBlank() || level == "full") {
    return issueKey
  }
  val digest = MessageDigest.getInstance("SHA-256").digest("$salt|$issueKey".toByteArray(Charsets.UTF_8))
  return REDACTED_ISSUE_KEY_PREFIX + hexEncode(digest).take(REDACTED_TOKEN_HEX_LENGTH)
}

internal fun hexEncode(bytes: ByteArray): String = buildString(bytes.size * 2) {
  bytes.forEach { byte -> append(((byte.toInt() and HEX_MASK) + 0x100).toString(16).substring(1)) }
}
