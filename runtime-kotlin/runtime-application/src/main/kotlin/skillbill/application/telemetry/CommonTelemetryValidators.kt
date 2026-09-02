package skillbill.application.telemetry

val specInputTypes = listOf("raw_text", "pdf", "markdown_file", "image", "directory")
val historySignalValues = listOf("none", "irrelevant", "low", "medium", "high")

fun validateEnum(value: String, allowed: List<String>, fieldName: String): String? =
  if (value in allowed) null else "Invalid $fieldName '$value'. Allowed: ${allowed.joinToString(", ")}"

fun validateNonBlank(value: String, fieldName: String): String? =
  if (value.isBlank()) "$fieldName must be non-empty." else null

fun validatePositive(value: Int, fieldName: String): String? =
  if (value > 0) null else "$fieldName must be greater than 0."
