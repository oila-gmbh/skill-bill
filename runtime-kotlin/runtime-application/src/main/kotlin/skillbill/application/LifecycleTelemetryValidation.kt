package skillbill.application

import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest
import skillbill.application.model.FeatureVerifyFinishedRequest
import skillbill.application.model.QualityCheckFinishedRequest
import skillbill.application.model.QualityCheckStartedRequest

private val issueKeyTypes = listOf("jira", "linear", "github", "other", "none")
private val specInputTypes = listOf("raw_text", "pdf", "markdown_file", "image", "directory")
private val featureSizes = listOf("SMALL", "MEDIUM", "LARGE")
private val featureFlagPatterns = listOf("simple_conditional", "di_switch", "legacy", "none")
private val historySignalValues = listOf("none", "irrelevant", "low", "medium", "high")
private val auditResults = listOf("all_pass", "had_gaps", "skipped")
private val validationResults = listOf("pass", "fail", "skipped")
private val completionStatuses =
  listOf("completed", "abandoned_at_planning", "abandoned_at_implementation", "abandoned_at_review", "error")
private val qualityCheckScopeTypes = listOf("files", "working_tree", "branch_diff", "repo")
private val qualityCheckResults = listOf("pass", "fail", "skipped", "unsupported_stack")
private val featureVerifyCompletionStatuses = listOf("completed", "abandoned_at_review", "abandoned_at_audit", "error")

fun validateFeatureImplementStarted(request: FeatureImplementStartedRequest): String? = listOfNotNull(
  validateEnum(request.featureSize, featureSizes, "feature_size"),
  validateEnum(request.issueKeyType, issueKeyTypes, "issue_key_type"),
  validateNonBlank(request.featureName, "feature_name"),
  validateNonBlank(request.issueKey, "issue_key"),
  validateIssueKeyType(request),
  validatePositive(request.acceptanceCriteriaCount, "acceptance_criteria_count"),
  validateSpecInputTypes(request),
  validateSpecWordCount(request),
  validateNonBlank(request.specSummary, "spec_summary"),
  request.specInputTypes.firstNotNullOfOrNull { validateEnum(it, specInputTypes, "spec_input_types") },
).firstOrNull()

fun validateFeatureImplementFinished(request: FeatureImplementFinishedRequest): String? = listOfNotNull(
  validateEnum(request.completionStatus, completionStatuses, "completion_status"),
  validateEnum(request.featureFlagPattern, featureFlagPatterns, "feature_flag_pattern"),
  validateEnum(request.auditResult, auditResults, "audit_result"),
  validateEnum(request.validationResult, validationResults, "validation_result"),
  validateEnum(request.boundaryHistoryValue, historySignalValues, "boundary_history_value"),
  validateCompletedFeatureImplementFinished(request),
).firstOrNull()

fun validateQualityCheckStarted(request: QualityCheckStartedRequest): String? =
  validateEnum(request.scopeType, qualityCheckScopeTypes, "scope_type")

fun validateQualityCheckFinished(request: QualityCheckFinishedRequest): String? =
  validateEnum(request.result, qualityCheckResults, "result")

fun validateFeatureVerifyFinished(request: FeatureVerifyFinishedRequest): String? = listOfNotNull(
  validateEnum(request.auditResult, auditResults, "audit_result"),
  validateEnum(request.completionStatus, featureVerifyCompletionStatuses, "completion_status"),
  validateEnum(request.historyRelevance, historySignalValues, "history_relevance"),
  validateEnum(request.historyHelpfulness, historySignalValues, "history_helpfulness"),
).firstOrNull()

private fun validateEnum(value: String, allowed: List<String>, fieldName: String): String? =
  if (value in allowed) null else "Invalid $fieldName '$value'. Allowed: ${allowed.joinToString(", ")}"

private fun validateNonBlank(value: String, fieldName: String): String? =
  if (value.isBlank()) "$fieldName must be non-empty." else null

private fun validatePositive(value: Int, fieldName: String): String? =
  if (value > 0) null else "$fieldName must be greater than 0."

private fun validateIssueKeyType(request: FeatureImplementStartedRequest): String? =
  if (request.issueKey.isNotBlank() && request.issueKeyType == "none") {
    "issue_key_type must not be 'none' when issue_key is provided."
  } else {
    null
  }

private fun validateSpecInputTypes(request: FeatureImplementStartedRequest): String? =
  if (request.specInputTypes.isEmpty()) "spec_input_types must contain at least one input type." else null

private fun validateSpecWordCount(request: FeatureImplementStartedRequest): String? {
  val hasTextInput = request.specInputTypes.any { it != "image" }
  return if (hasTextInput && request.specWordCount <= 0) {
    "spec_word_count must be greater than 0 for text-based specs."
  } else {
    null
  }
}

private fun validateCompletedFeatureImplementFinished(request: FeatureImplementFinishedRequest): String? {
  if (request.completionStatus != "completed") {
    return null
  }
  return listOfNotNull(
    validatePositive(request.planTaskCount, "plan_task_count"),
    validatePositive(request.planPhaseCount, "plan_phase_count"),
    validatePositive(request.tasksCompleted, "tasks_completed"),
    validatePositive(request.reviewIterations, "review_iterations"),
    validatePositive(request.auditIterations, "audit_iterations"),
    validateCompletedAuditResult(request.auditResult),
    validateCompletedValidationResult(request.validationResult),
  ).firstOrNull()
}

private fun validateCompletedAuditResult(auditResult: String): String? =
  if (auditResult == "skipped") "audit_result must not be skipped when completion_status is completed." else null

private fun validateCompletedValidationResult(validationResult: String): String? =
  if (validationResult == "skipped") {
    "validation_result must not be skipped when completion_status is completed."
  } else {
    null
  }
