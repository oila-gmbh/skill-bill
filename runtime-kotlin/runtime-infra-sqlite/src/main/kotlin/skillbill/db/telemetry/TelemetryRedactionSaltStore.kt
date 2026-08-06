package skillbill.db.telemetry

import java.security.SecureRandom
import java.sql.Connection

private const val REDACTION_SALT_KEY = "telemetry_redaction_salt"
private const val SALT_BYTE_LENGTH = 32

/**
 * Machine-local salt backing [redactIssueKey]. It is generated once, never leaves this machine, and
 * never appears in any telemetry payload.
 */
fun telemetryRedactionSalt(connection: Connection): String {
  readRedactionSalt(connection)?.let { return it }
  val generated = hexEncode(ByteArray(SALT_BYTE_LENGTH).also(SecureRandom()::nextBytes))
  connection.prepareStatement(
    "INSERT OR IGNORE INTO telemetry_local_secrets (secret_key, secret_value) VALUES (?, ?)",
  ).use { statement ->
    statement.bind(REDACTION_SALT_KEY, generated)
    statement.executeUpdate()
  }
  return readRedactionSalt(connection) ?: generated
}

private fun readRedactionSalt(connection: Connection): String? = connection.prepareStatement(
  "SELECT secret_value FROM telemetry_local_secrets WHERE secret_key = ?",
).use { statement ->
  statement.bind(REDACTION_SALT_KEY)
  statement.executeQuery().use { resultSet ->
    if (resultSet.next()) resultSet.getString("secret_value")?.takeIf(String::isNotBlank) else null
  }
}
