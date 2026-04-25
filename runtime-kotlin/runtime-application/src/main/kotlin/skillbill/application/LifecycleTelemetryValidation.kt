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
  request.specInputTypes.firstNotNullOfOrNull { validateEnum(it, specInputTypes, "spec_input_types") },
).firstOrNull()

fun validateFeatureImplementFinished(request: FeatureImplementFinishedRequest): String? = listOfNotNull(
  validateEnum(request.completionStatus, completionStatuses, "completion_status"),
  validateEnum(request.featureFlagPattern, featureFlagPatterns, "feature_flag_pattern"),
  validateEnum(request.auditResult, auditResults, "audit_result"),
  validateEnum(request.validationResult, validationResults, "validation_result"),
  validateEnum(request.boundaryHistoryValue, historySignalValues, "boundary_history_value"),
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
