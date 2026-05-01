package skillbill.mcp

object ReadianSecretRedactor {
  private const val REDACTED = "[REDACTED]"

  private val secretKeyPattern =
    Regex(
      "(access|refresh|session|auth|authorization|cookie|token|credential|password|secret)",
      RegexOption.IGNORE_CASE,
    )
  private val publicBoundaryKeys =
    setOf(
      "access_expires_at_epoch_ms",
      "auth_required",
      "auth_source",
      "authenticated",
      "credential_handling",
      "refreshed",
    )
  private val bearerPattern = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
  private val jwtPattern = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")
  private val readianSecretPattern =
    Regex("(?i)\\breadian[_-]?(?:rt|st|token|session)[A-Za-z0-9._-]*\\b")
  private val querySecretPattern =
    Regex("(?i)(access_token|refresh_token|session_id|token|cookie|authorization)=([^&\\s]+)")

  fun redact(value: Any?): Any? = when (value) {
    null -> null
    is Map<*, *> -> redactMap(value)
    is Iterable<*> -> value.map { entry -> redact(entry) }
    is Array<*> -> value.map { entry -> redact(entry) }
    is String -> redactString(value)
    else -> value
  }

  private fun redactMap(value: Map<*, *>): Map<String, Any?> = value.entries.associate { (key, entryValue) ->
    val keyText = key?.toString().orEmpty()
    keyText to if (keyText in publicBoundaryKeys) {
      redact(entryValue)
    } else if (secretKeyPattern.containsMatchIn(keyText)) {
      REDACTED
    } else {
      redact(entryValue)
    }
  }

  private fun redactString(value: String): String {
    var redacted = value
    redacted = bearerPattern.replace(redacted, REDACTED)
    redacted = jwtPattern.replace(redacted, REDACTED)
    redacted = readianSecretPattern.replace(redacted, REDACTED)
    redacted = querySecretPattern.replace(redacted) { match ->
      "${match.groupValues[1]}=$REDACTED"
    }
    return redacted
  }
}
