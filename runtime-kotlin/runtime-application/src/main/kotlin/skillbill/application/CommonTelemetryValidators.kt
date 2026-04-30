package skillbill.application

import skillbill.application.model.FeatureImplementStartedRequest

internal val issueKeyTypes = listOf("jira", "linear", "github", "other", "none")
internal val specInputTypes = listOf("raw_text", "pdf", "markdown_file", "image", "directory")
internal val featureSizes = listOf("SMALL", "MEDIUM", "LARGE")
internal val featureFlagPatterns = listOf("simple_conditional", "di_switch", "legacy", "none")
internal val historySignalValues = listOf("none", "irrelevant", "low", "medium", "high")

internal fun validateEnum(value: String, allowed: List<String>, fieldName: String): String? =
  if (value in allowed) null else "Invalid $fieldName '$value'. Allowed: ${allowed.joinToString(", ")}"

internal fun validateNonBlank(value: String, fieldName: String): String? =
  if (value.isBlank()) "$fieldName must be non-empty." else null

internal fun validatePositive(value: Int, fieldName: String): String? =
  if (value > 0) null else "$fieldName must be greater than 0."

internal fun validateIssueKeyType(request: FeatureImplementStartedRequest): String? =
  if (request.issueKey.isNotBlank() && request.issueKeyType == "none") {
    "issue_key_type must not be 'none' when issue_key is provided."
  } else {
    null
  }

internal fun validateSpecInputTypes(request: FeatureImplementStartedRequest): String? =
  if (request.specInputTypes.isEmpty()) "spec_input_types must contain at least one input type." else null

internal fun validateSpecWordCount(request: FeatureImplementStartedRequest): String? {
  val hasTextInput = request.specInputTypes.any { it != "image" }
  return if (hasTextInput && request.specWordCount <= 0) {
    "spec_word_count must be greater than 0 for text-based specs."
  } else {
    null
  }
}
