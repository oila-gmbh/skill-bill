package skillbill.application

import skillbill.application.model.FeatureImplementFinishedRequest
import skillbill.application.model.FeatureImplementStartedRequest

private val auditResults = listOf("all_pass", "had_gaps", "skipped")
private val validationResults = listOf("pass", "fail", "skipped")
private val completionStatuses =
  listOf("completed", "abandoned_at_planning", "abandoned_at_implementation", "abandoned_at_review", "error")

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

private fun validateCompletedValidationResult(validationResult: String): String? = if (validationResult == "skipped") {
  "validation_result must not be skipped when completion_status is completed."
} else {
  null
}
